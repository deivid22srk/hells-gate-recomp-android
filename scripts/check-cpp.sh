#!/bin/bash
# check-cpp.sh - host syntax check of the Android native app sources.
#
# Compiles android_gamepad.cpp / android_main.cpp with -fsyntax-only against
# the SDK headers, SDL3, fmt/spdlog, OpenJDK's JNI headers and a stub
# <android/log.h> (the NDK's own headers are only available in CI/NDK).
#
# Tool cache: /home/z/my-project/tools
#   jni-include/{jni.h,linux/jni_md.h}   (OpenJDK jdk-21+35)
#   android-stub/android/log.h           (minimal NDK header stub)
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${PAD_SDK_DIR:-/home/z/my-project/rexglue-sdk}"
JNI="/home/z/my-project/tools/jni-include"
STUB="/home/z/my-project/tools/android-stub"

if [ ! -f "$JNI/jni.h" ]; then
  mkdir -p "$JNI/linux"
  curl -fsSL -o "$JNI/jni.h" \
    "https://raw.githubusercontent.com/openjdk/jdk/jdk-21%2B35/src/java.base/share/native/include/jni.h"
  curl -fsSL -o "$JNI/linux/jni_md.h" \
    "https://raw.githubusercontent.com/openjdk/jdk/jdk-21%2B35/src/java.base/unix/native/include/jni_md.h"
fi
if [ ! -f "$STUB/android/log.h" ]; then
  mkdir -p "$STUB/android"
  cat > "$STUB/android/log.h" << 'EOF'
// Host syntax-check stub for the NDK <android/log.h> (never shipped).
#pragma once
#include <stdarg.h>
#ifdef __cplusplus
extern "C" {
#endif
enum { ANDROID_LOG_ERROR = 6, ANDROID_LOG_WARN = 5, ANDROID_LOG_INFO = 4 };
int __android_log_print(int prio, const char* tag, const char* fmt, ...)
    __attribute__((format(printf, 3, 4)));
#ifdef __cplusplus
}
#endif
EOF
fi

for f in android_gamepad.cpp android_main.cpp; do
  echo "[check-cpp] $f"
  g++ -fsyntax-only -std=c++2b \
    -D__ANDROID__=1 -DREX_PLATFORM_ANDROID=1 -DREXGLUE_BUILD_CONFIG='"Debug"' \
    -I "${REPO_ROOT}/android/app/src/main/cpp" \
    -I "${SDK}/include" -I "${SDK}/src/ui" \
    -I "${SDK}/thirdparty/sdl3/include" \
    -I "${SDK}/thirdparty/fmt/include" \
    -I "${SDK}/thirdparty/spdlog/include" \
    -I "$JNI" -I "$JNI/linux" -I "$STUB" \
    "${REPO_ROOT}/android/app/src/main/cpp/$f"
done
echo "CPP CHECK OK"
