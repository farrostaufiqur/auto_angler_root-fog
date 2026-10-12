#!/usr/bin/env bash
# ============================================================
#  AutoAngler (Rooted) — Build RILIS (Linux)
#  Menghasilkan & menyalin ke apk/ (aturan nama sama dgn APK):
#    - autoangler-rooted_<yyyymmdd>.aab   (WAJIB — untuk upload Play Store)
#    - autoangler-rooted_<yyyymmdd>.apk   (untuk sideload / uji)
#  Sumber: app/build/outputs/bundle/release/app-release.aab
#          app/build/outputs/apk/release/app-release.apk
#  Butuh keystore.properties + autoangler-release.keystore di root proyek.
# ============================================================
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/Android/jdk-17}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

# Nama dasar artefak di apk/ (tanpa ekstensi). Varian ROOT-ONLY.
ARTIFACT_BASE="${ARTIFACT_BASE:-autoangler-rooted}"

if [ ! -f keystore.properties ]; then
    echo "keystore.properties tidak ada — build akan TIDAK ter-sign. Batalkan." >&2
    exit 1
fi

echo "========================================"
echo " ${ARTIFACT_BASE} - Build RILIS"
echo "========================================"

echo
echo "[1/2] bundleRelease (AAB) + assembleRelease (APK)..."
./gradlew bundleRelease assembleRelease

AAB_SRC="app/build/outputs/bundle/release/app-release.aab"
APK_SRC="app/build/outputs/apk/release/app-release.apk"
DST_DIR="apk"
STAMP="$(date +%Y%m%d)"
AAB_DST="$DST_DIR/${ARTIFACT_BASE}_${STAMP}.aab"
APK_DST="$DST_DIR/${ARTIFACT_BASE}_${STAMP}.apk"

[ -f "$AAB_SRC" ] || { echo "AAB tidak ditemukan: $AAB_SRC" >&2; exit 1; }
[ -f "$APK_SRC" ] || { echo "APK tidak ditemukan: $APK_SRC" >&2; exit 1; }

echo
echo "[2/2] Salin APK + AAB ke $DST_DIR ..."
mkdir -p "$DST_DIR"
# Simpan hanya rilis terbaru varian ini (CLAUDE.md). Sapu juga nama lama "fishing_".
rm -f "$DST_DIR/${ARTIFACT_BASE}_"*.apk "$DST_DIR/${ARTIFACT_BASE}_"*.aab "$DST_DIR"/fishing_*.apk
cp "$AAB_SRC" "$AAB_DST"
cp "$APK_SRC" "$APK_DST"

echo
echo "========================================"
echo " SELESAI"
echo "   AAB (upload Play Console): $AAB_DST"
echo "   APK (sideload / uji)     : $APK_DST"
echo "========================================"
