package tf.fog.autoangler

import android.app.Application
import tf.fog.autoangler.util.Config
import tf.fog.autoangler.util.FileLog

/**
 * Application: titik paling awal proses → init FileLog + pasang crash handler
 * sebelum komponen lain jalan, supaya semua log & crash ikut tersimpan.
 */
class BotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLog.init(this)
        Config.loadFromFile(this)
    }
}
