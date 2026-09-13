package tf.fog.autoangler.game

import android.graphics.Bitmap
import android.graphics.Rect
import tf.fog.autoangler.util.FileLog as Log
import tf.fog.autoangler.capture.CaptureManager
import tf.fog.autoangler.controller.BotController
import tf.fog.autoangler.controller.BotState
import tf.fog.autoangler.injector.TouchInjector
import tf.fog.autoangler.util.Config
import tf.fog.autoangler.vision.VisionProcessor
import tf.fog.autoangler.vision.VisionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class FullGameController(
    private val injector: TouchInjector,
    private val capture: CaptureManager,
    private var layout: ButtonLayout,
    private val onStateChange: (GameState, BotState, VisionResult) -> Unit,
    private val onFpsUpdate: (Float) -> Unit,
    private val onLayoutUpdated: (ButtonLayout) -> Unit,
    private val onAutoStop: (String) -> Unit = {}
) {
    private var currentState = GameState.IDLE
    private var debounceCount = 0
    private var pendingState = GameState.IDLE
    private var roiFound = false
    private var arrowsDetected = false
    private var lastArrowTryMs = 0L
    private var lastArrowFailLogMs = 0L
    // Sighting tombol kiri & kanan disimpan terpisah; tombol kanan hampir selalu
    // kebaca, kiri kadang tidak → gabung lintas frame biar lock lebih cepat (bukan
    // nunggu 1 frame yang kebetulan dua-duanya kebaca sekaligus).
    private var seenLeft: android.graphics.Point? = null
    private var seenLeftMs = 0L
    private var seenRight: android.graphics.Point? = null
    private var seenRightMs = 0L
    private var castingStartMs = 0L

    private var barController: BotController? = null
    private var frameCount = 0L
    private var fpsStart = System.currentTimeMillis()

    private companion object { const val TAG = "FullGameController" }

    suspend fun run() {
        Log.d(TAG, "Loop start ${layout.screenW}x${layout.screenH}")

        while (coroutineContext.isActive) {
            val fullFrame = capture.acquireFullFrame()
            if (fullFrame == null) { delay(Config.loopMs); continue }

            // ── Vision: cari fishing bar di ROI ──────────────────────────────
            val roi = if (roiFound)
                Rect(0, layout.roiTop, layout.screenW, layout.roiBottom)
            else
                Rect(0, 0, layout.screenW, (layout.screenH * 0.20).toInt())

            val roiBmp = crop(fullFrame, roi)
            val vision = if (roiBmp != null) VisionProcessor.process(roiBmp) else VisionResult()
            roiBmp?.recycle()

            // ── Deteksi state ─────────────────────────────────────────────────
            var rawState = if (vision.valid) GameState.FISHING
                           else StateDetector.detect(fullFrame)

            // Fallback CASTING→FISHING: cek tombol << >> (cluster piksel putih) di layar
            if (rawState == GameState.IDLE && currentState == GameState.CASTING) {
                val (l, r) = ButtonFinder.findArrowButtons(fullFrame)
                if (l != null && r != null) {
                    rawState = GameState.FISHING
                    Log.d(TAG, "FISHING via arrow fallback: L=$l R=$r")
                }
                if (!arrowsDetected) recordArrowSighting(l, r)
            }

            // Auto-detect ROI saat FISHING pertama kali
            if (rawState == GameState.FISHING && !roiFound) {
                val found = AutoCalibrator.findFishingBarRoi(fullFrame, layout.screenW, layout.screenH)
                if (found != null) {
                    layout = layout.copy(roiTop = found.top, roiBottom = found.bottom)
                    roiFound = true
                    onLayoutUpdated(layout)
                    Log.d(TAG, "ROI: top=${found.top} bottom=${found.bottom}")
                }
            }

            // ── Debounce ─────────────────────────────────────────────────────
            // FISHING: transisi langsung (perlu respons cepat)
            // RESULT: 2 frame (hindari false positive)
            // lainnya: 3 frame
            val debounceThreshold = when (rawState) {
                GameState.FISHING -> 1
                GameState.RESULT  -> 2
                else              -> 3
            }
            if (rawState == GameState.FISHING) {
                debounceCount = 0; pendingState = rawState
            } else {
                if (rawState == pendingState) debounceCount++
                else { pendingState = rawState; debounceCount = 1 }
            }
            val confirmedState = if (rawState == GameState.FISHING || debounceCount >= debounceThreshold)
                rawState else currentState

            // ── State transition ──────────────────────────────────────────────
            if (confirmedState != currentState) {
                Log.d(TAG, "State: $currentState → $confirmedState")
                if (confirmedState != GameState.FISHING) {
                    barController?.reset(); barController = null
                }
                currentState = confirmedState
            }

            // Kunci koordinat arrow via DETEKSI PIKSEL PUTIH — SEKALI per run. Tombol << >>
            // posisinya TETAP; default (AutoCalibrator) sudah presisi, deteksi di sini hanya
            // menghaluskan ke posisi piksel persis kalau berhasil. Kalau gagal, DEFAULT tetap
            // dipakai (aman). KRITIS: jalur HOOKED→FISHING men-set currentState=FISHING
            // langsung → deteksi di sini menjamin jalur itu pun tercoba. Rate-limit scan biar
            // tak berat/banjir log (kiri sering tak kebaca dlm 1 frame → lihat recordArrowSighting).
            if (currentState == GameState.FISHING && !arrowsDetected) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastArrowTryMs >= 250) {
                    lastArrowTryMs = nowMs
                    val (l, r) = ButtonFinder.findArrowButtons(fullFrame)
                    recordArrowSighting(l, r)
                }
            }

            fullFrame.recycle()

            val botState = barController?.state ?: BotState.IDLE
            onStateChange(currentState, botState, vision)
            updateFps()

            // ── State machine ─────────────────────────────────────────────────
            when (currentState) {

                GameState.IDLE -> {
                    delay(300)
                    Log.d(TAG, "Cast: (${layout.castX},${layout.castY})")
                    injector.tap(layout.castX, layout.castY, Config.tapHoldMs)
                    delay(300)
                    currentState = GameState.CASTING
                    castingStartMs = System.currentTimeMillis()
                    debounceCount = 0; pendingState = GameState.CASTING
                }

                GameState.CASTING -> {
                    val elapsed = System.currentTimeMillis() - castingStartMs
                    if (elapsed > Config.castingTimeoutMs) {
                        Log.w(TAG, "Cast timeout → IDLE")
                        currentState = GameState.IDLE
                    }
                    delay(Config.loopMs)
                }

                GameState.HOOKED -> {
                    delay(200)
                    Log.d(TAG, "Reel: (${layout.reelX},${layout.reelY})")
                    injector.tap(layout.reelX, layout.reelY, Config.tapHoldMs)
                    delay(400)
                    // Setelah reel ditekan, fishing mini-game akan mulai
                    currentState = GameState.FISHING
                    // arrowsDetected SENGAJA tidak di-reset: koord arrow tetap sepanjang
                    // run, deteksi sekali cukup. Reset di sini dulu bikin scan+log banjir
                    // tiap ikan. roiFound di-reset karena posisi bar bisa beda tiap cast.
                    roiFound = false
                    debounceCount = 0; pendingState = GameState.FISHING
                }

                GameState.FISHING -> {
                    if (barController == null) {
                        Log.d(TAG, "BotController arrow coords: L=(${layout.leftArrowX},${layout.leftArrowY}) R=(${layout.rightArrowX},${layout.rightArrowY}) detected=$arrowsDetected")
                        barController = BotController(
                            injector = injector,
                            leftArrowX  = layout.leftArrowX,
                            leftArrowY  = layout.leftArrowY,
                            rightArrowX = layout.rightArrowX,
                            rightArrowY = layout.rightArrowY,
                            leftMovesRight = layout.leftArrowMovesRight
                        )
                    }
                    val done = barController!!.tick(vision)
                    if (barController?.isStuck == true) {
                        // Cursor tak bergerak walau terus di-tap ke arah yang benar (mis. arrow
                        // tak lagi merespons) — hentikan seluruh run, bukan cuma reset bar,
                        // supaya user tahu ada masalah nyata (bukan sekadar ikan lepas).
                        Log.e(TAG, "Auto-stop: cursor macet ${barController?.stuckErrorCount} kali berturut-turut")
                        onAutoStop("Auto-stopped: cursor stuck")
                        return
                    }
                    if (done) {
                        // Bar hilang terlalu lama (3 detik) → ikan dapat/lepas
                        // StateDetector akan mendeteksi RESULT jika ada layar hasil
                        Log.d(TAG, "Bar done → tunggu state berikutnya")
                        barController?.reset(); barController = null
                        // Paksa deteksi ulang di frame berikutnya
                        debounceCount = 0
                    }
                }

                GameState.RESULT -> {
                    delay(400)
                    // Tap di tengah layar untuk tutup layar hasil
                    val tx = layout.screenW / 2
                    val ty = layout.screenH / 2
                    Log.d(TAG, "Result: tap ($tx,$ty)")
                    injector.tap(tx, ty, Config.tapHoldMs)
                    delay(600)
                    currentState = GameState.IDLE
                    debounceCount = 0; pendingState = GameState.IDLE
                }
            }

            if (currentState != GameState.FISHING) delay(Config.loopMs)
        }
        Log.d(TAG, "Loop ended")
    }

    // Gabung sighting kiri/kanan lintas frame (masing2 kadaluarsa 4 detik) lalu kunci
    // kalau dua-duanya ada & lolos sanity check. Dipanggil dari 2 titik (fallback CASTING
    // dan lock FISHING) supaya sanity check konsisten di semua jalur — sebelumnya jalur
    // fallback langsung menimpa koordinat tanpa validasi, berisiko rusak oleh noise putih.
    private fun recordArrowSighting(l: android.graphics.Point?, r: android.graphics.Point?) {
        if (arrowsDetected) return
        val nowMs = System.currentTimeMillis()
        if (l != null) { seenLeft = l; seenLeftMs = nowMs }
        if (r != null) { seenRight = r; seenRightMs = nowMs }
        val lv = if (nowMs - seenLeftMs < 4000) seenLeft else null
        val rv = if (nowMs - seenRightMs < 4000) seenRight else null
        if (lv != null && rv != null && arrowCoordsSane(lv, rv)) {
            layout = layout.copy(leftArrowX = lv.x, leftArrowY = lv.y,
                                 rightArrowX = rv.x, rightArrowY = rv.y)
            arrowsDetected = true
            barController = null   // buat ulang dgn koordinat baru
            onLayoutUpdated(layout)
            Log.d(TAG, "Panah terkunci via deteksi putih: L=$lv R=$rv")
        } else if (nowMs - lastArrowFailLogMs >= 2000) {
            lastArrowFailLogMs = nowMs
            Log.w(TAG, "Arrow belum lengkap/valid: seenL=$lv seenR=$rv → pakai default " +
                "(${layout.leftArrowX},${layout.leftArrowY})/(${layout.rightArrowX},${layout.rightArrowY})")
        }
    }

    // Tolak deteksi arrow yang ngawur (noise putih) supaya tak merusak koord bagus.
    // Longgar: hanya jaring nilai yang jelas mustahil untuk tombol << / >>.
    private fun arrowCoordsSane(l: android.graphics.Point, r: android.graphics.Point): Boolean {
        val w = layout.screenW; val h = layout.screenH
        return l.x in (w * Config.arrowSaneLeftXMinPct).toInt()..(w * Config.arrowSaneLeftXMaxPct).toInt() &&
               r.x in (w * Config.arrowSaneRightXMinPct).toInt()..(w * Config.arrowSaneRightXMaxPct).toInt() &&
               (r.x - l.x) > (w * Config.arrowSaneMinGapPct).toInt() &&
               l.y in (h * Config.arrowSaneYMinPct).toInt()..(h * Config.arrowSaneYMaxPct).toInt() &&
               r.y in (h * Config.arrowSaneYMinPct).toInt()..(h * Config.arrowSaneYMaxPct).toInt()
    }

    private fun crop(full: Bitmap, roi: Rect): Bitmap? = try {
        val l = roi.left.coerceIn(0, full.width - 1)
        val t = roi.top.coerceIn(0, full.height - 1)
        val w = roi.width().coerceIn(1, full.width - l)
        val h = roi.height().coerceIn(1, full.height - t)
        Bitmap.createBitmap(full, l, t, w, h)
    } catch (_: Exception) { null }

    private fun updateFps() {
        frameCount++
        val elapsed = System.currentTimeMillis() - fpsStart
        if (elapsed >= 1000L) {
            onFpsUpdate(frameCount * 1000f / elapsed)
            frameCount = 0; fpsStart = System.currentTimeMillis()
        }
    }

    fun reset() {
        currentState = GameState.IDLE
        barController?.reset(); barController = null
        debounceCount = 0; pendingState = GameState.IDLE
        roiFound = false; arrowsDetected = false
        seenLeft = null; seenRight = null
    }
}
