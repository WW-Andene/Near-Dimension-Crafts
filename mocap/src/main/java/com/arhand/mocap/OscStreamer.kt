package com.arhand.mocap

import com.arhand.tracking.FaceExpressions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
// trySendBlocking removed — trySend() used instead (never throws on closed/conflated channel)
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.sign

// ─── OSC operating mode ───────────────────────────────────────────────────────

/**
 * G3 — Whether this device is sending or receiving hand pose data over OSC.
 *
 * | Mode    | Behaviour                                                        |
 * |---------|------------------------------------------------------------------|
 * | SEND    | Existing behaviour — broadcast joint rotations to a remote host  |
 * | RECEIVE | Listen on UDP port; render remote hand pose instead of camera    |
 */
enum class OscMode {
    SEND,
    RECEIVE;

    val label: String get() = when (this) {
        SEND    -> "SND"
        RECEIVE -> "RCV"
    }

    val description: String get() = when (this) {
        SEND    -> "Broadcast (send to remote)"
        RECEIVE -> "Listen (render remote hand)"
    }
}

// ─── Schema enum ─────────────────────────────────────────────────────────────

/**
 * OSC address schema presets.
 *
 * | Schema            | Compatible receiver                                         |
 * |-------------------|-------------------------------------------------------------|
 * | HANDY_DEFAULT     | Any OSC client; Handy's native `/hand/<Joint>/rotation`     |
 * | VMC               | VirtualMotionCapture, VSeeFace, 3tene, Warudo — VMC v2.3    |
 * | UNREAL_LIVE_LINK  | Unreal Engine 5 LiveLink OSC Source plugin                  |
 * | VSEEFACE          | VSeeFace legacy `/VMC/Ext/Bone/Pos` direct channel          |
 *
 * VMC is the recommended default for VTubing. Unreal LiveLink is recommended for
 * game-engine character control. VSeeFace legacy is kept for compatibility with
 * older setups already configured on a fixed address schema.
 */
enum class OscSchema {
    HANDY_DEFAULT,
    VMC,
    UNREAL_LIVE_LINK,
    VSEEFACE,
    /** OSC-3 — VTube Studio parameter injection format. */
    VTUBE_STUDIO;

    /** Short display label for UI chips. */
    val label: String get() = when (this) {
        HANDY_DEFAULT    -> "HANDY"
        VMC              -> "VMC"
        UNREAL_LIVE_LINK -> "UE LL"
        VSEEFACE         -> "VSF"
        VTUBE_STUDIO     -> "VTS"
    }

    /** Longer description for settings/tooltips. */
    val description: String get() = when (this) {
        HANDY_DEFAULT    -> "Handy native (/hand/<Joint>/rotation)"
        VMC              -> "VirtualMotionCapture / VSeeFace v2.3 (/VMC/Ext/Bon/Rot)"
        UNREAL_LIVE_LINK -> "Unreal LiveLink OSC (/LiveLink/<Subject>/Bone/<Joint>)"
        VSEEFACE         -> "VSeeFace legacy (/VMC/Ext/Bone/Pos)"
        VTUBE_STUDIO     -> "VTube Studio parameter injection (/VTubeStudioInternal/...)"
    }
}

// ─── OscStreamer ──────────────────────────────────────────────────────────────

/**
 * Streams live hand joint rotations as OSC (Open Sound Control) messages over UDP.
 *
 * OSC is the standard protocol used by professional mocap systems (Vicon, OptiTrack)
 * and is natively supported by Unreal Engine, Unity (via OSCJack / extOSC), TouchDesigner,
 * and Notch. This allows Handy to drive a desktop character rig in real time without a
 * USB connection or file export step.
 *
 * ## Schema presets
 *
 * The [schema] property selects the OSC address layout used when building bundles:
 *
 * | [OscSchema]        | Address pattern                              | Use case               |
 * |--------------------|----------------------------------------------|------------------------|
 * | HANDY_DEFAULT      | `/hand/<Joint>/rotation`                     | Custom receivers       |
 * | VMC                | `/VMC/Ext/Bon/Rot` + bone name arg           | VTubing (VSeeFace etc) |
 * | UNREAL_LIVE_LINK   | `/LiveLink/Hand/Bone/<BoneName>`             | Unreal Engine 5        |
 * | VSEEFACE           | `/VMC/Ext/Bone/Pos` + bone name arg          | VSeeFace legacy        |
 *
 * [schema] can be changed at any time, even while streaming — the next [sendFrame]
 * call will use the new schema.
 *
 * ## Bundle structure
 *
 * All per-frame messages are packed into a single OSC bundle with the timetag
 * set to the OSC "immediate" sentinel (`0x0000000000000001`). This lets receiving
 * tools (TouchDesigner, Unreal) process the whole frame atomically rather than
 * interleaving partial frames.
 *
 * ## Target latency
 *
 * Packet construction and send happen on a dedicated coroutine with a
 * [Channel.CONFLATED] queue — if the send coroutine falls behind the tracking
 * thread (e.g. OS scheduler jitter), the latest frame replaces the queued one
 * instead of building up a backlog. Over a local WiFi network (5 GHz) the
 * round-trip is typically under 2ms; one-way under 1ms.
 *
 * ## Usage
 * ```kotlin
 * val streamer = OscStreamer()
 * streamer.schema = OscSchema.VMC
 * streamer.start(host = "192.168.1.42", port = 39539)  // VMC default port
 *
 * // Each tracking frame:
 * streamer.sendFrame(retargetResult)
 *
 * // On teardown:
 * streamer.stop()
 * ```
 *
 * Thread safety: [sendFrame] is safe to call from any thread. [start] and [stop]
 * must be called from the same thread (typically the ViewModel/main thread).
 * [schema] is a plain var — write it from the main thread before or between frames.
 */
