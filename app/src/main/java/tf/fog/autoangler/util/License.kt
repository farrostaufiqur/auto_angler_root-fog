package tf.fog.autoangler.util

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Web-verified registration (replaces the old hard-coded code list).
 *
 * Without registration the app runs in TRIAL mode: the bot stops automatically 3 minutes
 * after fishing starts. Enter a registration code on the main screen; the app verifies it
 * once at fog.tf/api/autoangler/<code> and, if valid, stores it locally so full mode stays
 * on afterwards — even offline. Contact @fog2x on Telegram for a code.
 *
 * The server also records this device's ANDROID_ID against the code (see the admin page
 * at fog.tf/api/autoangler), and can revoke a code — refresh() picks that up.
 */
object License {
    /** Trial length per fishing session (3 minutes). */
    const val TRIAL_MS = 3 * 60 * 1000L

    private const val BASE_URL = "https://fog.tf/api/autoangler/"

    // Must match Config.PREFS ("fishing_bot_prefs").
    private const val PREFS = "fishing_bot_prefs"
    private const val KEY_CODE = "reg_code"

    enum class Outcome { VALID, INVALID, NETWORK_ERROR }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedCode(context: Context): String = prefs(context).getString(KEY_CODE, "") ?: ""

    /** Local flag only — never hits the network, so trial/full state is stable offline. */
    fun isRegistered(context: Context): Boolean = savedCode(context).isNotBlank()

    @SuppressLint("HardwareIds")
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    /** Blocking web check of [code]. Call OFF the main thread. Stores the code locally on VALID. */
    fun verify(context: Context, code: String): Outcome {
        val outcome = query(context, code)
        if (outcome == Outcome.VALID) {
            prefs(context).edit().putString(KEY_CODE, code.trim()).apply()
        }
        return outcome
    }

    /**
     * Blocking re-check of the stored code (for revocation). Call OFF the main thread.
     * Clears the stored code ONLY on an explicit INVALID from the server — a network
     * error keeps the user registered.
     */
    fun refresh(context: Context) {
        val code = savedCode(context)
        if (code.isBlank()) return
        if (query(context, code) == Outcome.INVALID) {
            prefs(context).edit().remove(KEY_CODE).apply()
        }
    }

    private fun query(context: Context, code: String): Outcome {
        val c = code.trim()
        if (c.isEmpty()) return Outcome.INVALID
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(
                BASE_URL + URLEncoder.encode(c, "UTF-8") +
                    "?device_id=" + URLEncoder.encode(deviceId(context), "UTF-8")
            )
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            when (conn.responseCode) {
                in 200..299 -> Outcome.VALID
                in 400..499 -> Outcome.INVALID   // 404 = unknown or deactivated code
                else -> Outcome.NETWORK_ERROR
            }
        } catch (_: Exception) {
            Outcome.NETWORK_ERROR
        } finally {
            conn?.disconnect()
        }
    }
}
