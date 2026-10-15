package com.arhand.mocap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import com.arhand.util.Vec3

/**
 * G3 — Cross-device hand sync via OSC: receiver mode.
 *
 * Listens on a UDP port for incoming OSC bundles broadcast by another device
 * running Handy in SEND mode. Parses joint rotation messages and wrist position
 * messages into a [RetargetResult] that can be fed directly to the renderer.
 *
 * Supports HANDY_DEFAULT schema:
 *   /hand/<JointName>/rotation   f:qx f:qy f:qz f:qw
 *   /hand/wrist/position         f:x  f:y  f:z
 *
 * Also parses VMC schema (/VMC/Ext/Bon/Rot  s:boneName f:qx f:qy f:qz f:qw)
 * so two Handy devices can sync in any schema the sender uses.
 *
 * Usage:
 * ```kotlin
 * val receiver = OscReceiver()
 * receiver.start(port = 9000)
 * // Collect frames:
 * receiver.frames.collect { result -> renderer.latestRetargetResult = result }
 * // On teardown:
 * receiver.stop()
 * ```
 *
 * Thread safety: [start] / [stop] must be called from the same thread.
 * [frames] is safe to collect from any coroutine.
 */
class OscReceiver {

    companion object {
        const val DEFAULT_PORT = 9000
        private const val BUFFER_SIZE = 4096

        // Handy address prefixes (ASCII, no null terminator for startsWith matching)
        private const val PREFIX_HAND_ROT = "/hand/"
        private const val SUFFIX_ROTATION = "/rotation"
        private const val ADDR_WRIST_POS  = "/hand/wrist/position"

        // VMC address
        private const val VMC_BON_ROT  = "/VMC/Ext/Bon/Rot"
        private const val VSF_BONE_POS = "/VMC/Ext/Bone/Pos"

        // Reverse-lookup: joint name string → BoneRetargeter JOINT_* constant
        // Covers HANDY_DEFAULT joint names
        private val HANDY_NAME_TO_JOINT: Map<String, Int> = mapOf(
            "Wrist"      to BoneRetargeter.JOINT_WRIST,
            "ThumbCMC"   to BoneRetargeter.JOINT_THUMB_CMC,
            "ThumbMCP"   to BoneRetargeter.JOINT_THUMB_MCP,
            "ThumbIP"    to BoneRetargeter.JOINT_THUMB_IP,
            "IndexMCP"   to BoneRetargeter.JOINT_INDEX_MCP,
            "IndexPIP"   to BoneRetargeter.JOINT_INDEX_PIP,
            "IndexDIP"   to BoneRetargeter.JOINT_INDEX_DIP,
            "MiddleMCP"  to BoneRetargeter.JOINT_MIDDLE_MCP,
            "MiddlePIP"  to BoneRetargeter.JOINT_MIDDLE_PIP,
            "MiddleDIP"  to BoneRetargeter.JOINT_MIDDLE_DIP,
            "RingMCP"    to BoneRetargeter.JOINT_RING_MCP,
            "RingPIP"    to BoneRetargeter.JOINT_RING_PIP,
            "RingDIP"    to BoneRetargeter.JOINT_RING_DIP,
            "PinkyMCP"   to BoneRetargeter.JOINT_PINKY_MCP,
            "PinkyPIP"   to BoneRetargeter.JOINT_PINKY_PIP,
            "PinkyDIP"   to BoneRetargeter.JOINT_PINKY_DIP
        )

        // VMC/VSeeFace bone name → JOINT_* constant (right-hand bones)
        private val VMC_NAME_TO_JOINT: Map<String, Int> = mapOf(
            "RightHand"                to BoneRetargeter.JOINT_WRIST,
            "RightThumbProximal"       to BoneRetargeter.JOINT_THUMB_CMC,
            "RightThumbIntermediate"   to BoneRetargeter.JOINT_THUMB_MCP,
            "RightThumbDistal"         to BoneRetargeter.JOINT_THUMB_IP,
            "RightIndexProximal"       to BoneRetargeter.JOINT_INDEX_MCP,
            "RightIndexIntermediate"   to BoneRetargeter.JOINT_INDEX_PIP,
            "RightIndexDistal"         to BoneRetargeter.JOINT_INDEX_DIP,
            "RightMiddleProximal"      to BoneRetargeter.JOINT_MIDDLE_MCP,
            "RightMiddleIntermediate"  to BoneRetargeter.JOINT_MIDDLE_PIP,
            "RightMiddleDistal"        to BoneRetargeter.JOINT_MIDDLE_DIP,
            "RightRingProximal"        to BoneRetargeter.JOINT_RING_MCP,
            "RightRingIntermediate"    to BoneRetargeter.JOINT_RING_PIP,
            "RightRingDistal"          to BoneRetargeter.JOINT_RING_DIP,
            "RightLittleProximal"      to BoneRetargeter.JOINT_PINKY_MCP,
            "RightLittleIntermediate"  to BoneRetargeter.JOINT_PINKY_PIP,
            "RightLittleDistal"        to BoneRetargeter.JOINT_PINKY_DIP,
            // Left hand mirroring (treated as same slot for single-hand remote presence)
            "LeftHand"                 to BoneRetargeter.JOINT_WRIST,
            "LeftThumbProximal"        to BoneRetargeter.JOINT_THUMB_CMC,
            "LeftThumbIntermediate"    to BoneRetargeter.JOINT_THUMB_MCP,
            "LeftThumbDistal"          to BoneRetargeter.JOINT_THUMB_IP,
            "LeftIndexProximal"        to BoneRetargeter.JOINT_INDEX_MCP,
            "LeftIndexIntermediate"    to BoneRetargeter.JOINT_INDEX_PIP,
            "LeftIndexDistal"          to BoneRetargeter.JOINT_INDEX_DIP,
            "LeftMiddleProximal"       to BoneRetargeter.JOINT_MIDDLE_MCP,
            "LeftMiddleIntermediate"   to BoneRetargeter.JOINT_MIDDLE_PIP,
            "LeftMiddleDistal"         to BoneRetargeter.JOINT_MIDDLE_DIP,
            "LeftRingProximal"         to BoneRetargeter.JOINT_RING_MCP,
            "LeftRingIntermediate"     to BoneRetargeter.JOINT_RING_PIP,
            "LeftRingDistal"           to BoneRetargeter.JOINT_RING_DIP,
            "LeftLittleProximal"       to BoneRetargeter.JOINT_PINKY_MCP,
            "LeftLittleIntermediate"   to BoneRetargeter.JOINT_PINKY_PIP,
            "LeftLittleDistal"         to BoneRetargeter.JOINT_PINKY_DIP
        )
    }

