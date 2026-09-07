#!/bin/bash
# check-pad-geometry.sh - run the PadGeometry layout test matrix on the JVM.
#
# Compiles PadGeometry (pure java, no android.* imports) plus the test with
# ECJ and runs it. See scripts/PadGeometryTest.java for the invariants.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${PAD_TOOLS_DIR:-/home/z/my-project/tools}"
ECJ="$TOOLS/ecj-3.33.0.jar"

if [ ! -f "$ECJ" ]; then
  echo "ECJ not found at $ECJ" >&2
  exit 2
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

java -jar "$ECJ" -source 17 -target 17 -d "$OUT/classes" -nowarn -proc:none \
  "${REPO_ROOT}/android/app/src/main/java/com/deivid22srk/hellsgate/gamepad/PadGeometry.java" \
  "${REPO_ROOT}/scripts/PadGeometryTest.java" > "$OUT/ecj.log" 2>&1 || {
    cat "$OUT/ecj.log"; echo "GEOMETRY TEST BUILD FAILED"; exit 1; }

java -cp "$OUT/classes" PadGeometryTest
