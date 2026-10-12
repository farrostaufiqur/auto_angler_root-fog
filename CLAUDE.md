# CLAUDE.md — AutoAngler (Rooted) / varian ROOT-ONLY

Salinan dari `../autoangler/` dengan pembeda: **root-only, tanpa AccessibilityService**.
Fungsi bot (screen capture + vision HSV + tap tombol panah) identik. Detail arsitektur &
tuning: `CONTEXT_FOR_CLAUDE.md`. Rilis & Play Store: `PLAY_STORE.md`.

## Beda dari `../autoangler/`

| | `../autoangler/` (dua mode) | `autoangler-root-only/` (ini) |
|---|---|---|
| Mode tap | Root + Non-Root (Aksesibilitas), dipilih di layar utama APK | **Root saja**, tak ada pemilih mode |
| AccessibilityService | ada (`FishingAccessibilityService`) | **dihapus** (file + entri manifest + `res/xml/accessibility_service_config.xml`) |
| `A11yTouchInjector` | ada | dihapus; `InjectorMode` enum tinggal `{ ROOT }` |
| Cek root | saat tekan START; gagal → dialog tawaran pindah Non-Root | **saat app dibuka** (`MainActivity`) + tiap START; gagal → tombol Start **grayout**, tak ada fallback |
| `applicationId` | `tf.fog.autoangler` | `tf.fog.autoangler` (SAMA — sengaja) |
| Label | "AutoAngler" | "AutoAngler (Rooted)" |
| Keystore | `autoangler-release.keystore` | `autoangler-release.keystore` (SAMA — sengaja) |
| Artefak rilis | `autoangler_<yyyymmdd>.{apk,aab}` | `autoangler-rooted_<yyyymmdd>.{apk,aab}` |
| Artefak debug | `autoangler_<yyyymmdd>.apk` | `autoangler-rooted-debug_<yyyymmdd>.apk` |

## Build

Aturan rilis global (AAB wajib, salin APK+AAB ke `apk/` dgn nama sama, build
ulang tiap perubahan) di `/home/albayan/CLAUDE.md` bagian ANDROID/.

- Rilis: `./build_release.sh` (`ARTIFACT_BASE=autoangler-rooted` sudah default).
- Debug: `./build_and_copy.sh`.
- Signing: `keystore.properties` + `autoangler-release.keystore` (gitignore) — **file &
  cert SAMA dgn `../autoangler/`**, jadi APK ini bisa dipasang menimpa varian biasa.
- `applicationId` juga **sama** (`tf.fog.autoangler`) — hanya `rootProject.name`, label,
  dan nama artefak yang beda.
- Perubahan yang menyentuh varian biasa **tidak** otomatis mengalir ke sini — sinkronkan
  manual, lalu build ulang KEDUA folder.

## Catatan

- **UI text = BAHASA INGGRIS saja** (permintaan user). Komentar/log: campuran; tulisan baru → EN.
- Nama internal (`fishing_bot_prefs`, `fishing_bot_channel`) sengaja TIDAK diubah.
- **Registrasi = verifikasi web** (`License.kt`, sama dgn varian biasa): GET
  `https://fog.tf/api/autoangler/<kode>?device_id=<ANDROID_ID>`, butuh permission `INTERNET`.
  API + admin: `WEBSITE/fog/autoangler/`. Dev bagi kode via Telegram `@fog2x`.
- Play Store: varian ini menghilangkan blocker #1 (Accessibility API), tapi otomasi game
  tetap melanggar kebijakan Device Abuse / cheating — lihat `PLAY_STORE.md`.
