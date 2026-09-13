package tf.fog.autoangler.util

import android.content.Context
import android.graphics.Rect
import tf.fog.autoangler.util.FileLog as Log
import java.io.File

data class CalibrationConfig(
    val roiRect: Rect = Rect(),
    val leftBtnX: Int = 0,
    val leftBtnY: Int = 0,
    val rightBtnX: Int = 0,
    val rightBtnY: Int = 0,
    // true = tombol kiri menggeser marker ke kanan
    val leftBtnMovesRight: Boolean = true,
    val eventDevice: String = "/dev/input/event0",
    val isCalibrated: Boolean = false
)

/**
 * SEMUA angka yang bisa di-tuning ada di sini — satu tempat. Nilai default di bawah dipakai
 * kalau bot_config.txt belum ada (file dibuat otomatis saat pertama kali service jalan, isi
 * salinan nilai default ini). Untuk MENGUBAH tuning tanpa minta rebuild: edit bot_config.txt
 * langsung di HP (getExternalFilesDir, folder sama dgn bot_log.txt) pakai text editor apa
 * saja, simpan, lalu SVC OFF → Start Overlay Service lagi (atau tutup-buka app) — nilai baru
 * langsung dipakai, TIDAK perlu APK baru.
 */
object Config {
    private const val PREFS = "fishing_bot_prefs"
    private const val CONFIG_FILE = "bot_config.txt"

    // --- Arrow push pulse timing (ms) ---
    // (pulseBaseMs/pulseMaxMs/jitterMs dihapus — `input tap` mode Root tak punya parameter
    // durasi, jadi durasi tap proporsional itu dulu cuma beban komputasi tanpa efek nyata)
    var marginPx: Int = 8
    var deadzonePx: Int = 12
    var interPulseMs: Long = 10L       // delay saat luar zona: cepat kejar
    var interPulseInZoneMs: Long = 30L // delay saat dalam zona: cegah queue + overshoot
    var autoStopBarMissingMs: Long = 3000L

    // Durasi tekan-tahan tap (ms) — dipakai eksplisit di titik pemanggilan injector.tap()
    // utk KEDUA mode (satu variabel, satu tempat ubah). Root (`input tap`) TAK PUNYA
    // parameter durasi sama sekali — mekanisme Root sengaja TIDAK diubah, nilai ini
    // cuma "lewat" tanpa efek di sana. Non-Root (GestureDescription.StrokeDescription)
    // BENAR-BENAR memakainya sebagai durasi gesture — efek nyata cuma di mode ini.
    var tapHoldMs: Long = 50L

    // --- Deteksi cursor macet → auto-stop (BotController) ---
    var stuckWindowMs: Long = 400L    // lama jendela sebelum menilai "macet"
    var stuckMinMovePx: Int = 1        // gerak <Npx dianggap "tak bergerak"
    var stuckErrorThreshold: Int = 5   // jumlah kesalahan beruntun sebelum auto-stop

    // --- Game loop timing (ms) (FullGameController) ---
    var loopMs: Long = 40L
    var castingTimeoutMs: Long = 20_000L

    // --- Root shell watchdog (ms) (RootInjector) ---
    var shellWedgeTimeoutMs: Long = 1500L

    // --- HSV thresholds (vision) ---
    var yellowHMin: Float = 40f
    var yellowHMax: Float = 65f
    var yellowSMin: Float = 0.45f
    var yellowVMin: Float = 0.55f

    var greenHMin: Float = 150f
    var greenHMax: Float = 182f
    var greenSMin: Float = 0.50f
    var greenVMin: Float = 0.60f

    // --- Posisi tombol default, % layar 0.0-1.0 (AutoCalibrator) ---
    var castXPct: Double = 0.883
    var castYPct: Double = 0.875
    var leftArrowXPct: Double = 0.1458
    var leftArrowYPct: Double = 0.786
    var rightArrowXPct: Double = 0.860
    var rightArrowYPct: Double = 0.789

    // --- Zona scan panah utk auto-deteksi, % layar (ButtonFinder) ---
    var arrowZoneYMinPct: Double = 0.70
    var arrowZoneYMaxPct: Double = 0.87
    var leftZoneXMinPct: Double = 0.05
    var leftZoneXMaxPct: Double = 0.26
    var rightZoneXMinPct: Double = 0.75
    var rightZoneXMaxPct: Double = 0.97

    // --- Batas sanity-check koordinat panah, % layar (FullGameController) ---
    var arrowSaneLeftXMinPct: Double = 0.02
    var arrowSaneLeftXMaxPct: Double = 0.40
    var arrowSaneRightXMinPct: Double = 0.50
    var arrowSaneRightXMaxPct: Double = 0.95
    var arrowSaneMinGapPct: Double = 0.25
    var arrowSaneYMinPct: Double = 0.60
    var arrowSaneYMaxPct: Double = 0.92

    // ── Muat dari bot_config.txt ─────────────────────────────────────────────

