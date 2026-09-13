package tf.fog.autoangler.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import android.view.*
import android.widget.*
import tf.fog.autoangler.LogActivity
import tf.fog.autoangler.MainActivity
import tf.fog.autoangler.controller.BotState
import tf.fog.autoangler.game.GameState
import tf.fog.autoangler.vision.VisionResult

@SuppressLint("ViewConstructor")
class HudView(
    context: Context,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onStopService: () -> Unit
) : LinearLayout(context) {

    private val tvState: TextView
    private val tvMode: TextView
    private val tvBar: TextView
    private val tvFps: TextView
    private val btnStart: Button
    private val btnStop: Button
    private val btnStartMini: Button
    private val btnStopMini: Button
    private val fullPanel: LinearLayout
    private val miniPanel: LinearLayout

    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f

    // Varian ROOT-ONLY: START bisa di-grayout kalau root tak tersedia (lihat setStartEnabled).
    private var startAllowed = true
    private var running = false

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setBackgroundColor(Color.parseColor("#CC1A1A2E"))

        // Drag handle: SELALU tampil (baik full maupun minimize) — ini wilayah sentuh
        // tetap utk pindahkan overlay walau lagi minimize.
        val dragHandle = TextView(context).apply {
            text = "≡ AutoAngler"; setTextColor(Color.WHITE); textSize = 13f
            setPadding(0, 0, 0, dp(4))
        }
        addView(dragHandle)

        // Status: SELALU tampil (baik full maupun minimize)
        tvState = TextView(context).apply { text = "IDLE"; setTextColor(Color.GRAY); textSize = 12f }
        addView(tvState)

        // ── Panel penuh: mode (read-only), fps/bar, baris1 start/stop, baris2 log/svc off,
        // baris3 open app/minimize. Disembunyikan seluruhnya saat minimize. ────────────
        fullPanel = LinearLayout(context).apply { orientation = VERTICAL }
        addView(fullPanel)

        // Mode kontrol tap — hanya PENANDA (read-only). Pilihan mode dipindah ke layar
        // utama APK (MainActivity); HUD tidak lagi bisa mengganti mode.
        tvMode = TextView(context).apply { text = "mode: -"; setTextColor(Color.LTGRAY); textSize = 10f }
        fullPanel.addView(tvMode)

        tvFps = TextView(context).apply { text = "FPS: -"; setTextColor(Color.LTGRAY); textSize = 10f }
        fullPanel.addView(tvFps)

        tvBar = TextView(context).apply { text = "bar: -"; setTextColor(Color.LTGRAY); textSize = 10f }
        fullPanel.addView(tvBar)

        // Baris 1: START, STOP
        val row1 = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        btnStart = makeBtn("START", Color.parseColor("#FF00C853")) { onStart() }
        btnStop  = makeBtn("STOP",  Color.parseColor("#FFFFA000")) { onStop() }
        row1.addView(btnStart); row1.addView(btnStop)
        fullPanel.addView(row1)

        // Baris 2: LOG, SVC OFF
        val row2 = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        val btnLog = makeBtn("LOG", Color.parseColor("#FF6A1B9A")) {
            context.startActivity(Intent(context, LogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val btnStopSvc = makeBtn("SVC OFF", Color.parseColor("#FFD50000")) { onStopService() }
        row2.addView(btnLog); row2.addView(btnStopSvc)
        fullPanel.addView(row2)

        // Baris 3: OPEN APP, MINIMIZE
        val row3 = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        val btnOpenApp = makeBtn("OPEN APP", Color.parseColor("#FF37474F")) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val btnMinimize = makeBtn("MINIMIZE", Color.parseColor("#FF455A64")) { setMinimized(true) }
        row3.addView(btnOpenApp); row3.addView(btnMinimize)
        fullPanel.addView(row3)

        // ── Panel minimize: MAXIMIZE, START, STOP saja (+ dragHandle & tvState di atas
        // tetap tampil). Disembunyikan saat full. ─────────────────────────────────────
        miniPanel = LinearLayout(context).apply {
            orientation = HORIZONTAL; setPadding(0, dp(4), 0, 0); visibility = GONE
        }
        val btnMaximize = makeBtn("MAXIMIZE", Color.parseColor("#FF455A64")) { setMinimized(false) }
        btnStartMini = makeBtn("START", Color.parseColor("#FF00C853")) { onStart() }
        btnStopMini  = makeBtn("STOP",  Color.parseColor("#FFFFA000")) { onStop() }
        miniPanel.addView(btnMaximize); miniPanel.addView(btnStartMini); miniPanel.addView(btnStopMini)
        addView(miniPanel)

        // Drag support
        var wlp: WindowManager.LayoutParams? = null
        dragHandle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    wlp = layoutParams as? WindowManager.LayoutParams
                    initialX = wlp?.x ?: 0; initialY = wlp?.y ?: 0
                    initialTouchX = ev.rawX; initialTouchY = ev.rawY
                }
                MotionEvent.ACTION_MOVE -> wlp?.let {
                    it.x = initialX + (ev.rawX - initialTouchX).toInt()
                    it.y = initialY + (ev.rawY - initialTouchY).toInt()
                    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        .updateViewLayout(this, it)
                }
            }
            true
        }
    }

    private fun setMinimized(minimized: Boolean) {
        fullPanel.visibility = if (minimized) GONE else VISIBLE
        miniPanel.visibility = if (minimized) VISIBLE else GONE
    }

    // Varian ROOT-ONLY: mode selalu ROOT, ini penanda saja.
    fun setMode(modeName: String) {
        post { tvMode.text = "mode: $modeName (rooted build)" }
    }

    // Grayout / aktifkan START. Dipanggil OverlayService setelah cek root.
    fun setStartEnabled(enabled: Boolean) {
        startAllowed = enabled
        post { applyBtnState() }
    }

    private fun applyBtnState() {
        val canStart = startAllowed && !running
        btnStart.isEnabled = canStart; btnStart.alpha = if (canStart) 1f else 0.4f
        btnStartMini.isEnabled = canStart; btnStartMini.alpha = if (canStart) 1f else 0.4f
        btnStop.isEnabled = running; btnStop.alpha = if (running) 1f else 0.4f
        btnStopMini.isEnabled = running; btnStopMini.alpha = if (running) 1f else 0.4f
    }

    fun update(gameState: GameState, botState: BotState, fps: Float, vr: VisionResult) {
        post {
            val (color, label) = when (gameState) {
                GameState.IDLE    -> Color.GRAY                    to "IDLE"
                GameState.CASTING -> Color.parseColor("#FF42A5F5") to "CASTING..."
                GameState.HOOKED  -> Color.parseColor("#FFFF7043") to "HOOKED!"
                GameState.FISHING -> when (botState) {
                    BotState.PUSH_LEFT  -> Color.parseColor("#FFFFAB40") to "<<< LEFT"
                    BotState.PUSH_RIGHT -> Color.parseColor("#FFFFAB40") to "RIGHT >>>"
                    BotState.IDLE       -> Color.parseColor("#FF00E676") to "FISHING OK"
                }
                GameState.RESULT  -> Color.parseColor("#FFAB47BC") to "RESULT!"
            }
            tvState.setTextColor(color); tvState.text = label
            tvFps.text = "FPS: ${"%.1f".format(fps)}"
            tvBar.text = if (vr.valid) "Y=${vr.yellowX} G=[${vr.greenLeft}..${vr.greenRight}]"
                         else "bar: -"
        }
    }

    fun setRunning(running: Boolean) {
        this.running = running
        post { applyBtnState() }
    }

    // Tampilkan status berhenti + jam berhenti (mis. "STOPPED (14:23:05)"). Perlu di-set
    // eksplisit karena setelah loop game berhenti, update() tak lagi dipanggil sehingga
    // label akan diam di state terakhir kalau tak ditimpa.
    fun setStoppedStatus(text: String) {
        post { tvState.setTextColor(Color.parseColor("#FFFF5252")); tvState.text = text }
    }

    fun showToast(msg: String) {
        post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }

    private fun makeBtn(label: String, color: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label; textSize = 9f; setTextColor(Color.WHITE)
            setBackgroundColor(color); setOnClickListener { onClick() }
            layoutParams = LayoutParams(dp(58), dp(30)).also { it.setMargins(2, 0, 2, 0) }
        }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
