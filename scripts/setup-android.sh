#!/bin/bash
# setup-android.sh — clone the ReXGlue SDK (pinned v0.10.0), init submodules,
# and apply the upstream + Android patches. Run from the repo root.
#
#   ./scripts/setup-android.sh
#
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${REPO_ROOT}"

SDK_DIR="thirdparty/rexglue-sdk"
SDK_REPO="https://github.com/rexglue/rexglue-sdk.git"
SDK_TAG="v0.10.0"

if [ -f "${SDK_DIR}/CMakeLists.txt" ] && [ -f "${SDK_DIR}/.patches-applied" ]; then
    echo "[setup-android] SDK already set up at ${SDK_DIR} - skipping."
    exit 0
fi

echo "[setup-android] Cloning ReXGlue SDK ${SDK_TAG}..."
rm -rf "${SDK_DIR}"
git clone --depth 1 --branch "${SDK_TAG}" "${SDK_REPO}" "${SDK_DIR}"

echo "[setup-android] Initializing SDK submodules (SDL3, FFmpeg, glslang, simde, ...)..."
git -C "${SDK_DIR}" submodule update --init --depth 1

echo "[setup-android] Applying upstream project patch..."
git -C "${SDK_DIR}" apply "${REPO_ROOT}/patches/sdk/rexglue-sdk-v0.10.0.patch"

echo "[setup-android] Applying Android port patch..."
git -C "${SDK_DIR}" apply "${REPO_ROOT}/patches/sdk/rexglue-sdk-v0.10.0-android.patch"

touch "${SDK_DIR}/.patches-applied"

# Copy the SDL3 Android Java glue (SDLActivity + friends) into the app.
SDL_JAVA_SRC="${SDK_DIR}/thirdparty/sdl3/android-project/app/src/main/java/org/libsdl/app"
SDL_JAVA_DST="android/app/src/main/java/org/libsdl/app"
if [ -d "${SDL_JAVA_SRC}" ]; then
    mkdir -p "${SDL_JAVA_DST}"
    cp "${SDL_JAVA_SRC}"/*.java "${SDL_JAVA_DST}/"
    echo "[setup-android] Copied SDL3 Java glue into ${SDL_JAVA_DST}"
else
    echo "[setup-android] WARNING: SDL3 android-project Java sources not found at ${SDL_JAVA_SRC}" >&2
fi

echo "[setup-android] Done. SDK ready at ${SDK_DIR} (arm64-v8a Android via NDK, host tools for codegen)."
