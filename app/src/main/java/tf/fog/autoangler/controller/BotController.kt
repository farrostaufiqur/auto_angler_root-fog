package tf.fog.autoangler.controller

import tf.fog.autoangler.injector.TouchInjector
import tf.fog.autoangler.util.Config
import tf.fog.autoangler.util.FileLog as Log
import tf.fog.autoangler.vision.VisionResult
import kotlinx.coroutines.delay
import kotlin.math.abs

enum class BotState { IDLE, PUSH_LEFT, PUSH_RIGHT }

class BotController(
    private val injector: TouchInjector,
    private val leftArrowX: Int, private val leftArrowY: Int,
    private val rightArrowX: Int, private val rightArrowY: Int,
    private val leftMovesRight: Boolean = true
) {
    var state = BotState.IDLE
        private set

    private var barMissingStart = 0L
    var barMissingSec = 0f
        private set
    private var lastTapLogMs = 0L

    // Deteksi cursor "macet": tak bergerak walau status sedang menyuruh PUSH_LEFT/RIGHT.
    // Dicek per jendela waktu (bukan tiap tick) supaya tak kena noise vision/jitter tap.
    private var stuckWindowStartMs = 0L
    private var stuckWindowStartX = 0
    var stuckErrorCount = 0
        private set
    val isStuck get() = stuckErrorCount >= Config.stuckErrorThreshold

    private companion object { const val TAG = "BotController" }

    suspend fun tick(result: VisionResult): Boolean {
        if (!result.valid) {
            if (barMissingStart == 0L) barMissingStart = System.currentTimeMillis()
            barMissingSec = (System.currentTimeMillis() - barMissingStart) / 1000f
            state = BotState.IDLE
            return barMissingSec * 1000 > Config.autoStopBarMissingMs
        }
        barMissingStart = 0L; barMissingSec = 0f

        val center = (result.greenLeft + result.greenRight) / 2

        state = when {
            result.yellowX < result.greenLeft  -> BotState.PUSH_RIGHT
            result.yellowX > result.greenRight -> BotState.PUSH_LEFT
            abs(result.yellowX - center) <= Config.deadzonePx -> BotState.IDLE
            result.yellowX < center -> BotState.PUSH_RIGHT
            else                    -> BotState.PUSH_LEFT
        }

        if (state == BotState.IDLE) {
            // HANYA restart jendela waktu — stuckErrorCount SENGAJA TIDAK direset di sini.
            // Aturan: kesalahan reset HANYA saat cursor benar-benar bergerak, bukan sekadar
            // karena masuk deadzone/zone (masuk zone bukan bukti panah "hidup").
            stuckWindowStartMs = 0L
            return false
        }

        // Cek "macet": bandingkan posisi cursor di awal & akhir jendela STUCK_WINDOW_MS
        // selama status TERUS menyuruh push. Gerak 0px (tak bergerak sama sekali) → 1
        // kesalahan; reset ke 0 HANYA saat cursor benar-benar bergerak (bukan tempat lain).
        val nowMsStuck = System.currentTimeMillis()
        if (stuckWindowStartMs == 0L) {
            stuckWindowStartMs = nowMsStuck; stuckWindowStartX = result.yellowX
        } else if (nowMsStuck - stuckWindowStartMs >= Config.stuckWindowMs) {
            val moved = abs(result.yellowX - stuckWindowStartX)
            if (moved < Config.stuckMinMovePx) {
                stuckErrorCount++
                Log.w(TAG, "Cursor macet saat $state (gerak ${moved}px/${Config.stuckWindowMs}ms) — kesalahan #$stuckErrorCount/${Config.stuckErrorThreshold}")
            } else {
                stuckErrorCount = 0
            }
            stuckWindowStartMs = nowMsStuck; stuckWindowStartX = result.yellowX
        }

        // dist masih perlu dihitung utk menentukan luar/dalam zona (pilih delay di bawah) —
        // TAPI tak lagi dipakai utk hitung durasi tap proporsional (pulseBaseMs/pulseMaxMs/
        // jitterMs/finalPulse dihapus): `input tap` (mode Root) tak punya parameter durasi,
        // jadi seluruh komputasi itu cuma beban tanpa efek nyata.
        val dist = when (state) {
            BotState.PUSH_RIGHT -> (result.greenLeft - result.yellowX).coerceAtLeast(0)
            BotState.PUSH_LEFT  -> (result.yellowX - result.greenRight).coerceAtLeast(0)
            BotState.IDLE       -> 0
        }

        val (tx, ty) = when (state) {
            BotState.PUSH_RIGHT -> if (leftMovesRight) leftArrowX to leftArrowY  else rightArrowX to rightArrowY
            BotState.PUSH_LEFT  -> if (leftMovesRight) rightArrowX to rightArrowY else leftArrowX  to leftArrowY
            BotState.IDLE       -> return false
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastTapLogMs >= 1000) {
            lastTapLogMs = nowMs
            Log.d(TAG, "Arrow $state → ($tx,$ty)")
        }
        // Config.tapHoldMs: satu variabel, dipakai eksplisit di kedua mode. Efek nyata
        // cuma di Non-Root (Root/`input tap` tak punya parameter durasi, lihat Config.kt).
        injector.tap(tx, ty, Config.tapHoldMs)
        // Luar zona (dist>0): delay kecil → kejar zona cepat
        // Dalam zona (dist=0): delay besar → cegah command menumpuk + overshoot bolak-balik
        delay(if (dist > 0) Config.interPulseMs else Config.interPulseInZoneMs)
        return false
    }

    fun reset() {
        state = BotState.IDLE; barMissingStart = 0L; barMissingSec = 0f
        stuckWindowStartMs = 0L; stuckErrorCount = 0
    }
}
