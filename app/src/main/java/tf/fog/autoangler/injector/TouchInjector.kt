package tf.fog.autoangler.injector

interface TouchInjector {
    val isActive: Boolean
    val modeName: String
    fun tap(x: Int, y: Int, holdMs: Long = 50L)
}

// Varian ROOT-ONLY: hanya ada satu mode. Enum dipertahankan supaya Config (yang menyimpan
// nama mode di SharedPreferences) tak perlu diubah.
enum class InjectorMode { ROOT }
