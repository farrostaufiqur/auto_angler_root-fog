package tf.fog.autoangler.game

import android.graphics.Bitmap
import android.graphics.Rect
import tf.fog.autoangler.util.Config

object AutoCalibrator {

    // Posisi tombol (% layar) ada di Config.kt / bot_config.txt — bisa di-tuning tanpa rebuild.
    fun calibrate(screenW: Int, screenH: Int): ButtonLayout = ButtonLayout(
        castX  = (screenW * Config.castXPct).toInt(), castY  = (screenH * Config.castYPct).toInt(),
        reelX  = (screenW * Config.castXPct).toInt(), reelY  = (screenH * Config.castYPct).toInt(),
        leftArrowX  = (screenW * Config.leftArrowXPct).toInt(), leftArrowY  = (screenH * Config.leftArrowYPct).toInt(),
        rightArrowX = (screenW * Config.rightArrowXPct).toInt(), rightArrowY = (screenH * Config.rightArrowYPct).toInt(),
        screenW = screenW, screenH = screenH
    )

    // Saat FISHING pertama terdeteksi: scan top 20% untuk cari baris dengan kuning DAN hijau
    fun findFishingBarRoi(frame: Bitmap, screenW: Int, screenH: Int): Rect? {
        val maxY = (frame.height * 0.14).toInt().coerceAtMost(frame.height - 1)
        val cx = frame.width / 2
        val halfSpan = (frame.width * 0.22).toInt()
        var firstRow = -1; var lastRow = -1

        for (y in 0 until maxY) {
            var hasYellow = false; var hasGreen = false
            for (dx in -halfSpan..halfSpan step 6) {
                val x = (cx + dx).coerceIn(0, frame.width - 1)
                val pixel = frame.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 180 && g > 150 && b < 130) hasYellow = true
                if (g > 150 && g > r + 55 && g > b + 20) hasGreen = true
            }
            if (hasYellow || hasGreen) {
                if (firstRow == -1) firstRow = y
                lastRow = y
            }
        }

        return if (firstRow != -1 && lastRow > firstRow)
            Rect(0, (firstRow - 8).coerceAtLeast(0), screenW, lastRow + 8)
        else null
    }
}
