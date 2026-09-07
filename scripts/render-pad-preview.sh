#!/bin/bash
# render-pad-preview.sh - renders the exact PadGeometry layout to PNGs for
# visual review (layout preview: shapes/anchors exact, shading approximated).
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${PAD_TOOLS_DIR:-/home/z/my-project/tools}"
ECJ="$TOOLS/ecj-3.33.0.jar"
OUT_DIR="${1:-/home/z/my-project/download/pad_preview}"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

java -jar "$ECJ" -source 17 -target 17 -d "$OUT/classes" -nowarn -proc:none \
  "${REPO_ROOT}/android/app/src/main/java/com/deivid22srk/hellsgate/gamepad/PadGeometry.java" \
  "${REPO_ROOT}/scripts/PadPreview.java" > "$OUT/ecj.log" 2>&1 || {
    cat "$OUT/ecj.log"; echo "PREVIEW BUILD FAILED"; exit 1; }

java -cp "$OUT/classes" PadPreview "$OUT_DIR"
