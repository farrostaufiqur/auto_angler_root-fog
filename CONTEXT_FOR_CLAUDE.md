# Context: AutoAngler (Rooted) — Analisis Kesalahan Tekan

## Tujuan
Bot overlay Android untuk mini-game mancing di sebuah game. Bot harus:
1. Tap tombol cast → pancing melayang
2. Saat banner "Fish on the hook!" muncul → tap tombol reel
3. Fishing mini-game aktif: gerakkan garis kuning ke dalam zona hijau dengan menekan << atau >>
4. Saat layar hasil tangkapan muncul → tap mana saja → ulang dari step 1

## Perangkat
- Device: Android 14, landscape, resolusi **2400×1080** (full physical pixels)
- Root: KernelSU
- Display scale: ×1.2 (display pixels × 1.2 = physical pixels)
- Screenshot display: 2000×900 (display) → physical: 2400×1080

---

## State Machine Saat Ini

```
IDLE → [tap cast] → CASTING
CASTING → [deteksi banner HOOKED] → HOOKED
HOOKED → [tap reel] → FISHING
FISHING → [tekan << atau >>] → ... → [bar hilang atau layar RESULT]
RESULT → [tap tengah layar] → IDLE
```

---

## Koordinat Tombol (physical pixels, 2400×1080)

Diambil dari analisis screenshot:

| Aksi | X | Y | % layar |
|------|---|---|---------|
| Cast (ikon kail, kanan bawah) | 1697 | 778 | 70.7%, 72.0% |
| Reel (sama dengan cast) | 1697 | 778 | 70.7%, 72.0% |
| << (panah kiri, mini-game) | 269 | 660 | 11.2%, 61.1% |
| >> (panah kanan, mini-game) | 1608 | 660 | 67.0%, 61.1% |
| Tap hasil (tengah layar) | 1200 | 540 | 50%, 50% |

### Sumber Koordinat

Dari screenshot `Idle.png` (display 2000×900, physical 2400×1080):
- 4 tombol aksi berjajar horizontal di kanan bawah
- Tombol 1 (kiri): x≈1095 display → x≈1314 physical (54.75%)
- Tombol 2: x≈1195 display → x≈1434 physical (59.75%)
- Tombol 3 (equip): x≈1298 display → x≈1558 physical (64.9%)
- Tombol 4 (cast/kail): x≈1412 display → x≈1694 physical (70.6%) ← dipakai
- Y semua tombol: y≈648 display → y≈778 physical (72%)

Dari screenshot `Fishing.png` (mini-game aktif):
- Tombol << (panah kiri): display x≈224, y≈550 → physical (269, 660) = 11.2%, 61.1%
- Tombol >> (panah kanan): display x≈1340, y≈550 → physical (1608, 660) = 67.0%, 61.1%

---

## Visual Fishing Bar

```
Landscape 2400×1080, bar di top ~7% layar:

[Fish Stamina ⊙]――――――[kuning]――[====HIJAU====]――――――[⊙ Fishing Line]
 x≈21-27%          x≈27-78% (area scan)              x≈52-57%
```

- **Fish Stamina circle**: ring kuning di x≈516-654 (21-27%) — SEBELUMNYA menyebabkan deteksi kuning palsu
- **Fishing Line circle**: ring teal di x≈1248-1384 (52-57%) — teal, bisa overlap dengan zona hijau
- **Bar track**: x≈636-1248 (antara dua circle)
- **Kursor kuning**: marker kecil, bergerak saat << >> ditekan
- **Zona hijau**: segmen berwarna mint/teal, ukuran dan posisi berubah terus

### Fix Yang Sudah Diterapkan
VisionProcessor sekarang skip x < 27% sehingga Fish Stamina ring tidak terhitung sebagai kuning.

---

## Deteksi State

### HOOKED
Mencari area gelap (brightness < 90) membentuk "pill badge" di:
- Y: sekitar 21% tinggi layar (±3%)
- X: 20–65% lebar layar (bukan full-width, membedakan dari toast lain)
- Threshold: >50% pixel gelap

