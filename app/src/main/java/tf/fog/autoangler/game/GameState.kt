package tf.fog.autoangler.game

enum class GameState {
    IDLE,     // Siap cast — 4 tombol aksi terlihat di kanan bawah
    CASTING,  // Cast dilakukan — tunggu ikan
    HOOKED,   // "Fish on the hook!" muncul — tap reel
    FISHING,  // Mini-game bar kuning/hijau aktif — tekan << >>
    RESULT    // Layar hasil tangkapan — tap mana saja untuk lanjut
}

data class ButtonLayout(
    // Tombol cast dan reel ada di posisi yang sama (kanan bawah, ikon kail)
    val castX: Int, val castY: Int,
    val reelX: Int, val reelY: Int,
    // Tombol panah << >> di fishing mini-game
    val leftArrowX: Int, val leftArrowY: Int,
    val rightArrowX: Int, val rightArrowY: Int,
    // false = tombol kiri menggeser kursor ke KIRI (mapping normal)
    val leftArrowMovesRight: Boolean = false,
    // ROI bar fishing (diisi otomatis saat bar terdeteksi pertama kali)
    val roiTop: Int = 0, val roiBottom: Int = 0,
    val screenW: Int, val screenH: Int
)
