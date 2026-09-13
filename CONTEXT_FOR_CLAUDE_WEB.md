# AutoAngler — Context untuk Claude AI Web

## Situasi
Aplikasi Android overlay bot untuk fishing mini-game di sebuah game sudah berjalan (tidak crash), tapi **bot gagal menggerakkan garis kuning ke dalam wilayah hijau** saat fishing bar aktif. Bot terdeteksi state FISHING dengan benar, tapi gerakan panah tidak efektif.

Saya akan lampirkan **video gameplay** agar kamu bisa analisis visual untuk bantu fix.

---

## Arsitektur Singkat

```
OverlayService (Foreground Service)
  └─ FullGameController          ← state machine game loop
       ├─ CaptureManager         ← screen capture via MediaProjection
       ├─ VisionProcessor        ← deteksi kuning/hijau dari bitmap
       ├─ BotController          ← logika gerakkan garis (PUSH_LEFT/PUSH_RIGHT)
       ├─ StateDetector          ← deteksi state game dari full frame
       └─ TouchInjector          ← kirim tap ke layar (root sendevent / A11y)
```

---

## File-File Kritis

### 1. `VisionProcessor.kt` — Deteksi warna fishing bar

```kotlin
object VisionProcessor {
    fun process(roi: Bitmap): VisionResult {
        val w = roi.width; val h = roi.height
        val scanY0 = (h / 2 - h / 6).coerceAtLeast(0)
        val scanY1 = (h / 2 + h / 6).coerceAtMost(h - 1)

        val pixels = IntArray(w)
        var yellowSumX = 0L; var yellowCount = 0
        var greenL = Int.MAX_VALUE; var greenR = Int.MIN_VALUE

        for (y in scanY0..scanY1) {
            roi.getPixels(pixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = pixels[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                rgbToHsv(r, g, b, hsv)
                val hue = hsv[0]; val sat = hsv[1]; val `val` = hsv[2]
                when {
                    isYellow(hue, sat, `val`) -> { yellowSumX += x; yellowCount++ }
                    isGreen(hue, sat, `val`)  -> { if (x < greenL) greenL = x; if (x > greenR) greenR = x }
                }
            }
        }
        if (yellowCount == 0 || greenL == Int.MAX_VALUE) return VisionResult()
        return VisionResult(yellowX = (yellowSumX / yellowCount).toInt(),
            greenLeft = greenL, greenRight = greenR, valid = true)
    }
}
```

**HSV thresholds saat ini (Config.kt):**
```
Kuning: H=40–75, S≥0.55, V≥0.55
Hijau:  H=90–155, S≥0.35, V≥0.35
```

**Output:** `VisionResult(yellowX, greenLeft, greenRight, valid)`
- `yellowX` = posisi X rata-rata centroid kuning
- `greenLeft/greenRight` = batas kiri/kanan zona hijau
- `valid = true` hanya jika kedua kuning DAN hijau ditemukan

---

### 2. `BotController.kt` — Logika gerakkan garis

```kotlin
suspend fun tick(result: VisionResult): Boolean {
    if (!result.valid) {
        // bar tidak terdeteksi, hitung waktu hilang
        if (barMissingStart == 0L) barMissingStart = System.currentTimeMillis()
        barMissingSec = (System.currentTimeMillis() - barMissingStart) / 1000f
        state = BotState.IDLE
        return barMissingSec * 1000 > Config.autoStopBarMissingMs  // 3000ms = selesai
    }
    barMissingStart = 0L; barMissingSec = 0f

    val margin = Config.marginPx     // = 8
    val dz = Config.deadzonePx       // = 4
    val center = (result.greenLeft + result.greenRight) / 2

    state = when {
        result.yellowX < result.greenLeft + margin  -> BotState.PUSH_RIGHT
        result.yellowX > result.greenRight - margin -> BotState.PUSH_LEFT
        abs(result.yellowX - center) <= dz          -> BotState.IDLE
        result.yellowX < center                     -> BotState.PUSH_RIGHT
        else                                        -> BotState.PUSH_LEFT
    }

    if (state == BotState.IDLE) return false

    // Proportional pulse: makin jauh, makin lama tekan
    val greenW = (result.greenRight - result.greenLeft).coerceAtLeast(1)
    val dist = when (state) {
        BotState.PUSH_RIGHT -> (result.greenLeft - result.yellowX).coerceAtLeast(0)
        BotState.PUSH_LEFT  -> (result.yellowX - result.greenRight).coerceAtLeast(0)
        else -> 0
    }
    val ratio = (dist.toFloat() / greenW).coerceIn(0f, 1f)
    val pulse = Config.pulseBaseMs + (ratio * (Config.pulseMaxMs - Config.pulseBaseMs)).toLong()
    val jitter = Random.nextLong(-Config.jitterMs / 2, Config.jitterMs / 2 + 1)
    val finalPulse = (pulse + jitter).coerceAtLeast(20L)

    val (tx, ty) = when (state) {
        BotState.PUSH_RIGHT -> if (leftMovesRight) Pair(leftArrowX, leftArrowY)
                               else Pair(rightArrowX, rightArrowY)
        BotState.PUSH_LEFT  -> if (leftMovesRight) Pair(rightArrowX, rightArrowY)
                               else Pair(leftArrowX, leftArrowY)
        else -> return false
    }

    injector.tap(tx, ty, finalPulse)
    delay(Config.interPulseMs)   // = 70ms
    return false
}
```

