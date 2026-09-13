package tf.fog.autoangler.util

import android.content.Context
import android.content.Intent
import tf.fog.autoangler.MainActivity
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger persisten ke file — pengganti drop-in [android.util.Log].
 * Dipakai lewat alias di tiap file:
 *     import tf.fog.autoangler.util.FileLog as Log
 *
 * Semua log ikut ditulis ke file (getExternalFilesDir/bot_log.txt) dan di-FLUSH tiap
 * baris (FileWriter.use → close → flush), jadi log tetap ada walau app force-close/crash.
 * Crash handler menulis stack trace FATAL sebelum proses mati.
 */
object FileLog {
    private const val FILE = "bot_log.txt"
    private const val MAX_BYTES = 3_000_000L   // batas ukuran file
    private const val TRIM_TO = 1_500_000       // sisa setelah dipangkas

    private val lock = Any()
    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null
    @Volatile private var crashHandlerInstalled = false
    @Volatile private var appContext: Context? = null
    private var writeCount = 0

    fun init(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            file = File(dir, FILE)
        }
        installCrashHandler()
        write("I", "FileLog", "==== sesi log dimulai ${sdf.format(Date())} ====")
    }

    fun path(): String = file?.absolutePath ?: "(not initialised)"

    // ── API drop-in android.util.Log ───────────────────────────────────────────
    fun d(tag: String, msg: String): Int { android.util.Log.d(tag, msg); write("D", tag, msg); return 0 }
    fun i(tag: String, msg: String): Int { android.util.Log.i(tag, msg); write("I", tag, msg); return 0 }
    fun w(tag: String, msg: String): Int { android.util.Log.w(tag, msg); write("W", tag, msg); return 0 }
    fun w(tag: String, msg: String, tr: Throwable): Int { android.util.Log.w(tag, msg, tr); write("W", tag, "$msg\n${stack(tr)}"); return 0 }
    fun e(tag: String, msg: String): Int { android.util.Log.e(tag, msg); write("E", tag, msg); return 0 }
    fun e(tag: String, msg: String, tr: Throwable): Int { android.util.Log.e(tag, msg, tr); write("E", tag, "$msg\n${stack(tr)}"); return 0 }
    fun v(tag: String, msg: String): Int { android.util.Log.v(tag, msg); write("V", tag, msg); return 0 }
    fun getStackTraceString(tr: Throwable): String = android.util.Log.getStackTraceString(tr)

    // ── Untuk halaman log ──────────────────────────────────────────────────────
    fun readAll(): String = synchronized(lock) {
        try {
            val txt = file?.takeIf { it.exists() }?.readText() ?: ""
            if (txt.isEmpty()) "(log kosong)" else txt
        } catch (e: Exception) { "Failed to read log: $e" }
    }

    fun clear() {
        synchronized(lock) { try { file?.writeText("") } catch (_: Exception) {} }
        write("I", "FileLog", "==== log dibersihkan ====")
    }

    // ── Internal ────────────────────────────────────────────────────────────────
    private fun stack(tr: Throwable) = android.util.Log.getStackTraceString(tr)

    private fun write(level: String, tag: String, msg: String) {
        val f = file ?: return
        synchronized(lock) {
            try {
                FileWriter(f, true).use {
                    it.append(sdf.format(Date())).append(' ')
                        .append(level).append('/').append(tag).append(": ")
                        .append(msg).append('\n')
                }
                if (++writeCount % 256 == 0 && f.length() > MAX_BYTES) trimLocked(f)
            } catch (_: Exception) {}
        }
    }

    private fun trimLocked(f: File) {
        try {
            val text = f.readText()
            val tail = if (text.length > TRIM_TO) text.substring(text.length - TRIM_TO) else text
            f.writeText("==== (log lama dipangkas) ====\n$tail")
        } catch (_: Exception) {}
    }

    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write("FATAL", "CRASH", "thread=${t.name}\n${android.util.Log.getStackTraceString(e)}") } catch (_: Exception) {}
            try { scheduleAutoRestart() } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    // Permudah restart: setelah force-close, buka ulang MainActivity otomatis (bukan
    // user harus cari ikon app manual). MainActivity mendeteksi extra AUTO_RESTART lalu
    // langsung start service kalau izin overlay sudah ada — jadi setelah crash tinggal
    // tap START di HUD + izin capture (izin capture TAK BISA dilewati, syarat keamanan
    // Android, harus persetujuan user tiap kali). Cooldown 10dtk cegah restart-loop
    // kalau app crash lagi segera setelah dibuka (bug lain yg belum kelihatan).
    private fun scheduleAutoRestart() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("filelog_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_auto_restart", 0L)
        if (now - last < 10_000L) return
        prefs.edit().putLong("last_auto_restart", now).apply()
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("AUTO_RESTART", true)
        ctx.startActivity(intent)
    }
}
