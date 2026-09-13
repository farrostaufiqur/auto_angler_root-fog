# AutoAngler (Rooted) — Rilis & Play Store

## Status build rilis (sudah disiapkan)

| Item | Nilai |
|---|---|
| `applicationId` | `tf.fog.autoangler` (SAMA dgn varian dua-mode — sengaja) |
| `versionCode` / `versionName` | `1` / `1.0` |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 35 / 35 |
| Signing | keystore RSA-4096, alias `autoangler`, valid ~27 th (`autoangler-release.keystore`) |
| R8 / shrink resources | aktif (`isMinifyEnabled` + `isShrinkResources = true`) |
| Output AAB | `app/build/outputs/bundle/release/app-release.aab` (~2,2 MB) — **ini yang di-upload** |
| Output APK | `app/build/outputs/apk/release/app-release.apk` (~1,5 MB) — sideload/uji, disalin ke `ANDROID/apk/autoangler-rooted_<tgl>.apk` |

Build ulang: `./build_release.sh`

### Kredensial keystore

Ada di `keystore.properties` (root proyek, **di-gitignore**) + file `autoangler-release.keystore`.
**Wajib di-backup di tempat aman** — kalau hilang, tidak bisa update app yang sama di Play
(kecuali pakai Play App Signing + minta reset upload key ke Google).

- SHA-256 sertifikat: `3D:AE:02:22:78:55:6A:4D:77:41:B0:97:D6:77:C1:75:AC:A7:8D:8A:20:E8:09:70:04:E6:FE:F2:AC:0B:36:3E`
  (keystore, cert, DAN `applicationId` semua **sama** dgn `../autoangler/` — sengaja: APK
  root-only bisa dipasang menimpa varian dua-mode sebagai "coba yang satunya". Untuk Play
  Store keduanya = SATU app; pilih salah satu track/APK, tidak bisa dua listing.)

---

## ⚠️ Peringatan kelayakan Play Store

Varian ROOT-ONLY ini **menghilangkan blocker #1** (tak ada `AccessibilityService`, tak ada
form deklarasi Accessibility API). Tapi peluang lolos review **tetap kecil** — otomasi game
melanggar kebijakan lain apa pun cara tap-nya:

1. ~~Penyalahgunaan Accessibility API~~ — **tidak berlaku di varian ini** (service dihapus).
2. **Device and Network Abuse** — otomasi interaksi / bot gameplay termasuk yang dilarang.
3. **Memfasilitasi cheating** — mengotomasi mini-game untuk farming reward umumnya melanggar
   ToS game target; makin berat kalau game-nya online/kompetitif.
4. **`SYSTEM_ALERT_WINDOW` + `MediaProjection`** — diizinkan, tapi diperiksa ketat dan wajib
   ada privacy policy, Data safety form, serta justifikasi `foregroundServiceType`
   (`dataSync` sekarang dibatasi). Digabung poin 1–3 = banyak bendera merah.
5. **Unlock berbayar via kode registrasi di luar Google Play Billing** — kalau dianggap
   "membuka fitur", bisa kena kebijakan pembayaran.

Menghapus AccessibilityService memperbaiki satu titik gagal terbesar, tapi **poin 2–3
(bot game) berlaku apa pun cara tap-nya**. Distribusi realistis tetap = sideload APK dari
situs sendiri / store pihak ketiga.

---

## Kalau tetap mau coba submit ke Play Console

### Sebelum upload
- [ ] Buat akun Play Console (biaya pendaftaran sekali $25).
- [ ] Aktifkan **Play App Signing** (default untuk app baru) — upload pakai
      `autoangler-release.keystore` sebagai *upload key*.
- [ ] Siapkan **Privacy Policy** publik (URL) — wajib karena `MediaProjection` (screen
      capture). Jelaskan: data layar diproses **lokal di perangkat**, tidak dikirim ke
      server, tidak ada analytics.
- [ ] Isi **Data safety form**: tidak ada data dikumpulkan/dibagikan (kalau memang begitu).
- [ ] **Content rating questionnaire** (IARC).
- [ ] **Target audience**: dewasa (bukan anak-anak).

### Deklarasi permission (Play Console → App content)
- [x] **Accessibility API** — N/A, varian ini tak punya AccessibilityService.
- [ ] **All files / sensitive** — tidak dipakai.
- [ ] **Foreground service** (`mediaProjection`, `dataSync`) — jelaskan tiap tipe + kenapa
      butuh berjalan di foreground.
- [ ] **Overlay (`SYSTEM_ALERT_WINDOW`)** — jelaskan HUD melayang di atas game.

### Teknis / kepatuhan
- [ ] Target API level: Play untuk submission baru bisa menuntut **API 35/36**. Sekarang 35.
      Kalau ditolak "target API too low", naikkan `compileSdk`/`targetSdk` (butuh
      `platforms;android-36` + `build-tools` 36 + upgrade AGP ke ≥ 8.6).
- [ ] Uji di perangkat fisik ter-root (Android 12–15).
- [ ] Screenshot + ikon 512×512 + feature graphic 1024×500 untuk listing.
- [ ] Isi rilis di jalur **Internal testing** dulu, jangan langsung Production.

### Kalau ditolak
Jangan banding berulang dengan konten sama. Opsi:
- Reposisikan app (bukan "bot game") — sulit karena fungsinya memang itu.
- Distribusi di luar Play (situs sendiri, APKPure, F-Droid tak cocok karena bukan FOSS murni).