**Timing params saat ini:**
```
pulseBaseMs = 40ms    ← durasi tap minimum
pulseMaxMs  = 180ms   ← durasi tap maksimum (jarak jauh)
interPulseMs = 70ms   ← jeda antar tap
jitterMs = 15ms       ← random jitter anti-pattern
marginPx = 8          ← trigger push sebelum batas zona
deadzonePx = 4        ← zona mati di tengah
autoStopBarMissingMs = 3000ms
```

---

### 3. `AutoCalibrator.kt` — Koordinat tombol (% layar landscape)

```kotlin
fun calibrate(screenW: Int, screenH: Int): ButtonLayout = ButtonLayout(
    castX      = (screenW * 0.91).toInt(), castY      = (screenH * 0.84).toInt(),
    equipBaitX = (screenW * 0.85).toInt(), equipBaitY = (screenH * 0.84).toInt(),
    reelX      = (screenW * 0.88).toInt(), reelY      = (screenH * 0.84).toInt(),
    leftArrowX  = (screenW * 0.13).toInt(), leftArrowY  = (screenH * 0.83).toInt(),
    rightArrowX = (screenW * 0.87).toInt(), rightArrowY = (screenH * 0.83).toInt(),
    baitItemX   = (screenW * 0.33).toInt(), baitItemY   = (screenH * 0.47).toInt(),
    baitSwitchX = (screenW * 0.63).toInt(), baitSwitchY = (screenH * 0.59).toInt(),
    screenW = screenW, screenH = screenH
)

// ROI fishing bar: scan top 20% layar untuk baris yang ada kuning+hijau
fun findFishingBarRoi(frame: Bitmap, screenW: Int, screenH: Int): Rect? {
    val maxY = (frame.height * 0.20).toInt()
    val cx = frame.width / 2
    var firstRow = -1; var lastRow = -1
    for (y in 0 until maxY) {
        var hasYellow = false; var hasGreen = false
        for (dx in -80..80 step 8) {
            val x = (cx + dx).coerceIn(0, frame.width - 1)
            val pixel = frame.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r > 160 && g > 100 && b < 80) hasYellow = true
            if (g > r + 30 && g > b + 30 && g > 90) hasGreen = true
        }
        if (hasYellow || hasGreen) {
            if (firstRow == -1) firstRow = y
            lastRow = y
        }
    }
    return if (firstRow != -1 && lastRow > firstRow)
        Rect(0, (firstRow - 8).coerceAtLeast(0), screenW, lastRow + 8)
    else null
}
```

---

### 4. `RootInjector.kt` — Kirim tap via sendevent (root)

```kotlin
override fun tap(x: Int, y: Int, holdMs: Long) {
    val dev = eventDevice   // default: /dev/input/event0
    val ms = holdMs.coerceAtLeast(20L)
    val cmd = buildString {
        append("sendevent $dev 3 57 1\n")      // ABS_MT_TRACKING_ID = 1
        append("sendevent $dev 3 53 $x\n")     // ABS_MT_POSITION_X
        append("sendevent $dev 3 54 $y\n")     // ABS_MT_POSITION_Y
        append("sendevent $dev 1 330 1\n")     // BTN_TOUCH down
        append("sendevent $dev 0 0 0\n")       // SYN_REPORT
        append("sleep 0.0${ms.toString().padStart(3, '0')}\n")
        append("sendevent $dev 3 57 4294967295\n") // ABS_MT_TRACKING_ID = -1 (lift)
        append("sendevent $dev 1 330 0\n")     // BTN_TOUCH up
        append("sendevent $dev 0 0 0\n")       // SYN_REPORT
    }
    stdin!!.writeBytes(cmd)
    stdin!!.flush()
}
```

