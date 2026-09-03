#!/bin/bash
# build-android.sh - full local Android build (APK with YOUR game files).
#
#   ./scripts/build-android.sh [debug|release]
#
# Prerequisites: Android SDK + NDK 27.x, Java 17+, scripts/setup-android.sh,
# and game/default.xex (see README). The resulting APK contains recompiled
# game code derived from YOUR dump - do not distribute it.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${REPO_ROOT}"

VARIANT="${1:-debug}"

if [ ! -f "generated/default/sources.cmake" ]; then
    echo "[build-android] No codegen output - running host codegen (needs game/default.xex)..."
    ./scripts/host-codegen.sh
fi

echo "[build-android] Gradle assemble${VARIANT}..."
./gradlew -p android "assemble${VARIANT^}"

APK="android/app/build/outputs/apk/${VARIANT}/app-${VARIANT}.apk"
echo "[build-android] Done: ${APK}"
echo "  Install: adb install -r ${APK}"
echo "  Reminder: the APK embeds code recompiled from your own dump - personal use only."