### RESULT (Layar Hasil)
Mencari area cerah menutupi pusat layar (brightness > 155):
- Sample grid 8×8 di x=20-80%, y=25-75%
- Jika >50% sample sangat cerah → candidate
- Konfirmasi: area tombol kanan bawah (x=55-75%, y=65-78%) juga cerah
  (artinya tombol aksi sudah hilang, tertutup panel hasil)

### FISHING
`VisionResult.valid = true` jika VisionProcessor menemukan pixel kuning DAN hijau di ROI bar.

---

## Vision Processor (Deteksi Warna Bar)

### Threshold HSV
```
Kuning (kursor):
  H: 40°–75°
  S: ≥ 0.40
  V: ≥ 0.55

Hijau/mint (zona target):
  H: 140°–178°   ← mint/teal, bukan hijau standar (H≈120°)
  S: ≥ 0.35
  V: ≥ 0.35
```

### Area Scan
- X: dimulai dari 27% lebar layar (skip Fish Stamina circle)
- Y: baris tengah ROI (middle 1/3 dari ROI height)
- ROI default: top 20% layar
- ROI auto-detect: saat FISHING pertama terdeteksi, scan top 14% untuk cari baris dengan kuning+hijau

### Output VisionResult
```kotlin
data class VisionResult(
    val yellowX: Int,    // centroid X dari semua pixel kuning
    val greenLeft: Int,  // x paling kiri dari pixel hijau
    val greenRight: Int, // x paling kanan dari pixel hijau
    val valid: Boolean   // true jika kuning DAN hijau keduanya terdeteksi
)
```

### HUD Display (saat FISHING)
`Y=1234 G=[900..1150]` → Y=posisi kursor kuning, G=batas zona hijau

---

## BotController Logic (Arah Tekan)

```kotlin
state = when {
    yellowX < greenLeft + margin(8px)  → PUSH_RIGHT (kursor terlalu kiri)
    yellowX > greenRight - margin(8px) → PUSH_LEFT  (kursor terlalu kanan)
    abs(yellowX - center) <= deadzone(4px) → IDLE (sudah di tengah zona)
    yellowX < center → PUSH_RIGHT
    else             → PUSH_LEFT
}
```

**Mapping tombol:**
```kotlin
leftArrowMovesRight = false  // tekan << → kursor bergerak ke KIRI (mapping normal)

PUSH_LEFT  → tap leftArrowX/Y   (tombol <<, x=11.2%)
PUSH_RIGHT → tap rightArrowX/Y  (tombol >>, x=67.0%)
```

### Pulse Duration
Proporsional dengan jarak kursor ke tepi zona hijau:
- `pulseBaseMs = 40ms` (dekat zona)
- `pulseMaxMs = 180ms` (jauh dari zona)
- Jitter: ±7ms untuk randomisasi

---

## Touch Injection (Input Tap)

Menggunakan `input tap X Y` via persistent su stdin:
```kotlin
stdin!!.writeBytes("input tap $x $y\n")
stdin!!.flush()
```

**Tidak** menggunakan `sendevent` (terlalu bergantung event device, tidak reliable di semua kernel).

---

## File Utama

```
app/src/main/java/tf/fog/autoangler/
├── game/
│   ├── GameState.kt          ← enum state + ButtonLayout data class
│   ├── AutoCalibrator.kt     ← koordinat tombol + ROI detection
│   ├── StateDetector.kt      ← deteksi HOOKED dan RESULT dari frame
│   ├── ButtonFinder.kt       ← deteksi << >> dari pixel (image recognition)
│   └── FullGameController.kt ← state machine utama
├── vision/
│   └── VisionProcessor.kt   ← analisis HSV bar kuning/hijau
├── controller/
│   └── BotController.kt     ← logika arah tekan berdasarkan VisionResult
├── injector/
│   └── RootInjector.kt      ← tap via su stdin (input tap)
└── overlay/
    ├── OverlayService.kt     ← Android Service utama
    └── HudView.kt            ← UI overlay (drag, state display, kontrol)
```

