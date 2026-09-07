#!/bin/bash
# check-java.sh - compile every Java source of the Android app on the JVM.
#
# Uses the Eclipse batch compiler (ECJ) against a Robolectric android-all
# framework jar (android.* stubs), the SDL3 Java glue (copied from the SDK
# submodule), a generated stub R class and this repo's Java sources.
# Catches syntax/typo/API-mismatch errors before pushing to CI.
#
# Tool cache (survives workspace resets): /home/z/my-project/tools
#   ecj-3.33.0.jar               (Eclipse batch compiler)
#   android-all-15-trimmed.jar   (Robolectric android-all 15, java/* trimmed)
#
# Usage: ./scripts/check-java.sh
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${PAD_TOOLS_DIR:-/home/z/my-project/tools}"

ECJ="$TOOLS/ecj-3.33.0.jar"
ANDROID_JAR="$TOOLS/android-all-15-trimmed.jar"
SDK_DIR="${REPO_ROOT}/thirdparty/rexglue-sdk"

if [ ! -f "$ECJ" ]; then
  echo "ECJ not found at $ECJ" >&2
  echo "  curl -fsSL -o '$ECJ' https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar" >&2
  exit 2
fi
if [ ! -f "$ANDROID_JAR" ]; then
  RAW="$TOOLS/android-all-15.jar"
  if [ ! -f "$RAW" ]; then
    echo "android-all jar not found at $RAW" >&2
    echo "  curl -fsSL -o '$RAW' https://repo1.maven.org/maven2/org/robolectric/android-all/15-robolectric-13954326/android-all-15-robolectric-13954326.jar" >&2
    exit 2
  fi
  python3 "${REPO_ROOT}/scripts/trim_android_jar.py" "$RAW" "$ANDROID_JAR"
fi

SDL_JAVA="${SDK_DIR}/thirdparty/sdl3/android-project/app/src/main/java/org/libsdl/app"
if [ ! -d "$SDL_JAVA" ]; then
  # Fall back to a local SDK checkout outside the repo (dev machines).
  SDL_JAVA="/home/z/my-project/rexglue-sdk/thirdparty/sdl3/android-project/app/src/main/java/org/libsdl/app"
fi
if [ ! -d "$SDL_JAVA" ]; then
  echo "SDL3 Java glue not found (run scripts/setup-android.sh or clone the SDK)" >&2
  exit 2
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# --- stub R class generated from the real resources ---------------------------
R="$OUT/stub-R/com/deivid22srk/hellsgate/R.java"
mkdir -p "$(dirname "$R")"
{
  echo "package com.deivid22srk.hellsgate;"
  echo "public final class R {"
  echo "  public static final class string {"
  grep -o 'name="[^"]*"' "${REPO_ROOT}/android/app/src/main/res/values/strings.xml" \
    | sed 's/name="/public static final int /; s/"$/ = 1;/'
  echo "  }"
  echo "  public static final class style {"
  echo "    public static final int AppTheme = 1;"
  echo "  }"
  echo "  public static final class mipmap {"
  echo "    public static final int ic_launcher = 1;"
  echo "  }"
  echo "}"
} > "$R"

SOURCES=(
  "${R}"
  "${SDL_JAVA}"/*.java
  "${REPO_ROOT}/android/app/src/main/java/com/deivid22srk/hellsgate"/*.java
  "${REPO_ROOT}/android/app/src/main/java/com/deivid22srk/hellsgate/gamepad"/*.java
)

echo "[check-java] compiling ${#SOURCES[@]} files..."
ECJ_LOG="$OUT/ecj.log"
if ! java -jar "$ECJ" \
  -source 17 -target 17 \
  -cp "$ANDROID_JAR" \
  -d "$OUT/classes" \
  -nowarn -proc:none \
  "${SOURCES[@]}" > "$ECJ_LOG" 2>&1; then
  cat "$ECJ_LOG"
  echo "JAVA CHECK FAILED"
  exit 1
fi
grep -v "^$" "$ECJ_LOG" || true

echo "JAVA CHECK OK"