---

### 5. `FullGameController.kt` — State machine & game loop

Loop berjalan ~25 FPS (`LOOP_DELAY_MS = 40ms`).

State machine:
```
IDLE → tap cast → CASTING
CASTING → (deteksi) → HOOKED / FISHING / NO_BAIT  (timeout 12s → IDLE)
HOOKED → tap reel → (kembali ke deteksi)
FISHING → BotController.tick() tiap frame → AFTER_FISH (bar hilang 3s)
AFTER_FISH → delay 500ms → tap cast → CASTING
NO_BAIT → tap equipBait → tunggu 1200ms → SELECT_BAIT (terdeteksi)
SELECT_BAIT → tap bait kiri → tap Switch → IDLE
```

Saat masuk FISHING pertama kali: `AutoCalibrator.findFishingBarRoi()` dipanggil untuk auto-detect ROI dari full frame, lalu crop ke ROI itu untuk proses vision.

---

## Masalah yang Perlu Difix

**Garis kuning tidak berhasil masuk ke zona hijau.**

Kemungkinan penyebab yang perlu dianalisis dari video:

1. **HSV threshold salah** — warna kuning/hijau di game tidak match range H=40–75 / H=90–155. Lihat warna aktual bar dari video (RGB pixel).

2. **ROI salah** — `findFishingBarRoi()` mungkin crop area yang tidak tepat, VisionProcessor menerima area yang bukan fishing bar.

3. **leftArrowMovesRight salah** — jika tekan kiri malah geser ke kiri (bukan ke kanan), bot akan push ke arah yang salah terus. Default `true` tapi bisa jadi di game ini terbalik.

4. **Tap tidak sampai/terlambat** — `sendevent` via su stdin mungkin ada latency. Koordinat panah bisa juga meleset (13% dan 87% tidak pas).

5. **Pulse terlalu pendek atau terlalu panjang** — garis kuning jadi overshoot atau tidak bergerak cukup.

6. **interPulseMs terlalu lama** — 70ms jeda antar tap mungkin terlalu lambat untuk game yang responsif cepat.

---

## Yang Perlu Dilihat dari Video

Tolong analisis video dan berikan:

1. **Warna aktual** fishing bar: nilai RGB / HSV dari garis kuning dan zona hijau. Apakah range threshold saat ini cocok?

2. **Posisi fishing bar** di layar: di mana tepatnya (% dari atas layar)? Apakah di top 20% layar sudah benar?

3. **Arah gerak**: saat tombol kiri ditekan, garis kuning bergerak ke **kiri atau kanan**? (konfirmasi `leftArrowMovesRight`)

4. **Kecepatan/momentum bar**: seberapa cepat garis kuning bergerak? Apakah ada inersia/fisika? Ini menentukan apakah pulse timing perlu disesuaikan.

5. **Ukuran zona hijau** relatif terhadap bar total: berapa % panjang bar?

6. **Koordinat tombol panah** yang benar (% dari lebar/tinggi layar).

---

## File yang Perlu Diubah (setelah analisis)

- **`Config.kt`** — ubah HSV thresholds `yellowHMin/Max`, `greenHMin/Max`, timing `pulseBaseMs`, `interPulseMs`
- **`AutoCalibrator.kt`** — ubah `%` koordinat `leftArrowX/Y`, `rightArrowX/Y`, dan logic `findFishingBarRoi` jika ROI salah
- **`GameState.kt`** — ubah `leftArrowMovesRight` default jika arah terbalik
- **`BotController.kt`** — ubah logika pulse/timing jika perlu

---

## Info Device & Build

- KernelSU (root via `su`, compatible standard interface)
- Android 14 (API 34)
- Mode: ROOT (sendevent via persistent su daemon)
- Loop delay: 40ms (~25 FPS)
- Build: AGP 8.5.2, Kotlin 1.9.24, minSdk 26, targetSdk 35