---

## Masalah Yang Diketahui / Pertanyaan Untuk Dianalisis

### 1. Koordinat tombol << >> — perlu verifikasi
Saat ini: `leftArrowX = 269 (11.2%)`, `rightArrowX = 1608 (67.0%)`

Dari screenshot fishing mini-game (display 2000×900):
- Tombol << terlihat di display x≈224, y≈550 (lingkaran besar dengan panah kiri)
- Tombol >> terlihat di display x≈1340, y≈550 (lingkaran besar dengan panah kanan)

**Pertanyaan:** Apakah koordinat ini benar? Tombol >> di 67% = x=1608 dari 2400 benar atau seharusnya lebih ke kanan/kiri?

### 2. Apakah kursor kuning selalu di KIRI zona hijau pada awal?
Jika saat fishing dimulai, kursor dimulai dari tengah dan zona hijau random, maka:
- 50% chance kuning di kiri zona → PUSH_RIGHT
- 50% chance kuning di kanan zona → PUSH_LEFT

Apakah ada kecenderungan selalu push ke satu arah yang menunjukkan deteksi warna salah?

### 3. Deteksi HOOKED gagal?
Banner "Fish on the hook!" muncul di y≈21% dan x=20-65% (pill badge, bukan full-width).
Jika deteksi gagal, bot tidak pernah masuk state HOOKED dan terus CASTING sampai timeout.

Threshold saat ini: `brightness < 90` untuk "gelap", `>50%` sample harus gelap.

### 4. Deteksi RESULT — belum pernah diuji
Belum ada konfirmasi apakah deteksi layar hasil tangkapan bekerja. Jika gagal, bot akan
stuck di state FISHING/IDLE dan tidak cast ulang setelah ikan dapat.

### 5. ButtonFinder untuk << >>
`ButtonFinder.findArrowButtons()` mencari cluster pixel putih (R>185,G>185,B>185) di:
- << area: x=3-22%, y=50-73%
- >> area: x=55-78%, y=50-73%

Jika gagal, fallback ke koordinat kalibrasi AutoCalibrator.

---

## Yang Perlu Diperbaiki (Minta Saran Claude)

1. **Verifikasi koordinat cast/reel**: apakah (70.7%, 72%) tepat pada tombol hook/kail?
2. **Verifikasi koordinat << >>**: apakah (11.2%, 61.1%) dan (67%, 61.1%) tepat?
3. **Deteksi HOOKED**: bagaimana membuat lebih robust? (saat ini 1 dari 3 kali terdeteksi)
4. **Deteksi RESULT**: apakah logic brightness check sudah cukup untuk layar hasil tangkapan game?
5. **HSV thresholds**: apakah H=140-178 untuk mint/teal sudah benar? H=40-75 untuk kuning?
6. **Fallback**: jika ButtonFinder gagal deteksi << >>, koordinat fallback sudah benar?

---

## Cara Apply Fix

Setelah Claude app menganalisis dan memberikan saran perubahan kode:
1. Buka Claude CLI di direktori `~/ANDROID/autoangler`
2. Paste saran perubahan dengan instruksi: "terapkan perubahan ini: [saran Claude app]"
3. Claude CLI akan edit file yang tepat
4. Build:
   - Dev/uji cepat: `./build_and_copy.sh` → APK debug ke `ANDROID/apk/`
   - Rilis (ter-sign, untuk Play Store): `./build_release.sh` → AAB + APK rilis
     (butuh `keystore.properties` + `autoangler-release.keystore` di root proyek).
   Kedua skrip sudah men-default `JAVA_HOME=~/Android/jdk-17` &
   `ANDROID_SDK_ROOT=~/Android/Sdk`. Manual: `./gradlew assembleDebug` /
   `./gradlew bundleRelease assembleRelease`.

Detail rilis & langkah Play Console: lihat `PLAY_STORE.md`.
