#!/usr/bin/env bash
# ============================================================
#  AutoAngler (Rooted) — Build & Copy debug (Linux)
#  Build APK debug lalu salin ke apk/ sebagai
#  autoangler-rooted-debug_<yyyymmdd>.apk (nama beda dari artefak
#  rilis autoangler-rooted_ dan dari varian biasa autoangler_).
# ============================================================
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/Android/jdk-17}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

ARTIFACT_BASE="autoangler-rooted-debug"

echo "========================================"
echo " AutoAngler (Rooted) - Build & Copy debug"
echo "========================================"

echo
echo "[1/2] Build APK debug..."
./gradlew assembleDebug

SRC="app/build/outputs/apk/debug/app-debug.apk"
DST_DIR="apk"
DST="$DST_DIR/${ARTIFACT_BASE}_$(date +%Y%m%d).apk"

[ -f "$SRC" ] || { echo "BUILD GAGAL / APK tidak ditemukan: $SRC" >&2; exit 1; }

echo
echo "[2/2] Salin APK ke $DST ..."
mkdir -p "$DST_DIR"
rm -f "$DST_DIR/${ARTIFACT_BASE}_"*.apk
cp "$SRC" "$DST"

echo
echo "========================================"
echo " SELESAI: $DST"
echo "========================================"