class OscStreamer {

    /**
     * Gap 3 — OSC connection health monitor.
     * Tracks sent/dropped frame counts and rolling frame rate.
     * Exposed so [AppViewModel] can collect it for HUD display.
     */
    val health = OscHealthMonitor()

    companion object {
        const val DEFAULT_PORT       = 9000
        const val DEFAULT_HOST       = "255.255.255.255"   // LAN broadcast

        /** VMC protocol default port (VirtualMotionCapture, VSeeFace). */
        const val VMC_PORT           = 39539
        /** Unreal LiveLink OSC default port. */
        const val UNREAL_LL_PORT     = 7771

        /** Suggested default port for each schema. */
        fun defaultPortFor(schema: OscSchema): Int = when (schema) {
            OscSchema.VMC              -> VMC_PORT
            OscSchema.VSEEFACE         -> VMC_PORT
            OscSchema.UNREAL_LIVE_LINK -> UNREAL_LL_PORT
            OscSchema.HANDY_DEFAULT    -> DEFAULT_PORT
            OscSchema.VTUBE_STUDIO     -> DEFAULT_PORT
        }

        /**
         * IMP-5 — Joint index → URL-safe name used in `/hand/<name>/velocity` messages.
         * Mirrors the display names in [MotionRecorder.JOINT_NAMES] as lowercase strings.
         */
        val JOINT_VELOCITY_NAMES: Map<Int, String> = mapOf(
            BoneRetargeter.JOINT_WRIST       to "wrist",
            BoneRetargeter.JOINT_THUMB_CMC   to "thumbCMC",
            BoneRetargeter.JOINT_THUMB_MCP   to "thumbMCP",
            BoneRetargeter.JOINT_THUMB_IP    to "thumbIP",
            BoneRetargeter.JOINT_INDEX_MCP   to "indexMCP",
            BoneRetargeter.JOINT_INDEX_PIP   to "indexPIP",
            BoneRetargeter.JOINT_INDEX_DIP   to "indexDIP",
            BoneRetargeter.JOINT_MIDDLE_MCP  to "middleMCP",
            BoneRetargeter.JOINT_MIDDLE_PIP  to "middlePIP",
            BoneRetargeter.JOINT_MIDDLE_DIP  to "middleDIP",
            BoneRetargeter.JOINT_RING_MCP    to "ringMCP",
            BoneRetargeter.JOINT_RING_PIP    to "ringPIP",
            BoneRetargeter.JOINT_RING_DIP    to "ringDIP",
            BoneRetargeter.JOINT_PINKY_MCP   to "pinkyMCP",
            BoneRetargeter.JOINT_PINKY_PIP   to "pinkyPIP",
            BoneRetargeter.JOINT_PINKY_DIP   to "pinkyDIP"
        )

        // OSC bundle header: "#bundle\0" + timetag (immediate = 1)
        private val BUNDLE_HEADER: ByteArray = run {
            val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            buf.put("#bundle\u0000".toByteArray(StandardCharsets.US_ASCII))
            buf.putInt(0)   // timetag seconds (immediate)
            buf.putInt(1)   // timetag fraction (immediate sentinel)
            buf.array()
        }

        // Pre-build type tag strings (immutable — same every frame)
        // ,ffff padded to 8 bytes  (4 floats = quaternion)
        private val TYPE_FFFF = ",ffff\u0000\u0000\u0000".toByteArray(StandardCharsets.US_ASCII)
        // ,fff  padded to 8 bytes  (3 floats = position)
        // OSC 1.0 §3.1.2: type tag strings are null-terminated then padded to a 4-byte boundary.
        // ",fff" = 4 chars + 1 null = 5 bytes → next 4-byte boundary = 8 bytes (3 pad nulls).
        // The previous value had only 1 null (5 bytes total), which caused every OSC receiver
        // (TouchDesigner, Python-osc, Unreal LiveLink) to misparse wrist position floats.
        private val TYPE_FFF  = ",fff\u0000\u0000\u0000\u0000".toByteArray(StandardCharsets.US_ASCII)
        // ,sffff padded to 8 bytes (1 string arg + 4 floats = VMC bone rotation)
        // The string arg is written inline; type tag length stays constant only
        // because we pre-size the string arg to a fixed-width oscString.
        private val TYPE_SFFFF = ",sffff\u0000\u0000".toByteArray(StandardCharsets.US_ASCII)
        // ,sfff padded to 8 bytes (1 string arg + 3 floats = VMC wrist pos)
        private val TYPE_SFFF  = ",sfff\u0000\u0000\u0000".toByteArray(StandardCharsets.US_ASCII)
        // ,f padded to 4 bytes (1 float — used by face blend-shape messages / velocity channel)
        private val TYPE_F = ",f\u0000\u0000".toByteArray(StandardCharsets.US_ASCII)
    }

    // ─── State ────────────────────────────────────────────────────────────────

    /** True when the streamer has been started and not yet stopped. */
    var isStreaming: Boolean = false
        private set

    /** Currently configured target host. */
    var host: String = DEFAULT_HOST
        private set

    /** Currently configured target port. */
    var port: Int = DEFAULT_PORT
        private set

