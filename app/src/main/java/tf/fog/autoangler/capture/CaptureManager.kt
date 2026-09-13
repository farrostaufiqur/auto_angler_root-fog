package tf.fog.autoangler.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import tf.fog.autoangler.util.FileLog as Log

class CaptureManager {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    var screenW = 0; var screenH = 0; var screenDpi = 0

    fun init(proj: MediaProjection, w: Int, h: Int, dpi: Int) {
        release()
        projection = proj
        screenW = w; screenH = h; screenDpi = dpi

        // Android 14+ wajib register callback SEBELUM createVirtualDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    release()
                }
            }, Handler(Looper.getMainLooper()))
        }

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = proj.createVirtualDisplay(
            "FishingCapture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        Log.d(TAG, "init ${w}x${h} dpi=$dpi")
    }

    // Full frame tanpa crop — untuk state detection
    fun acquireFullFrame(): Bitmap? = acquireRawBitmap()

    // Crop ke ROI — untuk fishing bar vision
    fun acquireFrame(roi: Rect): Bitmap? {
        val bmp = acquireRawBitmap() ?: return null
        if (roi.isEmpty) return bmp
        return try {
            val l = roi.left.coerceIn(0, screenW - 1)
            val t = roi.top.coerceIn(0, screenH - 1)
            val w = roi.width().coerceIn(1, screenW - l)
            val h = roi.height().coerceIn(1, screenH - t)
            val cropped = Bitmap.createBitmap(bmp, l, t, w, h)
            bmp.recycle()
            cropped
        } catch (e: Exception) {
            bmp.recycle(); null
        }
    }

    private fun acquireRawBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val ps = plane.pixelStride
            val rs = plane.rowStride
            val padding = (rs - ps * screenW) / ps
            val bmp = Bitmap.createBitmap(screenW + padding, screenH, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            // Trim padding kolom kanan jika ada
            if (padding > 0) {
                val trimmed = Bitmap.createBitmap(bmp, 0, 0, screenW, screenH)
                bmp.recycle()
                trimmed
            } else bmp
        } catch (e: Exception) {
            Log.e(TAG, "acquireRawBitmap error", e)
            null
        } finally {
            image.close()
        }
    }

    fun release() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
    }

    val isReady get() = projection != null

    companion object { private const val TAG = "CaptureManager" }
}
