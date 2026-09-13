package tf.fog.autoangler.vision

import android.graphics.Bitmap
import tf.fog.autoangler.util.Config

data class VisionResult(
    val yellowX: Int = 0,
    val greenLeft: Int = 0,
    val greenRight: Int = 0,
    val valid: Boolean = false
)

object VisionProcessor {
    private val hsv = FloatArray(3)

    fun process(roi: Bitmap): VisionResult {
        val w = roi.width; val h = roi.height
        val scanY0 = (h / 2 - h / 6).coerceAtLeast(0)
        val scanY1 = (h / 2 + h / 6).coerceAtMost(h - 1)

        // Skip 34% kiri: Fish Stamina arc (x≈685–800, H≈48)
        // Green xEnd 60%: stop sebelum Fishing Line ring cyan (x≈67%, H≈205) → false green
        // Yellow xEnd 85%: cyan H≈205 tidak overlap yellow H=40-65, aman scan lebih lebar
        //   → cursor bisa terdeteksi walau drift ke kanan zona hijau
        val xStart      = (w * 0.34f).toInt()
        val xEndGreen   = (w * 0.70f).toInt()  // diperluas: zona bisa sampai ~70%, ring cyan H≈205° > greenHMax=182°
        val xEndYellow  = (w * 0.85f).toInt()

        val pixels = IntArray(w)
        var yellowSumX = 0L; var yellowCount = 0
        var greenL = Int.MAX_VALUE; var greenR = Int.MIN_VALUE

        for (y in scanY0..scanY1) {
            roi.getPixels(pixels, 0, w, 0, y, w, 1)
            for (x in xStart until xEndYellow) {
                val c = pixels[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                rgbToHsv(r, g, b, hsv)
                val hue = hsv[0]; val sat = hsv[1]; val `val` = hsv[2]

                when {
                    isYellow(hue, sat, `val`) -> { yellowSumX += x; yellowCount++ }
                    x < xEndGreen && isGreen(hue, sat, `val`) -> {
                        if (x < greenL) greenL = x; if (x > greenR) greenR = x
                    }
                }
            }
        }

        if (yellowCount == 0 || greenL == Int.MAX_VALUE) return VisionResult()
        return VisionResult(
            yellowX = (yellowSumX / yellowCount).toInt(),
            greenLeft = greenL,
            greenRight = greenR,
            valid = true
        )
    }

    private fun isYellow(h: Float, s: Float, v: Float) =
        h >= Config.yellowHMin && h <= Config.yellowHMax &&
        s >= Config.yellowSMin && v >= Config.yellowVMin

    private fun isGreen(h: Float, s: Float, v: Float) =
        h >= Config.greenHMin && h <= Config.greenHMax &&
        s >= Config.greenSMin && v >= Config.greenVMin

    private fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
        val delta = max - min
        out[2] = max
        out[1] = if (max > 0f) delta / max else 0f
        if (delta == 0f) { out[0] = 0f; return }
        var hue = when (max) {
            rf -> 60f * (((gf - bf) / delta) % 6f)
            gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }
        if (hue < 0f) hue += 360f
        out[0] = hue
    }
}
