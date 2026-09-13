package tf.fog.autoangler.game

import android.graphics.Bitmap

object StateDetector {

    fun detect(frame: Bitmap): GameState {
        val w = frame.width; val h = frame.height

        // 1. Layar hasil tangkapan: XP bar pink muncul di tengah-atas
        if (hasResultScreen(frame)) return GameState.RESULT

        // 2. "Fish on the hook!" = pill badge gelap di tengah y≈27%
        if (hasCenterDarkBadge(frame, w, h, yFraction = 0.27f)) return GameState.HOOKED

        return GameState.IDLE
    }

    // Layar hasil: XP bar pink muncul di tengah-atas (H=300–345°, S≥0.45, V≥0.65).
    // Terverifikasi 6/6 tangkapan, 0 false positive. Dock kanan-bawah tetap gelap saat RESULT.
    private fun hasResultScreen(frame: Bitmap): Boolean {
        val W = frame.width; val H = frame.height
        val y0 = (H * 0.06).toInt(); val y1 = (H * 0.12).toInt()
        val x0 = (W * 0.38).toInt(); val x1 = (W * 0.62).toInt()
        val hsv = FloatArray(3)
        var pink = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = frame.getPixel(x.coerceIn(0, W - 1), y.coerceIn(0, H - 1))
                android.graphics.Color.colorToHSV(p, hsv)
                if (hsv[0] in 300f..345f && hsv[1] >= 0.45f && hsv[2] >= 0.65f) pink++
                x += 2
            }
            y += 2
        }
        return pink > 60   // video: ~200–760 saat RESULT, 0 selainnya
    }

    // "Fish on the hook!" pill badge = area gelap oval di tengah layar (bukan full-width)
    // Scan ±3% di sekitar yFraction untuk robustness
    private fun hasCenterDarkBadge(frame: Bitmap, w: Int, h: Int, yFraction: Float): Boolean {
        val yCenter = (h * yFraction).toInt()
        val yRange  = (h * 0.03).toInt().coerceAtLeast(3)
        val x0 = (w * 0.27).toInt(); val x1 = (w * 0.68).toInt()
        val sampleCount = 20
        val step = (x1 - x0) / sampleCount
        if (step <= 0) return false
        var bestRatio = 0f
        for (yTest in (yCenter - yRange)..(yCenter + yRange) step 4) {
            val y = yTest.coerceIn(0, h - 1)
            var dark = 0
            for (i in 0 until sampleCount) {
                val px = frame.getPixel((x0 + i * step).coerceIn(0, w - 1), y)
                val br = ((px shr 16 and 0xFF) + (px shr 8 and 0xFF) + (px and 0xFF)) / 3
                if (br < 90) dark++
            }
            bestRatio = maxOf(bestRatio, dark.toFloat() / sampleCount)
        }
        return bestRatio > 0.50f
    }
}
