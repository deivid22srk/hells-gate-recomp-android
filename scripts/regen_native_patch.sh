#!/bin/bash
# regen_native_patch.sh — regenerate patches/sdk/rexglue-sdk-v0.10.0-native-renderer.patch
#
# The native renderer patch (P4) is the per-file byte-level diff between:
#   a = pristine ReXGlue SDK v0.10.0 + P1 (upstream) + P2 (android) + P3 (android-perf)
#   b = a + P4 (native renderer; the working tree at /home/z/my-project/rexglue-sdk)
#
# Only the files P4 owns are diffed (surgical, byte-exact, git-style headers so
# `git apply` processes it exactly like the previous revisions of the patch).
#
# Usage: regen_native_patch.sh <output-patch-path>
set -euo pipefail

OUT="${1:?usage: regen_native_patch.sh <output-patch-path>}"
SDK_REF="/home/z/my-project/rexglue-sdk"           # b (patched + edited working tree)
REPO="/home/z/my-project/hells-gate-recomp-android"
WORK="/tmp/p4gen"

# The exact file set P4 touches. Keep in sync when the native renderer gains
# or loses files.
P4_FILES=(
  "include/rex/graphics/flags.h"
  "include/rex/graphics/vulkan/command_processor.h"
  "include/rex/graphics/vulkan/deferred_command_buffer.h"
  "include/rex/graphics/vulkan/native_frame_renderer.h"
  "include/rex/graphics/vulkan/pipeline_cache.h"
  "include/rex/graphics/vulkan/render_target_cache.h"
  "include/rex/ui/vulkan/functions/device_1_0.inc"
  "src/graphics/CMakeLists.txt"
  "src/graphics/plugin_main_native.cpp"
  "src/graphics/vulkan/command_processor.cpp"
  "src/graphics/vulkan/deferred_command_buffer.cpp"
  "src/graphics/vulkan/native_frame_renderer.cpp"
  "src/graphics/vulkan/pipeline_cache.cpp"
  "src/graphics/vulkan/render_target_cache.cpp"
  "src/ui/vulkan/vulkan_presenter.cpp"
)

rm -rf "${WORK}"
mkdir -p "${WORK}/a"
cd "${WORK}/a"
# a: pristine v0.10.0 from the local clone (HEAD is the unpatched tag), then P1-P3.
git -C /home/z/my-project/rexglue-sdk archive HEAD | tar -x
git apply "${REPO}/patches/sdk/rexglue-sdk-v0.10.0.patch"
git apply "${REPO}/patches/sdk/rexglue-sdk-v0.10.0-android.patch"
git apply "${REPO}/patches/sdk/rexglue-sdk-v0.10.0-android-perf.patch"

# b: the current working tree (all four patches + the native renderer edits).
mkdir -p "${WORK}/b"
cp -r "${SDK_REF}/." "${WORK}/b/"
rm -rf "${WORK}/b/.git"

cd "${WORK}"
: > "${OUT}"
for f in "${P4_FILES[@]}"; do
  if [ ! -f "b/${f}" ]; then
    echo "ERROR: ${f} missing from the patched tree" >&2
    exit 1
  fi
  if [ -f "a/${f}" ]; then
    if cmp -s "a/${f}" "b/${f}"; then
      continue
    fi
    echo "diff --git a/${f} b/${f}" >> "${OUT}"
    { diff -u "a/${f}" "b/${f}" || true; } | sed -e "1s|.*|--- a/${f}|" -e "2s|.*|+++ b/${f}|" >> "${OUT}"
  else
    echo "diff --git a/${f} b/${f}" >> "${OUT}"
    echo "new file mode 100644" >> "${OUT}"
    { diff -u /dev/null "b/${f}" || true; } | sed -e "1s|.*|--- /dev/null|" -e "2s|.*|+++ b/${f}|" >> "${OUT}"
  fi
done

echo "Patch written to ${OUT}"
wc -l "${OUT}"
