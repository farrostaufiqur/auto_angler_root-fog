package tf.fog.autoangler.overlay

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import tf.fog.autoangler.util.FileLog as Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import tf.fog.autoangler.MediaProjectionActivity
import tf.fog.autoangler.capture.CaptureManager
import tf.fog.autoangler.controller.BotState
import tf.fog.autoangler.game.AutoCalibrator
import tf.fog.autoangler.game.ButtonLayout
import tf.fog.autoangler.game.FullGameController
import tf.fog.autoangler.game.GameState
import tf.fog.autoangler.injector.RootInjector
import tf.fog.autoangler.injector.TouchInjector
import tf.fog.autoangler.util.Config
import tf.fog.autoangler.util.License
import tf.fog.autoangler.vision.VisionResult
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "fishing_bot_channel"
        const val NOTIF_ID = 1001
        private const val TAG = "OverlayService"
        var instance: OverlayService? = null
    }

    private lateinit var windowManager: WindowManager
    private var hudView: HudView? = null

    private val capture = CaptureManager()
    private val rootInjector = RootInjector()
    // Varian ROOT-ONLY: satu-satunya injector, tak ada pemilihan mode.
    private val activeInjector: TouchInjector get() = rootInjector

    private var gameController: FullGameController? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gameJob: Job? = null
    private var trialJob: Job? = null
    private var currentFps = 0f
    private var lastVision = VisionResult()
    private var lastGameState = GameState.IDLE
    private var lastBotState = BotState.IDLE
    private val stopTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // Muat ulang bot_config.txt tiap kali service (re)start — titik restart alami
        // (SVC OFF -> Start Overlay Service lagi) supaya perubahan tuning manual di file
        // langsung dipakai tanpa perlu APK baru.
        Config.loadFromFile(this)

        createNotificationChannel()
        startForegroundSafe(includeMediaProjection = false)
        showHud()
        Log.d(TAG, "Service created (ROOT-ONLY)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // Dipanggil LANGSUNG dari MediaProjectionActivity.onActivityResult
    // (activity masih hidup saat dipanggil → Android 14 happy)
    fun onProjectionGranted(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Projection denied (resultCode=$resultCode)")
            return
        }
        // Post ke main thread untuk pastikan urutan yang benar
        Handler(Looper.getMainLooper()).post { startCapture(resultCode, data) }
    }

    // ── Foreground ────────────────────────────────────────────────────────────

    // Dibungkus try/catch: log crash sebelumnya menunjukkan exception dari sisi sistem
    // (ActiveServices.setServiceForegroundInnerLocked) yang TAK TERTANGKAP → force-close
    // seluruh app. startForeground() bisa menolak (mis. rate-limit OS, tipe tak valid di
    // state tertentu) — kalau itu terjadi, JANGAN crash, cukup log+return false, caller
    // yang putuskan langkah selanjutnya (gagal mulai capture, bukan mati total).
    private fun startForegroundSafe(includeMediaProjection: Boolean): Boolean {
        return try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (includeMediaProjection)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIF_ID, notif, type)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground gagal (mediaProjection=$includeMediaProjection)", e)
            false
        }
    }

    // ── HUD ──────────────────────────────────────────────────────────────────

    private fun showHud() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted"); stopSelf(); return
        }
        hudView = HudView(
            context = this,
            onStart = { requestStart() },
            onStop = { stopGame() },
            onStopService = { stopServiceFully() }
        )
        hudView?.setMode("ROOT")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = 16; y = 64 }
        windowManager.addView(hudView, params)
    }

    // ── Start flow ────────────────────────────────────────────────────────────

    private fun requestStart() {
        // ROOT-ONLY build: check root on every START. If it fails, START is greyed out —
        // there is no other mode to fall back to.
        hudView?.showToast("Checking root access…")
        serviceScope.launch {
            val ok = RootInjector.isRootAvailable()
            runOnMainThread {
                hudView?.setStartEnabled(ok)
                if (ok) {
                    launchProjection()
                } else {
                    hudView?.showToast("Device is not rooted — this build can't run. Use the regular AutoAngler build (it has a Non-Root mode).")
                    Log.w(TAG, "Root unavailable - START disabled")
                }
            }
        }
    }

    private fun launchProjection() {
        startActivity(Intent(this, MediaProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // Upgrade foreground service type SEBELUM getMediaProjection (Android 14 requirement)
            startForegroundSafe(includeMediaProjection = true)
            val projection: MediaProjection = mpManager.getMediaProjection(resultCode, data)

            val (w, h, dpi) = getScreenDimensions()
            capture.init(projection, w, h, dpi)
            val layout = AutoCalibrator.calibrate(w, h)
            Log.d(TAG, "Capture init ${w}x${h}, layout=$layout")

            serviceScope.launch {
                rootInjector.eventDevice = Config.loadCalibration(this@OverlayService).eventDevice
                val ok = rootInjector.start()
                if (!ok) {
                    hudView?.showToast("Failed to start the root daemon — grant SU")
                    hudView?.setStartEnabled(false)
                    return@launch
                }
                startGameLoop(layout)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startCapture failed", e)
            hudView?.showToast("Failed to start capture: ${e.message}")
            startForegroundSafe(includeMediaProjection = false)
        }
    }

    // ── Game loop ─────────────────────────────────────────────────────────────

    private fun startGameLoop(layout: ButtonLayout) {
        if (gameJob?.isActive == true) return
        hudView?.setRunning(true)

        gameController = FullGameController(
            injector = activeInjector,
            capture = capture,
            layout = layout,
            onStateChange = { gs, bs, vr ->
                lastGameState = gs; lastBotState = bs; lastVision = vr
                hudView?.update(gs, bs, currentFps, vr)
            },
            onFpsUpdate = { fps -> currentFps = fps },
            onLayoutUpdated = { Log.d(TAG, "Layout updated: $it") },
            onAutoStop = { reason -> stopGame(reason) }
        )

        gameJob = serviceScope.launch {
            gameController?.run()
        }

        // Trial mode (not registered): fishing stops automatically after 3 minutes.
        // Tap START again for the next 3-minute session. A registration code removes the limit.
        trialJob?.cancel(); trialJob = null
        if (!License.isRegistered(this)) {
            trialJob = serviceScope.launch {
                delay(License.TRIAL_MS)
                runOnMainThread {
                    hudView?.showToast("Trial ended (3 min) — enter a registration code to continue without limit")
                    stopGame("Trial ended (3 min)")
                }
            }
        }
    }

    private fun stopGame(reason: String = "Stopped") {
        trialJob?.cancel(); trialJob = null
        gameJob?.cancel(); gameJob = null
        gameController?.reset(); gameController = null
        // Hentikan screen capture SUNGGUHAN (bukan cuma loop bot) — sebelumnya STOP hanya
        // membatalkan game loop, MediaProjection tetap aktif → OS masih menampilkan
        // indikator "merekam layar" walau bot sudah berhenti.
        capture.release()
        startForegroundSafe(includeMediaProjection = false)
        hudView?.setRunning(false)
        val time = stopTimeFormat.format(Date())
        hudView?.setStoppedStatus("$reason ($time)")
        Log.d(TAG, "Game stopped: $reason at $time")
    }

    private fun stopServiceFully() {
        stopGame()
        rootInjector.stop()
        capture.release()
        Log.d(TAG, "Stop service")
        stopSelf()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // PENTING: currentWindowMetrics (context Service) pernah kebukti mengembalikan dimensi
    // PORTRAIT (1080x2400) padahal game target landscape (2400x1080), saat MainActivity sempat
    // dikunci portrait — app yg sama proses jadi konflik orientasi dgn game. Sekarang aman
    // krn MainActivity SUDAH DIKEMBALIKAN ke landscape (sama dgn game, tak ada konflik lagi).
    // Kalau MainActivity diubah orientasinya lagi (bukan landscape), masalah ini BISA KAMBUH —
    // cek dulu log "CaptureManager: init WxH" cocok dgn resolusi asli game sebelum curiga hal lain.
    private fun getScreenDimensions(): Triple<Int, Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), resources.displayMetrics.densityDpi)
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun runOnMainThread(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "AutoAngler Service", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoAngler").setContentText("Bot active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()

    override fun onDestroy() {
        stopGame(); rootInjector.stop(); capture.release(); serviceScope.cancel()
        hudView?.let { runOnMainThread { windowManager.removeView(it) } }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
