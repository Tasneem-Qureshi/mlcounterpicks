package com.dripagency.mlbbdraft.capture

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible activity used by the notification's "Enable screen detection" action: Android only
 * lets an Activity show the screen-capture consent dialog.  Result goes straight to the service.
 */
class ProjectionConsentActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) ScreenCaptureService.start(this, res.resultCode, data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}