    /**
     * Active OSC address schema.
     *
     * Safe to change at any time — even during active streaming. The next
     * [sendFrame] call picks up the new value. No restart required.
     */
    @Volatile
    var schema: OscSchema = OscSchema.HANDY_DEFAULT

    private var scope:   CoroutineScope? = null
    private var socket:  DatagramSocket? = null

    // sendVelocityFrame() don't call InetAddress.getByName() on the tracking thread every
    // frame — a DNS lookup that can block for 10–100 ms on non-literal hostnames.
    private var resolvedAddress: InetAddress? = null
    private var channel: Channel<RetargetResult>? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Open a UDP socket and start the send coroutine.
     *
     * @param host   Target IP address or hostname. Use `"255.255.255.255"` for LAN
     *               broadcast (requires `SO_BROADCAST` — set automatically). Use a
     *               specific IP for point-to-point (lower jitter).
     * @param port   Target UDP port. Defaults to [defaultPortFor] the current [schema].
     * @param schema Optional schema override — sets [schema] before starting.
     */
    fun start(
        host:   String    = DEFAULT_HOST,
        schema: OscSchema = this.schema,
        port:   Int       = defaultPortFor(schema)
    ) {
        if (isStreaming) stop()

        this.host   = host
        this.port   = port
        this.schema = schema

        val ch  = Channel<RetargetResult>(Channel.CONFLATED)
        channel = ch

        val skt = DatagramSocket().apply {
            broadcast    = true
            sendBufferSize = 65536
        }
        socket = skt

        val address = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            skt.close()
            socket  = null
            channel?.close()
            channel = null
            return
        }
        resolvedAddress = address

        scope = CoroutineScope(Dispatchers.IO + Job()).also { sc ->
            sc.launch {
                while (isActive) {
                    val result = ch.receive()
                    try {
                        val bundle = buildBundle(result)
                        val packet = DatagramPacket(bundle, bundle.size, address, port)
                        skt.send(packet)
                        health.onFrameSent()
                    } catch (_: Exception) {
                        health.onFrameDropped()
                        // Socket may have been closed — loop exits when scope is cancelled
                    }
                }
            }
        }

