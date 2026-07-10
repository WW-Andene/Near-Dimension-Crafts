package com.arhand.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * CameraX pipeline — replaces the raw Camera2 implementation.
 *
 * Camera2's CameraDevice.close() is asynchronous: the device is not fully
 * released until the OS fires CameraDevice.StateCallback.onClosed(). The old
 * implementation called start() immediately after stop(), which raced that
 * callback and produced ERROR_CAMERA_IN_USE or a deadlock on many devices.
 *
 * CameraX ProcessCameraProvider serialises bind/unbind internally and waits
 * for the previous session to close before opening a new one. switchCamera()
 * is now a single unbindAll() + bindToLifecycle() — no manual teardown required.
 */
class CameraController(
    private val context:       Context,
    private val frameProvider: CameraFrameProvider
) {
    companion object {
        const val TARGET_WIDTH  = 640
        const val TARGET_HEIGHT = 480
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera:         Camera? = null
    // Recreated in start(): stop() shuts this executor down, and a shutdown
    // ExecutorService rejects any further setAnalyzer() submissions, so a
    // restart (camera re-bind, lifecycle re-entry) needs a fresh instance.
    private var analysisExecutor = Executors.newSingleThreadExecutor()
    private var currentFacing    = CameraSelector.LENS_FACING_FRONT
    private var lifecycleOwner:  LifecycleOwner? = null

    fun start(owner: LifecycleOwner, facing: Int = CameraSelector.LENS_FACING_FRONT) {
        lifecycleOwner = owner
        currentFacing  = facing
        if (analysisExecutor.isShutdown) {
            analysisExecutor = Executors.newSingleThreadExecutor()
        }
        ProcessCameraProvider.getInstance(context).also { future ->
            future.addListener({
                cameraProvider = future.get()
                bindCamera()
            }, ContextCompat.getMainExecutor(context))
        }
    }

    private fun bindCamera() {
        val owner    = lifecycleOwner ?: return
        val provider = cameraProvider  ?: return

        val selector = CameraSelector.Builder()
            .requireLensFacing(currentFacing)
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setTargetResolution(Size(TARGET_WIDTH, TARGET_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Have CameraX convert YUV -> RGBA itself (its internal, hardware-accelerated
            // path) instead of handing us raw YUV_420_888 to convert by hand every frame —
            // see CameraFrameProvider.rgbaToBitmap for the other half of this.
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        applyHighestFpsRange(analysisBuilder, currentFacing)
        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(analysisExecutor, frameProvider::onImageProxy)

        frameProvider.isFrontCamera = (currentFacing == CameraSelector.LENS_FACING_FRONT)

        provider.unbindAll()
        camera = provider.bindToLifecycle(owner, selector, analysis)
    }

    fun switchCamera() {
        currentFacing = if (currentFacing == CameraSelector.LENS_FACING_FRONT)
            CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        bindCamera()
    }

    fun isFrontFacing() = currentFacing == CameraSelector.LENS_FACING_FRONT

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    /**
     * ImageAnalysis has no target-frame-rate knob of its own — CameraX leaves the AE
     * routine to pick a rate for the chosen resolution, which defaults to 30fps on most
     * devices/cameras even when a faster range is available. Request the widest supported
     * AE target FPS range instead of a hardcoded one (a fixed guess like (60,60) would
     * throw/be ignored on cameras that don't support it — this only ever selects from the
     * camera's own advertised [CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES],
     * so there's nothing here for a specific device to reject). Best-effort: on any failure
     * (older devices, camera2 quirks) this silently leaves CameraX's own default in place —
     * same fallback shape as [com.arhand.depth.ArCoreDepthSource]/[GrayscaleCamera]'s existing
     * device-variance guards. See ENGINE_ARCHITECTURE.md §4.7 (Core-layer lag) — this is the
     * separate camera-stream-rate half of "still not 60fps", not the per-frame processing cost
     * that DepthChannelBudget addresses.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyHighestFpsRange(builder: ImageAnalysis.Builder, facing: Int) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val wantFacing = if (facing == CameraSelector.LENS_FACING_BACK)
                CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT

            val best = manager.cameraIdList
                .asSequence()
                .map { manager.getCameraCharacteristics(it) }
                .firstOrNull { it.get(CameraCharacteristics.LENS_FACING) == wantFacing }
                ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.maxByOrNull { it.upper }

            if (best != null) {
                Camera2Interop.Extender(builder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best)
            }
        } catch (_: Throwable) {
            // Leave CameraX's default frame-rate selection in place.
        }
    }
}
