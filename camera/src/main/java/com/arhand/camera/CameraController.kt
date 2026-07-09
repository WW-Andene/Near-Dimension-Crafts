package com.arhand.camera

import android.content.Context
import android.util.Size
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

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(TARGET_WIDTH, TARGET_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
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
}