        isStreaming = true
    }

    /**
     * Stop streaming and close the UDP socket.
     * Safe to call multiple times or before [start].
     */
    fun stop() {
        isStreaming     = false
        scope?.cancel()
        scope           = null
        socket?.close()
        socket          = null
        resolvedAddress = null
        channel?.close()
        channel         = null
        health.reset()  // Gap 3
    }

    // ─── Frame send ──────────────────────────────────────────────────────────

    /**
     * IMP-11 — When true, [sendFaceFrame] emits face blend-shape floats as
     * ARKit-compatible OSC messages alongside the joint rotation bundle.
     * Receivers that don't understand face addresses silently ignore them.
     * Default: false (opt-in; no breaking change to existing receivers).
     */
    @Volatile
    var sendFace: Boolean = false

    /**
     * IMP-5 — When true, per-joint angular velocity (rad/s) is emitted as
     * `/hand/<joint>/velocity` float messages alongside rotation messages.
     * Requires IMP-1 QuaternionEmaFilter to have populated lastAngularVelocity.
     * Default: false (opt-in).
     */
    @Volatile
    var sendVelocity: Boolean = false

    /**
     * Enqueue a retarget result for transmission.
     *
     * Uses [Channel.CONFLATED]: if the send coroutine is busy, this frame replaces
     * any previously queued (unsent) frame. The tracking thread never blocks.
     *
     * Safe to call from any thread.
     */
    fun sendFrame(result: RetargetResult) {
        if (!isStreaming) return
        channel?.trySend(result)
    }

    /**
     * ARCH-1 — Unified send path for a complete [FullBodyRetargetResult] frame.
     *
     * OSC-1: For VMC and VSEEFACE schemas, builds a bundle containing both hand
     * and body joints using [buildBundleVmcFull]. For other schemas, falls back
     * to the existing hand-only channel path.
     *
     * Face expressions are emitted separately via [sendFaceFrame] when [sendFace] is true.
     * Joints absent from [FullBodyRetargetResult.body] are omitted (receiver uses T-pose).
     *
     * Fused-sl-v7 integration: when a [StructuredLightDepthSource] result is attached,
     * `/depth/map` (8×6 float grid) and `/depth/confidence` (scalar) are appended.
     */
    fun sendFrame(full: FullBodyRetargetResult) {
        if (!isStreaming) return
        val skt  = socket ?: return
        val addr = resolvedAddress ?: return

        when (schema) {
            OscSchema.VMC, OscSchema.VSEEFACE -> {
                // OSC-1: direct send of the full bundle (hand + body in one packet)
                try {
                    val bundle = buildBundleVmcFull(full)
                    skt.send(DatagramPacket(bundle, bundle.size, addr, port))
                } catch (_: Exception) {}
            }
            OscSchema.VTUBE_STUDIO -> {
                // OSC-3: VTS parameter injection — use primary hand result
                full.handPrimary?.let { channel?.trySend(it) }
            }
            else -> {
                // Other schemas: route primary hand through existing channel
                full.handPrimary?.let { channel?.trySend(it) }
            }
        }

        // Face expressions (schema-independent)
        if (sendFace && full.face != null) {
            sendFaceFrame(full.face)
        }

        // Fused-sl-v7: emit spatial depth map when SL data is available
        pendingSLResult?.let { slResult ->
            try {
                sendDepthMapBundle(slResult, full.depthConfidence, skt, addr)
            } catch (_: Exception) {}
            pendingSLResult = null
        }
    }

    /**
     * Attach a structured-light depth result to be included in the next [sendFrame] call.
     * Called from the SL processing path (AppViewModel) after each processed frame.
     */
    @Volatile var pendingSLResult: com.arhand.depth.StructuredLightDepthSource.SLResult? = null

    /**
     * Fused-sl-v7 port: emit `/depth/map` (8×6 normalised float grid) and
     * `/depth/confidence` (mean depth confidence scalar).
     *
     * Mirrors `sendOSC()` from fused-sl-v7.html — maps the full block grid down to
     * an 8×6 summary by averaging. Receivers can display a low-resolution depth map
     * or use it for scene-awareness without the full point cloud overhead.
     */
    private fun sendDepthMapBundle(
        slResult:   com.arhand.depth.StructuredLightDepthSource.SLResult,
        confidence: Float,
        skt:        java.net.DatagramSocket,
        addr:       java.net.InetAddress
    ) {
        val MAP_W = 8; val MAP_H = 6
        val depth = slResult.depth; val bW = slResult.bW; val bH = slResult.bH
        val map   = FloatArray(MAP_W * MAP_H)
        val stepX = bW.toFloat() / MAP_W; val stepY = bH.toFloat() / MAP_H
        for (my in 0 until MAP_H) {
            for (mx in 0 until MAP_W) {
                var sum = 0f; var cnt = 0
                val x0 = (mx * stepX).toInt(); val x1 = ((mx + 1) * stepX).toInt().coerceAtMost(bW)
                val y0 = (my * stepY).toInt(); val y1 = ((my + 1) * stepY).toInt().coerceAtMost(bH)
                for (by in y0 until y1) for (bx in x0 until x1) {
                    sum += depth[by * bW + bx]; cnt++
                }
                map[my * MAP_W + mx] = if (cnt > 0) sum / cnt else 0f
            }
        }

        // Build two OSC messages: /depth/map and /depth/confidence
        val mapMsg   = buildOscFloatArray("/depth/map", map)
        val confMsg  = buildOscFloat("/depth/confidence", confidence)
        val bundle   = buildBundle(listOf(mapMsg, confMsg))
        skt.send(DatagramPacket(bundle, bundle.size, addr, port))
    }

    /** Build an OSC bundle from multiple pre-encoded messages. */
    private fun buildBundle(messages: List<ByteArray>): ByteArray {
        val BUNDLE_HDR = byteArrayOf(0x23,0x62,0x75,0x6e,0x64,0x6c,0x65,0x00, // "#bundle\0"
                                      0,0,0,0,0,0,0,1)  // timetag: immediate
        var totalLen = BUNDLE_HDR.size
        for (m in messages) totalLen += 4 + m.size
        val out = ByteArray(totalLen)
        var off = 0
        BUNDLE_HDR.copyInto(out, off); off += BUNDLE_HDR.size
        for (m in messages) {
            out[off]   = (m.size shr 24).toByte(); out[off+1] = (m.size shr 16).toByte()
            out[off+2] = (m.size shr 8).toByte();  out[off+3] = m.size.toByte()
            off += 4; m.copyInto(out, off); off += m.size
        }
        return out
    }

    // ── v27 send methods ──────────────────────────────────────────────────────

    /**
     * Emit `/camera/pose` (6 floats: tx,ty,tz,rx,ry,rz) — SLAM accumulated pose.
     * Only sent when streaming is active.
     */
    fun sendCameraPose(tx: Float, ty: Float, tz: Float, rx: Float, ry: Float, rz: Float) {
        if (!isStreaming) return
        val skt = socket ?: return; val addr = resolvedAddress ?: return
        val msg = buildOscFloatArray("/camera/pose", floatArrayOf(tx, ty, tz, rx, ry, rz))
        runCatching { skt.send(DatagramPacket(msg, msg.size, addr, port)) }
    }

    /**
     * Emit `/camera/pose_delta` (3 floats: tx,ty,rz) — per-frame camera motion.
     */
    fun sendCameraPoseDelta(tx: Float, ty: Float, rz: Float) {
        if (!isStreaming) return
        val skt = socket ?: return; val addr = resolvedAddress ?: return
        val msg = buildOscFloatArray("/camera/pose_delta", floatArrayOf(tx, ty, rz))
        runCatching { skt.send(DatagramPacket(msg, msg.size, addr, port)) }
    }

    /**
     * Emit `/rppg` — float amplitude, int bpm.
     */
    fun sendRppg(amplitude: Float, bpm: Int) {
        if (!isStreaming) return
        val skt = socket ?: return; val addr = resolvedAddress ?: return
        val msg = buildOscMixed("/rppg", floatArgs = floatArrayOf(amplitude), intArgs = intArrayOf(bpm))
        runCatching { skt.send(DatagramPacket(msg, msg.size, addr, port)) }
    }

    /**
     * Emit `/depth/metric` — 48 floats (8×6 absolute metric depth in metres).
     */
    fun sendDepthMetric(depthBlocks: FloatArray) {
        if (!isStreaming) return
        val skt = socket ?: return; val addr = resolvedAddress ?: return
        val msg = buildOscFloatArray("/depth/metric", depthBlocks.copyOf(48.coerceAtMost(depthBlocks.size)))
        runCatching { skt.send(DatagramPacket(msg, msg.size, addr, port)) }
    }

    /**
     * Emit `/hand/N/occlusion` — 21 floats (0=clear, 1=occluded) for hand slot [handIdx].
     */
    fun sendHandOcclusion(handIdx: Int, occlusionProbs: FloatArray) {
        if (!isStreaming || handIdx !in 0..1) return
        val skt = socket ?: return; val addr = resolvedAddress ?: return
        val msg = buildOscFloatArray("/hand/$handIdx/occlusion",
            occlusionProbs.copyOf(21.coerceAtMost(occlusionProbs.size)))
        runCatching { skt.send(DatagramPacket(msg, msg.size, addr, port)) }
    }

    /**
     * Emit `/plane/N` — 4 floats (nx,ny,nz,d) + label string — for each detected plane.
     * [planes] is a list of FloatArray(4) + label string; only up to 3 planes are sent.
     */
    fun sendPlanes(planes: List<Pair<FloatArray, String>>) {
        if (!isStreaming) return
        val skt = socket ?: return; val addr = resolvedAddress ?: return
        planes.take(3).forEachIndexed { i, (coeffs, label) ->
            val msg = buildOscFloatStringMessage("/plane/$i", coeffs, label)
            runCatching { skt.send(DatagramPacket(msg, msg.size, addr, port)) }
        }
    }

    /** Build an OSC message with mixed float + int args: ,f…i… */
    private fun buildOscMixed(address: String, floatArgs: FloatArray, intArgs: IntArray): ByteArray {
        val addrPad = padOsc(address.toByteArray(Charsets.US_ASCII) + 0)
        val typeStr = "," + "f".repeat(floatArgs.size) + "i".repeat(intArgs.size)
        val typePad = padOsc(typeStr.toByteArray() + 0)
        val argBytes = ByteArray((floatArgs.size + intArgs.size) * 4)
        val buf = java.nio.ByteBuffer.wrap(argBytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        for (v in floatArgs) buf.putFloat(v)
        for (v in intArgs)  buf.putInt(v)
        return addrPad + typePad + argBytes
    }

    /** Build an OSC message with float array args followed by one string arg: ,f…s */
    private fun buildOscFloatStringMessage(address: String, floats: FloatArray, str: String): ByteArray {
        val addrPad = padOsc(address.toByteArray(Charsets.US_ASCII) + 0)
        val typeStr = "," + "f".repeat(floats.size) + "s"
        val typePad = padOsc(typeStr.toByteArray() + 0)
        val floatBytes = ByteArray(floats.size * 4)
        val buf = java.nio.ByteBuffer.wrap(floatBytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        for (v in floats) buf.putFloat(v)
        val strPad = padOsc(str.toByteArray(Charsets.US_ASCII) + 0)
        return addrPad + typePad + floatBytes + strPad
    }

    /** Build a single OSC float array message (/address ,ffff…). */
    private fun buildOscFloatArray(address: String, values: FloatArray): ByteArray {
        val addrPad = padOsc(address.toByteArray(Charsets.US_ASCII) + 0)
        val typePad = padOsc((",".toByteArray() + "f".repeat(values.size).toByteArray()) + 0)
        val argBytes = ByteArray(values.size * 4)
        val buf = java.nio.ByteBuffer.wrap(argBytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        for (v in values) buf.putFloat(v)
        return addrPad + typePad + argBytes
    }

    private fun buildOscFloat(address: String, value: Float): ByteArray =
        buildOscFloatArray(address, floatArrayOf(value))

    private fun padOsc(bytes: ByteArray): ByteArray {
        val pad = (4 - (bytes.size % 4)) % 4
        return bytes + ByteArray(pad)
    }

    /**
     * IMP-11 — Emit face blend-shape values as OSC float messages using ARKit-compatible
     * addresses. Each expression maps to a single `/face/<name>` float in [0, 1].
     *
     * Address → ARKit name mapping (VSeeFace / VTube Studio compatible):
     *   leftBlink      → /face/eyeBlinkLeft
     *   rightBlink     → /face/eyeBlinkRight
     *   jawOpen        → /face/jawOpen
     *   leftBrowRaise  → /face/browInnerUp
     *   rightBrowRaise → /face/browOuterUpRight
     *   mouthSmile     → /face/mouthSmileLeft  (single symmetric value)
     *
     * Only sent when [sendFace] is true and streaming is active. Existing
     * receivers that don't recognise these addresses ignore them — no breakage.
     */
    fun sendFaceFrame(fe: FaceExpressions) {
        if (!isStreaming || !sendFace) return
        val skt  = socket ?: return
        val addr = resolvedAddress ?: return

        val faceMessages = listOf(
            Pair("/face/eyeBlinkLeft",      fe.leftBlink),
            Pair("/face/eyeBlinkRight",     fe.rightBlink),
            Pair("/face/jawOpen",           fe.jawOpen),
            Pair("/face/browInnerUp",       fe.leftBrowRaise),
            Pair("/face/browOuterUpRight",  fe.rightBrowRaise),
            Pair("/face/mouthSmileLeft",    fe.mouthSmile),
            Pair("/face/mouthSmileRight",   fe.mouthSmile),
            // FACE-2 — iris gaze (VSeeFace / VTube Studio compatible)
            Pair("/face/eyeLeftX",          fe.leftGazeH),
            Pair("/face/eyeLeftY",          fe.leftGazeV),
            Pair("/face/eyeRightX",         fe.rightGazeH),
            Pair("/face/eyeRightY",         fe.rightGazeV)
        )

        for ((address, value) in faceMessages) {
            try {
                val msg = buildOscFloat(address, value)
                skt.send(DatagramPacket(msg, msg.size, addr, port))
            } catch (_: Exception) {}
        }

        // OSC-2 — VMC /VMC/Ext/Blendshape: VRM expression preset names
        if (schema == OscSchema.VMC || schema == OscSchema.VSEEFACE) {            val vmcBlendAddr = SchemaAddresses.oscString("/VMC/Ext/Blendshape")
            val vmcBlendMessages = listOf(
                "Blink_L" to fe.leftBlink,
                "Blink_R" to fe.rightBlink,
                "O"       to fe.jawOpen,
                "Joy"     to fe.mouthSmile
            ).filter { it.second > 0.01f }
            for ((name, value) in vmcBlendMessages) {
                try {
                    val nameBytes = SchemaAddresses.oscString(name)
                    val typetag = ",sf\u0000\u0000\u0000\u0000\u0000".toByteArray(StandardCharsets.US_ASCII)
                    val buf = ByteBuffer.allocate(vmcBlendAddr.size + typetag.size + nameBytes.size + 4)
                        .order(ByteOrder.BIG_ENDIAN)
                    buf.put(vmcBlendAddr); buf.put(typetag); buf.put(nameBytes); buf.putFloat(value)
                    val bytes = buf.array().copyOf(buf.position())
                    skt.send(DatagramPacket(bytes, bytes.size, addr, port))
                } catch (_: Exception) {}
            }
        }
    }

    // ─── IMP-5: velocity channel ──────────────────────────────────────────────

    /**
     * IMP-5 — Emit per-joint angular velocity as `/hand/<joint>/velocity` float messages.
     *
     * Velocity is computed by [QuaternionEmaFilter] as a byproduct — zero extra
     * differentiation. Receiving tools (VSeeFace, Warudo, Unity spring solvers) can use
     * these directly rather than differentiating consecutive quaternion frames.
     *
     * Only sent when [sendVelocity] is true. Existing receivers ignore unknown addresses.
     *
     * @param angularVelocity  Map of joint index → angular speed in rad/frame,
     *                         as produced by [QuaternionEmaFilter.lastAngularVelocity].
     */
    fun sendVelocityFrame(angularVelocity: Map<Int, Float>) {
        if (!isStreaming || !sendVelocity || angularVelocity.isEmpty()) return
        val skt  = socket ?: return
        val addr = resolvedAddress ?: return

        for ((jointIdx, vel) in angularVelocity) {
            val jointName = JOINT_VELOCITY_NAMES[jointIdx] ?: continue
            try {
                val msg = buildOscFloat("/hand/$jointName/velocity", vel)
                skt.send(DatagramPacket(msg, msg.size, addr, port))
            } catch (_: Exception) {}
        }
    }

    // ─── OSC bundle builder ───────────────────────────────────────────────────

    /**
     * Dispatch bundle construction to the active [schema].
     */
    private fun buildBundle(result: RetargetResult): ByteArray = when (schema) {
        OscSchema.HANDY_DEFAULT    -> buildBundleHandy(result)
        OscSchema.VMC              -> buildBundleVmc(result, SchemaAddresses.vmcRotAddr,
                                                              SchemaAddresses.vmcBoneBytes,
                                                              SchemaAddresses.vmcWristBone,
                                                              SchemaAddresses.vmcPosAddr)
        OscSchema.VSEEFACE         -> buildBundleVmc(result, SchemaAddresses.vsfRotAddr,
                                                              SchemaAddresses.vsfBoneBytes,
                                                              SchemaAddresses.vsfWristBone,
                                                              SchemaAddresses.vsfPosAddr)
        OscSchema.UNREAL_LIVE_LINK -> buildBundleUnreal(result)
        OscSchema.VTUBE_STUDIO     -> buildBundleVtubeStudio(result)
    }

    /**
     * OSC-3 — VTube Studio parameter injection bundle.
     *
     * VTS exposes a parameter injection API that accepts float values on named paths.
     * Handy maps hand joint rotations to VTS custom parameter names so users can
     * drive blendshapes, spring bones, or constraints in their VTS avatar.
     *
     * VTS parameter paths (sent as individual OSC float messages):
     *   /VTubeStudioInternal/Hand/Wrist/Yaw|Pitch|Roll
     *   /VTubeStudioInternal/Hand/<Finger>/<Joint>/<Axis>
     *
     * Face expressions (when sendFace=true) use VTS ARKit parameter paths:
     *   /VTubeStudioInternal/FaceAngle/Yaw|Pitch|Roll
     *   /VTubeStudioInternal/EyeOpenLeft|Right
     *   /VTubeStudioInternal/EyeLeftX|Y, EyeRightX|Y
     */
    private fun buildBundleVtubeStudio(result: RetargetResult): ByteArray {
        val buf = ByteBuffer.allocate(2048).order(ByteOrder.BIG_ENDIAN)
        buf.put(BUNDLE_HEADER)

        val fingerNames = listOf("Thumb", "Index", "Middle", "Ring", "Pinky")
        val jointNames  = listOf("MCP", "PIP", "DIP")

        // Wrist rotation as YPR floats
        val wrist = result.jointRotations[BoneRetargeter.JOINT_WRIST] ?: Quaternion.IDENTITY
        val (wy, wp, wr) = quaternionToVtsYPR(wrist)
        listOf("Yaw" to wy, "Pitch" to wp, "Roll" to wr).forEach { (axis, v) ->
            val addr = "/VTubeStudioInternal/Hand/Wrist/$axis"
            val msg  = buildOscFloat(addr, v)
            buf.putInt(msg.size); buf.put(msg)
        }

        // Finger joints — thumb has CMC/MCP/IP; others have MCP/PIP/DIP
        val fingerJointMap = listOf(
            listOf(BoneRetargeter.JOINT_THUMB_CMC,  BoneRetargeter.JOINT_THUMB_MCP,  BoneRetargeter.JOINT_THUMB_IP),
            listOf(BoneRetargeter.JOINT_INDEX_MCP,  BoneRetargeter.JOINT_INDEX_PIP,  BoneRetargeter.JOINT_INDEX_DIP),
            listOf(BoneRetargeter.JOINT_MIDDLE_MCP, BoneRetargeter.JOINT_MIDDLE_PIP, BoneRetargeter.JOINT_MIDDLE_DIP),
            listOf(BoneRetargeter.JOINT_RING_MCP,   BoneRetargeter.JOINT_RING_PIP,   BoneRetargeter.JOINT_RING_DIP),
            listOf(BoneRetargeter.JOINT_PINKY_MCP,  BoneRetargeter.JOINT_PINKY_PIP,  BoneRetargeter.JOINT_PINKY_DIP)
        )
        for ((fi, finger) in fingerNames.withIndex()) {
            for ((ji, jointIdx) in fingerJointMap[fi].withIndex()) {
                val q = result.jointRotations[jointIdx] ?: Quaternion.IDENTITY
                val curl = quaternionAngle(q)   // 0 = straight, π = fully bent
                val addr = "/VTubeStudioInternal/Hand/$finger/${jointNames[ji]}"
                val msg  = buildOscFloat(addr, curl / Math.PI.toFloat())   // normalise to [0,1]
                buf.putInt(msg.size); buf.put(msg)
            }
        }

        return buf.array().copyOf(buf.position())
    }

    /** Extract YPR Euler angles from a quaternion (radians, ZXY order). */
    private fun quaternionToVtsYPR(q: Quaternion): Triple<Float, Float, Float> {
        val sinP = 2f * (q.w * q.x - q.z * q.y)
        val pitch = if (kotlin.math.abs(sinP) >= 1f)
            kotlin.math.PI.toFloat() / 2f * sinP.sign
        else kotlin.math.asin(sinP)
        val yaw  = kotlin.math.atan2(2f * (q.w * q.y + q.x * q.z), 1f - 2f * (q.x * q.x + q.y * q.y))
        val roll = kotlin.math.atan2(2f * (q.w * q.z + q.x * q.y), 1f - 2f * (q.x * q.x + q.z * q.z))
        return Triple(yaw, pitch, roll)
    }

    private fun quaternionAngle(q: Quaternion): Float =
        2f * kotlin.math.acos(q.w.coerceIn(-1f, 1f))

    /**
     * OSC-1 — Build a VMC bundle from a [FullBodyRetargetResult], including body bones.
     *
     * Called from the unified [sendFrame] overload when schema is VMC or VSEEFACE.
     * Body joints are appended after hand joints using the same /VMC/Ext/Bon/Rot address.
     * Joints absent from [FullBodyRetargetResult.body] (visibility-gated) are omitted —
     * the VMC receiver uses T-pose for absent bones.
     */
    private fun buildBundleVmcFull(full: FullBodyRetargetResult): ByteArray {
        val hand = full.handPrimary
        val body = full.body

        // Estimate buffer: hand joints (16 × ~70b) + body joints (15 × ~70b) + header
        val buf = ByteBuffer.allocate(3200).order(ByteOrder.BIG_ENDIAN)
        buf.put(BUNDLE_HEADER)

        val rotAddr = SchemaAddresses.vmcRotAddr

        // ── Hand joints ───────────────────────────────────────────────────────
        if (hand != null) {
            for (j in MotionRecorder.HIERARCHY_ORDER) {
                val q       = hand.jointRotations[j] ?: Quaternion.IDENTITY
                val boneB   = SchemaAddresses.vmcBoneBytes[j] ?: continue
                val msgSize = rotAddr.size + TYPE_SFFFF.size + boneB.size + 16
                buf.putInt(msgSize)
                buf.put(rotAddr)
                buf.put(TYPE_SFFFF)
                buf.put(boneB)
                buf.putFloat(q.x); buf.putFloat(q.y); buf.putFloat(q.z); buf.putFloat(q.w)
            }
            // Wrist root position
            val pos      = hand.wristTransform.position
            val wristB   = SchemaAddresses.vmcWristBone
            val posAddr  = SchemaAddresses.vmcPosAddr
            val msgSize  = posAddr.size + TYPE_SFFF.size + wristB.size + 12
            buf.putInt(msgSize)
            buf.put(posAddr)
            buf.put(TYPE_SFFF)
            buf.put(wristB)
            buf.putFloat(pos.x); buf.putFloat(pos.y); buf.putFloat(pos.z)
        }

        // ── Body joints (OSC-1) ───────────────────────────────────────────────
        if (body != null) {
            for ((joint, q) in body.joints) {
                val boneB   = SchemaAddresses.vmcBodyBoneBytes[joint] ?: continue
                val msgSize = rotAddr.size + TYPE_SFFFF.size + boneB.size + 16
                buf.putInt(msgSize)
                buf.put(rotAddr)
                buf.put(TYPE_SFFFF)
                buf.put(boneB)
                buf.putFloat(q.x); buf.putFloat(q.y); buf.putFloat(q.z); buf.putFloat(q.w)
            }
        }

        return buf.array().copyOf(buf.position())
    }

    // ── HANDY_DEFAULT ─────────────────────────────────────────────────────────
    //
    // One message per joint:
    //   address  "/hand/<JointName>/rotation"   (pre-built oscString)
    //   typetag  ",ffff"
    //   data     qx qy qz qw  (4 × float32 BE)
    // + wrist position message:
    //   address  "/hand/wrist/position"
    //   typetag  ",fff"
    //   data     x y z  (3 × float32 BE)
    //
    // Total: ~16 + 17 × (addr~24 + typetag~8 + data~16) ≈ 832 bytes

    private fun buildBundleHandy(result: RetargetResult): ByteArray {
        val buf = ByteBuffer.allocate(1200).order(ByteOrder.BIG_ENDIAN)
        buf.put(BUNDLE_HEADER)

        for (j in MotionRecorder.HIERARCHY_ORDER) {
            val q       = result.jointRotations[j] ?: Quaternion.IDENTITY
            val addrB   = SchemaAddresses.handyRotAddr[j]!!
            val msgSize = addrB.size + TYPE_FFFF.size + 16
            buf.putInt(msgSize)
            buf.put(addrB)
            buf.put(TYPE_FFFF)
            buf.putFloat(q.x); buf.putFloat(q.y); buf.putFloat(q.z); buf.putFloat(q.w)
        }

        val pos     = result.wristTransform.position
        val msgSize = SchemaAddresses.handyPosAddr.size + TYPE_FFF.size + 12
        buf.putInt(msgSize)
        buf.put(SchemaAddresses.handyPosAddr)
        buf.put(TYPE_FFF)
        buf.putFloat(pos.x); buf.putFloat(pos.y); buf.putFloat(pos.z)

        return buf.array().copyOf(buf.position())
    }

    // ── VMC / VSEEFACE ────────────────────────────────────────────────────────
    //
    // VMC v2.3 spec: /VMC/Ext/Bon/Rot  s:boneName f:qx f:qy f:qz f:qw
    // VSeeFace legacy: /VMC/Ext/Bone/Pos  s:boneName f:qx f:qy f:qz f:qw
    //
    // Both share the same message structure; only the address bytes differ.
    // Args: [oscString boneName] [float qx] [float qy] [float qz] [float qw]
    //
    // Total: ~16 + 16 × (addr~20 + typetag~8 + boneName~28 + data~16) ≈ 1160 bytes

    private fun buildBundleVmc(
        result:    RetargetResult,
        rotAddr:   ByteArray,
        boneBytes: Map<Int, ByteArray>,
        wristBone: ByteArray,
        posAddr:   ByteArray
    ): ByteArray {
        val buf = ByteBuffer.allocate(1600).order(ByteOrder.BIG_ENDIAN)
        buf.put(BUNDLE_HEADER)

        for (j in MotionRecorder.HIERARCHY_ORDER) {
            val q       = result.jointRotations[j] ?: Quaternion.IDENTITY
            val boneB   = boneBytes[j]!!
            val msgSize = rotAddr.size + TYPE_SFFFF.size + boneB.size + 16
            buf.putInt(msgSize)
            buf.put(rotAddr)
            buf.put(TYPE_SFFFF)
            buf.put(boneB)
            buf.putFloat(q.x); buf.putFloat(q.y); buf.putFloat(q.z); buf.putFloat(q.w)
        }

        // Wrist root position — same pattern, bone = "RightHand"
        val pos     = result.wristTransform.position
        val msgSize = posAddr.size + TYPE_SFFF.size + wristBone.size + 12
        buf.putInt(msgSize)
        buf.put(posAddr)
        buf.put(TYPE_SFFF)
        buf.put(wristBone)
        buf.putFloat(pos.x); buf.putFloat(pos.y); buf.putFloat(pos.z)

        return buf.array().copyOf(buf.position())
    }

    // ── UNREAL_LIVE_LINK ──────────────────────────────────────────────────────
    //
    // UE5 LiveLink OSC plugin: /LiveLink/<Subject>/Bone/<BoneName>  f:qx f:qy f:qz f:qw
    // Subject defaults to "Hand"; configurable via unrealSubject field if needed.
    // Address is pre-built per joint; no string argument needed.
    //
    // Total: ~16 + 16 × (addr~40 + typetag~8 + data~16) ≈ 1040 bytes

    private fun buildBundleUnreal(result: RetargetResult): ByteArray {
        val buf = ByteBuffer.allocate(1400).order(ByteOrder.BIG_ENDIAN)
        buf.put(BUNDLE_HEADER)

        for (j in MotionRecorder.HIERARCHY_ORDER) {
            val q       = result.jointRotations[j] ?: Quaternion.IDENTITY
            val addrB   = SchemaAddresses.unrealRotAddr[j]!!
            val msgSize = addrB.size + TYPE_FFFF.size + 16
            buf.putInt(msgSize)
            buf.put(addrB)
            buf.put(TYPE_FFFF)
            buf.putFloat(q.x); buf.putFloat(q.y); buf.putFloat(q.z); buf.putFloat(q.w)
        }

        // Root position on dedicated address
        val pos     = result.wristTransform.position
        val msgSize = SchemaAddresses.unrealPosAddr.size + TYPE_FFF.size + 12
        buf.putInt(msgSize)
        buf.put(SchemaAddresses.unrealPosAddr)
        buf.put(TYPE_FFF)
        buf.putFloat(pos.x); buf.putFloat(pos.y); buf.putFloat(pos.z)

        return buf.array().copyOf(buf.position())
    }
}