    // ─── State ────────────────────────────────────────────────────────────────

    var isReceiving: Boolean = false
        private set

    var port: Int = DEFAULT_PORT
        private set

    private var scope:  CoroutineScope? = null
    private var socket: DatagramSocket? = null

    /**
     * Emits a [RetargetResult] each time a complete OSC bundle has been parsed
     * from the network. Replay = 0 (no stale frames for new collectors).
     */
    private val _frames = MutableSharedFlow<RetargetResult>(extraBufferCapacity = 4)
    val frames: SharedFlow<RetargetResult> = _frames

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Bind a UDP socket on [port] and start the receive loop.
     *
     * If another device sends HANDY_DEFAULT or VMC bundles to this device's IP
     * (or the LAN broadcast address) on [port], [frames] will emit parsed results.
     *
     * @param port  UDP port to listen on. Defaults to [DEFAULT_PORT] (9000).
     */
    fun start(port: Int = DEFAULT_PORT) {
        if (isReceiving) stop()

        this.port = port

        val skt = try {
            DatagramSocket(port).apply {
                receiveBufferSize = 65536
                soTimeout = 0  // block until packet arrives
            }
        } catch (e: Exception) {
            return  // port already bound or permission denied; caller checks isReceiving
        }
        socket = skt
        isReceiving = true

        scope = CoroutineScope(Dispatchers.IO + Job()).also { sc ->
            sc.launch {
                val buf    = ByteArray(BUFFER_SIZE)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive) {
                    try {
                        skt.receive(packet)
                        val result = parseBundle(buf, packet.length) ?: continue
                        _frames.tryEmit(result)
                    } catch (_: Exception) {
                        // Socket closed → loop exits when scope is cancelled
                    }
                }
            }
        }
    }

    /**
     * Stop the receive loop and close the UDP socket.
     * Safe to call multiple times or before [start].
     */
    fun stop() {
        isReceiving = false
        scope?.cancel()
        scope   = null
        socket?.close()
        socket  = null
    }

    // ─── OSC bundle parser ────────────────────────────────────────────────────

    /**
     * Parse an OSC bundle or message from [data] (first [length] bytes).
     *
     * Returns a [RetargetResult] if at least one joint rotation was parsed, or
     * null if the data is not a recognisable OSC packet.
     *
     * Handles:
     *   - OSC bundles (#bundle header + nested messages)
     *   - Bare OSC messages (no bundle wrapper)
     *
     * Schemas parsed:
     *   - HANDY_DEFAULT  (/hand/<Joint>/rotation   ,ffff  qx qy qz qw)
     *   - HANDY_DEFAULT  (/hand/wrist/position     ,fff   x  y  z)
     *   - VMC / VSeeFace (/VMC/Ext/Bon/Rot         ,sffff boneName qx qy qz qw)
     *   - VMC wrist pos  (/VMC/Ext/Bon/Pos         ,sfff  boneName x  y  z)  [ignored — position in wrist rot msg]
     */
    private fun parseBundle(data: ByteArray, length: Int): RetargetResult? {
        if (length < 8) return null
        val bb = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)

        val rotations = mutableMapOf<Int, Quaternion>()
        var wristPos  = Vec3(0f, 0f, 0f)

        val firstByte = data[0]
        val firstChar = firstByte.toInt().toChar()

        when {
            firstChar == '#' -> {
                // OSC bundle: "#bundle\0" (8 bytes) + timetag (8 bytes) + messages
                if (length < 16) return null
                val header = String(data, 0, 8, StandardCharsets.US_ASCII)
                if (header != "#bundle\u0000") return null
                bb.position(16)  // skip "#bundle\0" + 8-byte timetag
                while (bb.remaining() >= 4) {
                    val msgSize = runCatching { bb.int }.getOrNull() ?: break
                    if (msgSize <= 0 || msgSize > bb.remaining()) break
                    val msgStart = bb.position()
                    parseMessage(data, msgStart, msgSize, rotations) { wristPos = it }
                    bb.position(msgStart + msgSize)
                }
            }
            firstChar == '/' -> {
                // Bare OSC message (no bundle wrapper)
                parseMessage(data, 0, length, rotations) { wristPos = it }
            }
            else -> return null
        }

        if (rotations.isEmpty()) return null

        return RetargetResult(
            jointRotations = rotations,
            wristTransform = WristTransform(
                position = wristPos,
                rotation = rotations[BoneRetargeter.JOINT_WRIST] ?: Quaternion(0f, 0f, 0f, 1f)
            )
        )
    }

    /**
     * ENGINE_ARCHITECTURE.md §8.3 — parsed floats build a [Quaternion] directly with no
     * NaN/Inf guard or normalization, unlike [BoneRetargeter]'s own quaternions (always
     * unit-length via `shortestArcQuaternion`). A malformed or adversarial remote sender
     * could otherwise push a non-unit or NaN quaternion straight to
     * `renderer.latestRetargetResult`. Returns null (reject) for non-finite input rather
     * than normalizing garbage into something that merely looks valid.
     */
    private fun sanitizeQuaternion(qx: Float, qy: Float, qz: Float, qw: Float): Quaternion? {
        if (!qx.isFinite() || !qy.isFinite() || !qz.isFinite() || !qw.isFinite()) return null
        return Quaternion(qx, qy, qz, qw).normalized()
    }

    /**
     * Parse a single OSC message starting at [offset] in [data] with [size] bytes.
     * Detected joint rotations are put into [rotations]; wrist position triggers [onWristPos].
     */
    private fun parseMessage(
        data:       ByteArray,
        offset:     Int,
        size:       Int,
        rotations:  MutableMap<Int, Quaternion>,
        onWristPos: (Vec3) -> Unit
    ) {
        if (size < 4) return
        val bb = ByteBuffer.wrap(data, offset, size).order(ByteOrder.BIG_ENDIAN)

        // Read OSC address string (null-terminated, padded to 4 bytes)
        val address = readOscString(bb) ?: return

        // Read type tag string (starts with ',', padded to 4 bytes)
        val typeTag = readOscString(bb) ?: return
        if (!typeTag.startsWith(",")) return

        when {
            // ── HANDY_DEFAULT: /hand/<Joint>/rotation  ,ffff  qx qy qz qw ──────
            address.startsWith(PREFIX_HAND_ROT) && address.endsWith(SUFFIX_ROTATION)
                    && typeTag == ",ffff" -> {
                val jointName = address
                    .removePrefix(PREFIX_HAND_ROT)
                    .removeSuffix(SUFFIX_ROTATION)
                val joint = HANDY_NAME_TO_JOINT[jointName] ?: return
                if (bb.remaining() < 16) return
                val qx = bb.float; val qy = bb.float; val qz = bb.float; val qw = bb.float
                sanitizeQuaternion(qx, qy, qz, qw)?.let { rotations[joint] = it }
            }

            // ── HANDY_DEFAULT: /hand/wrist/position  ,fff  x y z ─────────────
            address == ADDR_WRIST_POS && typeTag == ",fff" -> {
                if (bb.remaining() < 12) return
                onWristPos(Vec3(bb.float, bb.float, bb.float))
            }

            // ── VMC: /VMC/Ext/Bon/Rot  ,sffff  boneName qx qy qz qw ─────────
            (address == VMC_BON_ROT || address == VSF_BONE_POS) && typeTag == ",sffff" -> {
                val boneName = readOscString(bb) ?: return
                if (bb.remaining() < 16) return
                val joint = VMC_NAME_TO_JOINT[boneName] ?: return
                val qx = bb.float; val qy = bb.float; val qz = bb.float; val qw = bb.float
                sanitizeQuaternion(qx, qy, qz, qw)?.let { rotations[joint] = it }
            }

            // ── VMC wrist position: /VMC/Ext/Bon/Pos  ,sfff  boneName x y z ──
            address == "/VMC/Ext/Bon/Pos" && typeTag == ",sfff" -> {
                val boneName = readOscString(bb) ?: return
                if (bb.remaining() < 12) return
                // Only capture wrist/root bone position
                if (boneName == "RightHand" || boneName == "LeftHand") {
                    onWristPos(Vec3(bb.float, bb.float, bb.float))
                }
            }
        }
    }

    /**
     * Read a null-terminated OSC string from [bb], advancing position to the next
     * 4-byte boundary after the null terminator.
     * Returns null if the buffer is exhausted before a null byte is found.
     */
    private fun readOscString(bb: ByteBuffer): String? {
        val start = bb.position()
        val bytes = bb.array()
        val arrayOffset = bb.arrayOffset()

        // Using start + bb.remaining() is wrong on the second readOscString call within
        // the same message — remaining() decreases as the buffer is consumed, making the
        // bound smaller than the actual available data. The absolute limit is always correct.
        val absoluteLimit = arrayOffset + bb.limit()
        var end = start
        while (arrayOffset + end < absoluteLimit) {
            if (bytes[arrayOffset + end] == 0.toByte()) break
            end++
        }
        if (arrayOffset + end >= absoluteLimit) return null  // no null terminator found
        val str = String(bytes, arrayOffset + start, end - start, StandardCharsets.US_ASCII)
        val rawLen = end - start + 1   // include null byte
        val padded = (rawLen + 3) / 4 * 4
        val newPos = start + padded
        if (newPos > bb.limit()) return null
        bb.position(newPos)
        return str
    }
}
