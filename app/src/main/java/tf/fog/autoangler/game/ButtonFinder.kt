package tf.fog.autoangler.game

import android.graphics.Bitmap
import android.graphics.Point
import tf.fog.autoangler.util.Config
import tf.fog.autoangler.util.FileLog as Log

/**
 * Deteksi posisi tombol dari frame screen capture menggunakan pixel scanning.
 * Semua deteksi menggunakan centroid pixel putih (ikon tombol) atau pixel colorful (item bait).
 * Hasilnya dipakai sebagai koordinat tap, lebih akurat dari hardcoded %.
 */
object ButtonFinder {
    private const val TAG = "ButtonFinder"

    // ── Arrow buttons << >> (fishing mini-game) ───────────────────────────────
    // Zona scan (% layar) ada di Config.kt / bot_config.txt — bisa di-tuning tanpa rebuild.
    fun findArrowButtons(frame: Bitmap): Pair<Point?, Point?> {
        val w = frame.width; val h = frame.height
        val yMin = (h * Config.arrowZoneYMinPct).toInt(); val yMax = (h * Config.arrowZoneYMaxPct).toInt()
        val left  = findWhiteCluster(frame, (w * Config.leftZoneXMinPct).toInt(), (w * Config.leftZoneXMaxPct).toInt(), yMin, yMax)
        val right = findWhiteCluster(frame, (w * Config.rightZoneXMinPct).toInt(), (w * Config.rightZoneXMaxPct).toInt(), yMin, yMax)
        // Hanya log saat ketemu sesuatu — dipanggil tiap frame CASTING, hindari banjir log
        if (left != null || right != null) Log.d(TAG, "Arrows: left=$left right=$right")
        return Pair(left, right)
    }

    // ── Bottom action buttons (cast / equip bait / reel) ──────────────────────
    // 4 tombol kecil di kanan bawah, ikon putih di dalam lingkaran gelap
    // Y: 64-82%, X: kanan 40-100%
    // Return: sorted kiri→kanan, [0]=btn1 .. [3]=btn4(cast/hook)
    fun findBottomButtons(frame: Bitmap): List<Point> {
        val w = frame.width; val h = frame.height
        val yMin = (h * 0.64).toInt(); val yMax = (h * 0.82).toInt()
        val xMin = (w * 0.40).toInt()
        val zoneW = (w - xMin) / 4
        val result = mutableListOf<Point>()
        for (z in 0 until 4) {
            val zx0 = xMin + z * zoneW; val zx1 = zx0 + zoneW
            val pt = findWhiteCluster(frame, zx0, zx1, yMin, yMax)
            if (pt != null) result.add(pt)
        }
        Log.d(TAG, "BottomButtons(${result.size}): $result")
        return result.sortedBy { it.x }
    }

    // ── Switch Bait dialog ────────────────────────────────────────────────────
    // Bait item pertama (kiri): pixel colorful di x 18-50%, y 27-48%
    fun findFirstBaitItem(frame: Bitmap): Point? {
        val w = frame.width; val h = frame.height
        return findColorfulCluster(
            frame,
            xMin = (w * 0.18).toInt(), xMax = (w * 0.50).toInt(),
            yMin = (h * 0.27).toInt(), yMax = (h * 0.48).toInt()
        ).also { Log.d(TAG, "BaitItem: $it") }
    }

    // Switch button: terang/putih di x 40-65%, y 45-60%
    fun findSwitchButton(frame: Bitmap): Point? {
        val w = frame.width; val h = frame.height
        return findBrightCluster(
            frame,
            xMin = (w * 0.40).toInt(), xMax = (w * 0.65).toInt(),
            yMin = (h * 0.45).toInt(), yMax = (h * 0.60).toInt()
        ).also { Log.d(TAG, "SwitchBtn: $it") }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    // Centroid pixel putih (R>185, G>185, B>185) — ikon tombol
    private fun findWhiteCluster(
        frame: Bitmap, xMin: Int, xMax: Int, yMin: Int, yMax: Int, step: Int = 5
    ): Point? {
        var sumX = 0L; var sumY = 0L; var count = 0
        for (y in yMin until yMax step step) {
            for (x in xMin until xMax step step) {
                val p = frame.getPixel(x.coerceIn(0, frame.width - 1), y.coerceIn(0, frame.height - 1))
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if (r > 185 && g > 185 && b > 185) { sumX += x; sumY += y; count++ }
            }
        }
        return if (count >= 8) Point((sumX / count).toInt(), (sumY / count).toInt()) else null
    }

    // Centroid pixel colorful (saturation tinggi) — bait item icon
    private fun findColorfulCluster(
        frame: Bitmap, xMin: Int, xMax: Int, yMin: Int, yMax: Int, step: Int = 7
    ): Point? {
        var sumX = 0L; var sumY = 0L; var count = 0
        for (y in yMin until yMax step step) {
            for (x in xMin until xMax step step) {
                val p = frame.getPixel(x.coerceIn(0, frame.width - 1), y.coerceIn(0, frame.height - 1))
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val maxC = maxOf(r, g, b); val minC = minOf(r, g, b)
                val sat = if (maxC > 30) (maxC - minC).toFloat() / maxC else 0f
                if (sat > 0.35f && maxC > 100) { sumX += x; sumY += y; count++ }
            }
        }
        return if (count >= 8) Point((sumX / count).toInt(), (sumY / count).toInt()) else null
    }

    // Centroid pixel sangat terang (brightness>200) — tombol putih Switch/Cancel
    private fun findBrightCluster(
        frame: Bitmap, xMin: Int, xMax: Int, yMin: Int, yMax: Int, step: Int = 7
    ): Point? {
        var sumX = 0L; var sumY = 0L; var count = 0
        for (y in yMin until yMax step step) {
            for (x in xMin until xMax step step) {
                val p = frame.getPixel(x.coerceIn(0, frame.width - 1), y.coerceIn(0, frame.height - 1))
                val brightness = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                if (brightness > 200) { sumX += x; sumY += y; count++ }
            }
        }
        return if (count >= 8) Point((sumX / count).toInt(), (sumY / count).toInt()) else null
    }
}
