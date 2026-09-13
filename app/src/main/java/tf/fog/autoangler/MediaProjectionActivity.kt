package tf.fog.autoangler

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import tf.fog.autoangler.overlay.OverlayService

class MediaProjectionActivity : Activity() {

    private val REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            // Direct call ke service — activity masih alive di sini,
            // menghindari masalah Android 14 timing dengan BroadcastReceiver
            OverlayService.instance?.onProjectionGranted(resultCode, data)
        }
        finish()
    }
}
