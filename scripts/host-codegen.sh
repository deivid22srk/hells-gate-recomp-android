#!/bin/bash
# host-codegen.sh - build the rexglue CLI on the HOST and run codegen against
# the game's default.xex. Produces generated/default/ with the recompiled C++
# for the entrypoint. Requires:
#   - scripts/setup-android.sh already run (SDK + patches in thirdparty/)
#   - clang >= 18, cmake >= 3.25, ninja on PATH
#   - game/default.xex present (never committed, never leaves this machine)
#
#   ./scripts/host-codegen.sh
#
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${REPO_ROOT}"

SDK_DIR="thirdparty/rexglue-sdk"
BUILD_DIR="out/build/host-codegen"

if [ ! -f "${SDK_DIR}/.patches-applied" ]; then
    echo "[host-codegen] SDK not set up - running scripts/setup-android.sh" >&2
    ./scripts/setup-android.sh
fi

if [ ! -f "game/default.xex" ]; then
    echo "error: game/default.xex not found." >&2
    echo "  Extract your own Xbox 360 dump (ISO) into game/ so the entrypoint" >&2
    echo "  is at game/default.xex. The file is copyrighted - do not commit it," >&2
    echo "  do not upload it anywhere. See README.md." >&2
    exit 1
fi

echo "[host-codegen] Building the rexglue CLI (host, one time)..."
cmake -S "${SDK_DIR}" -B "${BUILD_DIR}" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DREXGLUE_ENABLE_TRACY=OFF \
    -DREXGLUE_BUILD_TESTS=OFF \
    -DREXGLUE_ENABLE_DESKTOP_SDL_BACKENDS=OFF
cmake --build "${BUILD_DIR}" --target rexglue -j"$(nproc)"

REXGLUE_BIN="${SDK_DIR}/out/$(uname -s | tr '[:upper:]' '[:lower:]')-amd64/rexglue"
if [ ! -x "${REXGLUE_BIN}" ]; then
    # Fall back to locating the binary in the build tree.
    REXGLUE_BIN="$(find "${BUILD_DIR}" -name rexglue -type f | head -1)"
fi
echo "[host-codegen] Using ${REXGLUE_BIN}"

echo "[host-codegen] Running codegen against game/default.xex..."
"${REXGLUE_BIN}" codegen dantes_inferno_manifest.toml \
    --log_level=info

echo "[host-codegen] Applying generated-code patches (fiber/setjmp fix)..."
python3 patches/generated/apply_generated_patches.py

echo "[host-codegen] Done: generated/default/ contains the recompiled code."
echo "  Next: ./gradlew -p android assembleDebug  (or scripts/build-android.sh)"
