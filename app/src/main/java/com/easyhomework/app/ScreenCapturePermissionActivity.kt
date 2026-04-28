package com.easyhomework.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.easyhomework.app.service.FloatingBallService
import com.easyhomework.app.service.ScreenCaptureService

/**
 * Transparent activity that requests MediaProjection permission.
 * Launched when the user clicks the floating ball and no projection exists.
 */
class ScreenCapturePermissionActivity : Activity() {

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Start the capture service with the permission
            ScreenCaptureService.start(this, result.resultCode, result.data!!)

            // Hide the floating ball and wait for capture
            FloatingBallService.getInstance()?.hideFloatingBall()

            // Request capture after a short delay to let the service initialize
            android.os.Handler(mainLooper).postDelayed({
                ScreenCaptureService.requestCapture()
            }, 500)
        } else {
            // User denied, show the ball again
            FloatingBallService.getInstance()?.showFloatingBallAgain()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
