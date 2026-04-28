package com.easyhomework.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import com.easyhomework.app.service.FloatingBallService
import com.easyhomework.app.service.ScreenCaptureService

/**
 * Transparent activity that requests MediaProjection permission.
 * Launched when the user clicks the floating ball and no projection exists.
 * Uses traditional startActivityForResult (compatible with plain Activity).
 */
class ScreenCapturePermissionActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_MEDIA_PROJECTION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                // Start the capture service with the permission
                ScreenCaptureService.start(this, resultCode, data)

                // Hide the floating ball and wait for capture
                FloatingBallService.getInstance()?.hideFloatingBall()

                // Request capture after a short delay to let the service initialize
                Handler(mainLooper).postDelayed({
                    ScreenCaptureService.requestCapture()
                }, 500)
            } else {
                // User denied, show the ball again
                FloatingBallService.getInstance()?.showFloatingBallAgain()
            }
            finish()
        }
    }
}
