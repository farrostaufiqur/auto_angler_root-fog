package tf.fog.autoangler.injector

import tf.fog.autoangler.util.Config
import tf.fog.autoangler.util.FileLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

class RootInjector : TouchInjector {
    private var process: Process? = null
    private var stdin: DataOutputStream? = null
    @Volatile private var _isActive = false
    override val modeName = "Root"
    override val isActive get() = _isActive

    var eventDevice: String = "/dev/input/event0"

    // Health-check: deteksi shell "wedged" — write sukses tapi `input` tak jalan
    // (mis. app_process/zygote hang → shell stop baca stdin → writeBytes nge-block
    //  loop selamanya, _isActive tetap true, tak ada yang restart). PONG round-trip
    // membuktikan command BENAR-BENAR dieksekusi, bukan cuma write sukses.
    @Volatile private var lastPongMs = 0L
    @Volatile private var lastWriteMs = 0L
    private var lastRestartMs = 0L
    private var lastFallbackMs = 0L
    @Volatile private var watchdogRunning = false

    companion object {
        private const val TAG = "RootInjector"
        private const val PONG = "__PG__"

        /**
         * Cek cepat apakah perangkat benar-benar punya akses root: biner `su` ada DAN
         * izin SU untuk app ini diberikan. Blocking maksimal ~2 detik — panggil dari
         * background thread, JANGAN dari main thread. Dipakai saat user tekan START di
         * mode Root; kalau hasilnya false, OverlayService menawarkan pindah ke Non-Root.
         */
        fun isRootAvailable(): Boolean = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            if (p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.exitValue() == 0
            } else {
                p.destroy(); false
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            stop()
            spawnShell()
            startWatchdog()
            Log.d(TAG, "Root daemon started, device=$eventDevice")
            _isActive
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start root daemon", e)
            _isActive = false
            false
        }
    }

    // Buat proses su + drain threads (stdout cek PONG) + handshake.
    // Dipakai start() dan jalur restart, supaya semua jalur konsisten.
    private fun spawnShell() {
        val p = Runtime.getRuntime().exec("su")
        process = p
        stdin = DataOutputStream(p.outputStream)

        // Drain stdout + deteksi PONG. PONG = bukti shell selesai eksekusi command.
        // Tanpa drain, buffer 64KB penuh → shell block saat nulis → taps berhenti.
        Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.contains(PONG)) lastPongMs = System.currentTimeMillis()
                }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true }.start()
        Thread {
            try { p.errorStream.bufferedReader().forEachLine { } } catch (_: Exception) {}
        }.also { it.isDaemon = true }.start()

        val now = System.currentTimeMillis()
        lastPongMs = now; lastWriteMs = 0L
        stdin!!.writeBytes("echo $PONG\n")
        stdin!!.flush()
        Thread.sleep(250)
        _isActive = true
    }

    // Watchdog independen di thread sendiri: kalau ADA tap baru-baru ini tapi PONG
    // basi > WEDGE_TIMEOUT_MS → shell wedged. destroy() membuka blokir writeBytes
    // yang membekukan loop → loop lempar IOException → pulih lewat jalur auto-restart.
    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        Thread {
            while (watchdogRunning) {
                try {
                    Thread.sleep(400)
                    if (!_isActive) continue
                    val now = System.currentTimeMillis()
                    // "outstanding" = tulis terakhir BELUM di-ack PONG. Kalau semua tap
                    // sudah ber-PONG (lastPongMs >= lastWriteMs), shell SEHAT walau lama
                    // tak nge-tap (cursor diam di deadzone / game di-stop). Dulu pakai
                    // `wroteRecently` → tiap jeda tap >1.5d dikira wedged (false positive)
                    // lalu shell di-restart sia-sia.
                    val outstanding = lastWriteMs > lastPongMs
                    val writeStale = now - lastWriteMs > Config.shellWedgeTimeoutMs
                    if (outstanding && writeStale) {
                        Log.e(TAG, "Shell WEDGED: tap tak di-ack ${now - lastWriteMs}ms → force restart")
                        forceRestart()
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }.start()
    }

    // Bunuh proses → membuka blokir writeBytes di loop → loop pulih.
    // Tandai mati + reset rate-limit supaya tap() berikutnya langsung respawn.
    private fun forceRestart() {
        _isActive = false
        try { stdin?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        lastRestartMs = 0L; lastFallbackMs = 0L
    }

    override fun tap(x: Int, y: Int, holdMs: Long) {
        if (!_isActive) {
            val now = System.currentTimeMillis()
            // Rate-limit fallback: max 2/detik agar tidak spam proses su
            if (now - lastFallbackMs >= 500) {
                lastFallbackMs = now
                tapFallback(x, y)
            }
            // Auto-restart daemon (≤3 detik) jika mati
            if (now - lastRestartMs >= 3000) {
                lastRestartMs = now
                Thread {
                    try { spawnShell(); Log.d(TAG, "Root daemon auto-restarted") }
                    catch (e: Exception) { Log.e(TAG, "Auto-restart failed: $e") }
                }.also { it.isDaemon = true }.start()
            }
            return
        }
        try {
            lastWriteMs = System.currentTimeMillis()
            // `input tap` lalu `echo PONG`: PONG baru keluar SETELAH input selesai,
            // jadi PONG segar = bukti input benar-benar dieksekusi (bukan sekadar write OK).
            stdin!!.writeBytes("input tap $x $y\necho $PONG\n")
            stdin!!.flush()
        } catch (e: Exception) {
            Log.e(TAG, "tap($x,$y) error: $e")
            _isActive = false
        }
    }

    private fun tapFallback(x: Int, y: Int) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
        } catch (e: Exception) {
            Log.e(TAG, "fallback tap error", e)
        }
    }

    fun stop() {
        watchdogRunning = false
        try {
            stdin?.writeBytes("exit\n"); stdin?.flush(); stdin?.close()
        } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        stdin = null; process = null; _isActive = false
    }
}
