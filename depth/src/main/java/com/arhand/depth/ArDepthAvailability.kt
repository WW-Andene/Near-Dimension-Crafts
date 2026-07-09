package com.arhand.depth

import android.app.Activity
import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

/**
 * B6 — ARCore Depth API availability gate.
 *
 * Depth mode must only be offered when:
 *  1. ArCoreApk reports SUPPORTED_INSTALLED (device + Play Services for AR present), and
 *  2. The created [Session] reports `isDepthModeSupported(AUTOMATIC)` (device has the
 *     hardware/driver support for the Depth API — not all ARCore devices do).
 *
 * If either check fails, [DepthScannerPanel] stays in "PHASE 2 (unavailable)" and the
 * existing capsule-SDF [DepthCarver] remains the only reconstruction path.
 */
object ArDepthAvailability {

    /**
     * Quick synchronous check of ARCore install/support state.
     * Does NOT guarantee Depth API support — call [isDepthApiSupported] for that.
     *
     * Note: on first call, if availability is UNKNOWN, ArCoreApk triggers an async
     * check. Callers should re-check after a short delay (e.g. on next app foreground)
     * if this returns false due to UNKNOWN_CHECKING.
     */
    fun isArCoreSupported(context: Context): Boolean {
        return try {
            ArCoreApk.getInstance().checkAvailability(context).isSupported
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Full gate per B6 spec: SUPPORTED_INSTALLED AND device supports the Depth API.
     * This briefly creates and closes a [Session] — only call off the main thread
     * or cache the result, since Session creation can take tens of milliseconds.
     */
    fun isDepthApiSupported(context: Context): Boolean {
        val availability = try {
            ArCoreApk.getInstance().checkAvailability(context)
        } catch (_: Throwable) {
            return false
        }
        if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED) return false

        var session: Session? = null
        return try {
            session = Session(context)
            session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        } catch (_: Throwable) {
            false
        } finally {
            session?.close()
        }
    }

    /**
     * Request ARCore install/update via the standard ArCoreApk flow. Call from an
     * Activity when [isArCoreSupported] returns false but the user explicitly
     * toggled depth mode on (e.g. SUPPORTED_NOT_INSTALLED / SUPPORTED_APK_TOO_OLD).
     *
     * @return true if a request was made (caller should expect onResume re-check),
     *         false if no install action was needed/possible.
     */
    fun requestInstall(activity: Activity, userRequestedInstall: Boolean): Boolean {
        return try {
            val status = ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)
            status == ArCoreApk.InstallStatus.INSTALL_REQUESTED
        } catch (_: Throwable) {
            false
        }
    }
}
