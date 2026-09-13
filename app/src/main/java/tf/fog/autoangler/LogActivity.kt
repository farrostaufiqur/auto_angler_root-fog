package tf.fog.autoangler

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import tf.fog.autoangler.util.FileLog

/**
 * Halaman log: menampilkan seluruh isi file log persisten (tetap ada walau habis
 * force-close/crash). UI dibuat programatik supaya tak perlu file layout terpisah.
 * Warna surface/teks memakai token warna adaptif yang ikut mode cerah / gelap sistem.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var tv: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.screen_bg_alt))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(btn("Refresh", R.color.btn_refresh) { load() })
        bar.addView(btn("Clear", R.color.btn_clear) { FileLog.clear(); load() })
        bar.addView(btn("Share", R.color.btn_share) { share() })
        root.addView(bar)

        val pathTv = TextView(this).apply {
            text = FileLog.path()
            setTextColor(color(R.color.text_hint)); textSize = 9f
            setPadding(dp(2), dp(4), dp(2), dp(6))
        }
        root.addView(pathTv)

        tv = TextView(this).apply {
            setTextColor(color(R.color.log_text)); textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        scroll = ScrollView(this).apply { addView(tv) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        load()
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        tv.text = FileLog.readAll()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AutoAngler log")
            putExtra(Intent.EXTRA_TEXT, FileLog.readAll())
        }
        startActivity(Intent.createChooser(intent, "Share log"))
    }

    private fun btn(label: String, @ColorRes bgColor: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; setTextColor(Color.WHITE)
        setBackgroundColor(color(bgColor))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .also { it.setMargins(dp(2), 0, dp(2), 0) }
        setOnClickListener { onClick() }
    }

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(this, id)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