    fun loadFromFile(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, CONFIG_FILE)
        if (!file.exists()) {
            try {
                file.writeText(defaultFileContents())
                Log.d("Config", "bot_config.txt dibuat baru (nilai default) di ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e("Config", "Gagal buat bot_config.txt", e)
            }
            return
        }
        try {
            var applied = 0
            file.readLines().forEach { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEach
                val idx = line.indexOf('=')
                if (idx < 0) return@forEach
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (applyValue(key, value)) applied++
            }
            Log.d("Config", "bot_config.txt dimuat: $applied nilai diterapkan (${file.absolutePath})")
        } catch (e: Exception) {
            Log.e("Config", "Gagal baca bot_config.txt, pakai default", e)
        }
    }

    private fun applyValue(key: String, value: String): Boolean {
        when (key) {
            "marginPx" -> marginPx = value.toIntOrNull() ?: return invalid(key, value)
            "deadzonePx" -> deadzonePx = value.toIntOrNull() ?: return invalid(key, value)
            "interPulseMs" -> interPulseMs = value.toLongOrNull() ?: return invalid(key, value)
            "interPulseInZoneMs" -> interPulseInZoneMs = value.toLongOrNull() ?: return invalid(key, value)
            "autoStopBarMissingMs" -> autoStopBarMissingMs = value.toLongOrNull() ?: return invalid(key, value)
            "tapHoldMs" -> tapHoldMs = value.toLongOrNull() ?: return invalid(key, value)
            "stuckWindowMs" -> stuckWindowMs = value.toLongOrNull() ?: return invalid(key, value)
            "stuckMinMovePx" -> stuckMinMovePx = value.toIntOrNull() ?: return invalid(key, value)
            "stuckErrorThreshold" -> stuckErrorThreshold = value.toIntOrNull() ?: return invalid(key, value)
            "loopMs" -> loopMs = value.toLongOrNull() ?: return invalid(key, value)
            "castingTimeoutMs" -> castingTimeoutMs = value.toLongOrNull() ?: return invalid(key, value)
            "shellWedgeTimeoutMs" -> shellWedgeTimeoutMs = value.toLongOrNull() ?: return invalid(key, value)
            "yellowHMin" -> yellowHMin = value.toFloatOrNull() ?: return invalid(key, value)
            "yellowHMax" -> yellowHMax = value.toFloatOrNull() ?: return invalid(key, value)
            "yellowSMin" -> yellowSMin = value.toFloatOrNull() ?: return invalid(key, value)
            "yellowVMin" -> yellowVMin = value.toFloatOrNull() ?: return invalid(key, value)
            "greenHMin" -> greenHMin = value.toFloatOrNull() ?: return invalid(key, value)
            "greenHMax" -> greenHMax = value.toFloatOrNull() ?: return invalid(key, value)
            "greenSMin" -> greenSMin = value.toFloatOrNull() ?: return invalid(key, value)
            "greenVMin" -> greenVMin = value.toFloatOrNull() ?: return invalid(key, value)
            "castXPct" -> castXPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "castYPct" -> castYPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "leftArrowXPct" -> leftArrowXPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "leftArrowYPct" -> leftArrowYPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "rightArrowXPct" -> rightArrowXPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "rightArrowYPct" -> rightArrowYPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "arrowZoneYMinPct" -> arrowZoneYMinPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "arrowZoneYMaxPct" -> arrowZoneYMaxPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "leftZoneXMinPct" -> leftZoneXMinPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "leftZoneXMaxPct" -> leftZoneXMaxPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "rightZoneXMinPct" -> rightZoneXMinPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "rightZoneXMaxPct" -> rightZoneXMaxPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "arrowSaneLeftXMinPct" -> arrowSaneLeftXMinPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "arrowSaneLeftXMaxPct" -> arrowSaneLeftXMaxPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "arrowSaneRightXMinPct" -> arrowSaneRightXMinPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "arrowSaneRightXMaxPct" -> arrowSaneRightXMaxPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "arrowSaneMinGapPct" -> arrowSaneMinGapPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "arrowSaneYMinPct" -> arrowSaneYMinPct = value.toDoubleOrNull() ?: return invalid(key, value)
            "arrowSaneYMaxPct" -> arrowSaneYMaxPct = value.toDoubleOrNull() ?: return invalid(key, value)
            else -> { Log.w("Config", "bot_config.txt: key tak dikenal '$key'"); return false }
        }
        return true
    }

    private fun invalid(key: String, value: String): Boolean {
        Log.w("Config", "bot_config.txt: nilai tak valid utk '$key' = '$value', pakai default")
        return false
    }

    private fun defaultFileContents(): String = buildString {
        appendLine("# AutoAngler — tuning config.")
        appendLine("# Edit the values below, save, then on the phone: SVC OFF -> Start Overlay")
        appendLine("# Service again (or reopen the app) to apply — no new APK needed.")
        appendLine("# Lines starting with # are comments and are ignored. Format: key = value")
        appendLine()
        appendLine("# --- Arrow push pulse timing (ms) ---")
        appendLine("interPulseMs = $interPulseMs")
        appendLine("interPulseInZoneMs = $interPulseInZoneMs")
        appendLine("tapHoldMs = $tapHoldMs")
        appendLine("deadzonePx = $deadzonePx")
        appendLine("marginPx = $marginPx")
        appendLine("autoStopBarMissingMs = $autoStopBarMissingMs")
        appendLine()
        appendLine("# --- Stuck-cursor auto-stop detection ---")
        appendLine("stuckWindowMs = $stuckWindowMs")
        appendLine("stuckMinMovePx = $stuckMinMovePx")
        appendLine("stuckErrorThreshold = $stuckErrorThreshold")
        appendLine()
        appendLine("# --- Game loop timing (ms) ---")
        appendLine("loopMs = $loopMs")
        appendLine("castingTimeoutMs = $castingTimeoutMs")
        appendLine()
        appendLine("# --- Root shell watchdog (ms) ---")
        appendLine("shellWedgeTimeoutMs = $shellWedgeTimeoutMs")
        appendLine()
        appendLine("# --- HSV color thresholds (vision) ---")
        appendLine("yellowHMin = $yellowHMin")
        appendLine("yellowHMax = $yellowHMax")
        appendLine("yellowSMin = $yellowSMin")
        appendLine("yellowVMin = $yellowVMin")
        appendLine("greenHMin = $greenHMin")
        appendLine("greenHMax = $greenHMax")
        appendLine("greenSMin = $greenSMin")
        appendLine("greenVMin = $greenVMin")
        appendLine()
        appendLine("# --- Button position defaults, % of screen (0.0-1.0) ---")
        appendLine("castXPct = $castXPct")
        appendLine("castYPct = $castYPct")
        appendLine("leftArrowXPct = $leftArrowXPct")
        appendLine("leftArrowYPct = $leftArrowYPct")
        appendLine("rightArrowXPct = $rightArrowXPct")
        appendLine("rightArrowYPct = $rightArrowYPct")
        appendLine()
        appendLine("# --- Arrow search zone, % of screen (for auto-detection) ---")
        appendLine("arrowZoneYMinPct = $arrowZoneYMinPct")
        appendLine("arrowZoneYMaxPct = $arrowZoneYMaxPct")
        appendLine("leftZoneXMinPct = $leftZoneXMinPct")
        appendLine("leftZoneXMaxPct = $leftZoneXMaxPct")
        appendLine("rightZoneXMinPct = $rightZoneXMinPct")
        appendLine("rightZoneXMaxPct = $rightZoneXMaxPct")
        appendLine()
        appendLine("# --- Arrow coordinate sanity-check bounds, % of screen ---")
        appendLine("arrowSaneLeftXMinPct = $arrowSaneLeftXMinPct")
        appendLine("arrowSaneLeftXMaxPct = $arrowSaneLeftXMaxPct")
        appendLine("arrowSaneRightXMinPct = $arrowSaneRightXMinPct")
        appendLine("arrowSaneRightXMaxPct = $arrowSaneRightXMaxPct")
        appendLine("arrowSaneMinGapPct = $arrowSaneMinGapPct")
        appendLine("arrowSaneYMinPct = $arrowSaneYMinPct")
        appendLine("arrowSaneYMaxPct = $arrowSaneYMaxPct")
    }

    fun saveInjectorMode(context: Context, mode: tf.fog.autoangler.injector.InjectorMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("injector_mode", mode.name).apply()
    }

    fun loadInjectorMode(context: Context): tf.fog.autoangler.injector.InjectorMode {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("injector_mode", null)
        return try { tf.fog.autoangler.injector.InjectorMode.valueOf(name ?: "") }
        catch (_: Exception) { tf.fog.autoangler.injector.InjectorMode.ROOT }
    }

    fun saveCalibration(context: Context, cfg: CalibrationConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt("roi_l", cfg.roiRect.left)
            putInt("roi_t", cfg.roiRect.top)
            putInt("roi_r", cfg.roiRect.right)
            putInt("roi_b", cfg.roiRect.bottom)
            putInt("lb_x", cfg.leftBtnX)
            putInt("lb_y", cfg.leftBtnY)
            putInt("rb_x", cfg.rightBtnX)
            putInt("rb_y", cfg.rightBtnY)
            putBoolean("lb_moves_right", cfg.leftBtnMovesRight)
            putString("event_dev", cfg.eventDevice)
            putBoolean("calibrated", cfg.isCalibrated)
            apply()
        }
    }

    fun loadCalibration(context: Context): CalibrationConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CalibrationConfig(
            roiRect = Rect(
                p.getInt("roi_l", 0), p.getInt("roi_t", 0),
                p.getInt("roi_r", 0), p.getInt("roi_b", 0)
            ),
            leftBtnX = p.getInt("lb_x", 0),
            leftBtnY = p.getInt("lb_y", 0),
            rightBtnX = p.getInt("rb_x", 0),
            rightBtnY = p.getInt("rb_y", 0),
            leftBtnMovesRight = p.getBoolean("lb_moves_right", true),
            eventDevice = p.getString("event_dev", "/dev/input/event0") ?: "/dev/input/event0",
            isCalibrated = p.getBoolean("calibrated", false)
        )
    }
}
