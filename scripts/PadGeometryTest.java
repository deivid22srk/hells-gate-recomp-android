import com.deivid22srk.hellsgate.gamepad.PadGeometry;
import com.deivid22srk.hellsgate.gamepad.PadGeometry.Control;

/**
 * JVM test matrix for the virtual gamepad layout engine (PadGeometry).
 *
 * Sweeps screen sizes, aspect ratios, cutout insets and user scale settings,
 * and asserts the AAA layout invariants:
 *   1. all controls are laid out,
 *   2. every visual shape sits fully inside the viewport,
 *   3. no two visual shapes overlap,
 *   4. each control's visual center resolves (nearest-wins hit test) to
 *      itself - i.e. aiming at a control always presses THAT control,
 *   5. the hidden-mode restore tab stays inside the viewport.
 *
 * Run via scripts/check-pad-geometry.sh.
 */
public final class PadGeometryTest {

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        final int[] widths = {1280, 1600, 1920, 2048, 2340, 2400, 2560, 2960, 3840};
        final int[] heights = {540, 720, 900, 1080, 1280, 1440, 1536, 1600};
        final int[][] insets = {
                {0, 0, 0, 0},
                {100, 0, 100, 0},   // landscape cutout on both short edges
                {0, 48, 0, 0},      // top bar
                {132, 0, 0, 64},    // one-sided cutout + bottom bar
        };
        final float[] scales = {0.75f, 1.0f, 1.2f, 1.4f};

        for (int w : widths) {
            for (int h : heights) {
                // The game is locked to sensorLandscape; the narrowest real
                // landscape viewport (foldable cover displays) is ~1.15:1,
                // phones are 1.78..2.22, tablets 1.33. Below ~1.15 two
                // thumb-reachable sticks physically cannot fit side by side,
                // so those viewports are out of scope for the overlay.
                if (w <= h || (float) w / (float) h < 1.15f) {
                    continue;
                }
                for (int[] inset : insets) {
                    for (float scale : scales) {
                        verify(w, h, inset, scale);
                    }
                }
            }
        }

        System.out.printf("checks: %d, failures: %d%n", checks, failures);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("PAD GEOMETRY OK");
    }

    private static void verify(int w, int h, int[] inset, float scale) {
        final String label = "w=" + w + " h=" + h
                + " inset=" + inset[0] + "/" + inset[1] + "/" + inset[2] + "/" + inset[3]
                + " scale=" + scale;
        final Control[] cs = PadGeometry.build(w, h, inset[0], inset[1], inset[2],
                inset[3], scale);

        check(cs.length == 14, "14 controls", label);

        // 2. every visual inside the viewport
        for (Control c : cs) {
            insideViewport(c, w, h, label);
        }

        // 3. no pairwise visual overlap
        for (int i = 0; i < cs.length; i++) {
            for (int j = i + 1; j < cs.length; j++) {
                final boolean overlap = shapesOverlap(cs[i], cs[j]);
                check(!overlap, "no overlap " + name(cs[i]) + " vs " + name(cs[j]),
                        label + " gap-violation");
            }
        }

        // 4. visual centers resolve to themselves
        for (Control c : cs) {
            final int resolved = PadGeometry.hitResolve(cs, c.cx, c.cy);
            check(resolved == c.id,
                    "center of " + name(c) + " resolves to itself (got " + resolved + ")",
                    label);
        }

        // 5. restore tab (hidden mode)
        final Control tab = PadGeometry.buildTab(w, h, inset[0], inset[1],
                inset[2], inset[3], scale);
        insideViewport(tab, w, h, label + " tab");
    }

    private static void insideViewport(Control c, int w, int h, String label) {
        check(c.visualLeft() >= -0.5f, name(c) + " left edge", label);
        check(c.visualTop() >= -0.5f, name(c) + " top edge", label);
        check(c.visualRight() <= w + 0.5f, name(c) + " right edge", label);
        check(c.visualBottom() <= h + 0.5f, name(c) + " bottom edge", label);
    }

    private static boolean shapesOverlap(Control a, Control b) {
        // Conservative bounding-circle test first, then exact circle/rect.
        final float ar = a.type == PadGeometry.TYPE_PILL
                ? (float) Math.hypot(a.halfW, a.halfH) : a.radius;
        final float br = b.type == PadGeometry.TYPE_PILL
                ? (float) Math.hypot(b.halfW, b.halfH) : b.radius;
        if (PadGeometry.dist2(a.cx, a.cy, b.cx, b.cy) > (ar + br) * (ar + br)) {
            return false;
        }
        return exactOverlap(a, b);
    }

    private static boolean exactOverlap(Control a, Control b) {
        if (a.type != PadGeometry.TYPE_PILL && b.type != PadGeometry.TYPE_PILL) {
            final float d2 = PadGeometry.dist2(a.cx, a.cy, b.cx, b.cy);
            final float sum = a.radius + b.radius;
            return d2 < sum * sum - 1e-3f;
        }
        // Circle vs rect (and rect vs rect via circle-rect both ways is not
        // exact for pills; use closest-point distance both ways).
        final Control circle = a.type != PadGeometry.TYPE_PILL ? a : b;
        final Control rect = a.type != PadGeometry.TYPE_PILL ? b : a;
        if (b.type != PadGeometry.TYPE_PILL) {
            final float nx = clamp(circle.cx, rect.cx - rect.halfW, rect.cx + rect.halfW);
            final float ny = clamp(circle.cy, rect.cy - rect.halfH, rect.cy + rect.halfH);
            return PadGeometry.dist2(circle.cx, circle.cy, nx, ny)
                    < circle.radius * circle.radius - 1e-3f;
        }
        // Rect vs rect (pills)
        final boolean xOverlap = a.cx - a.halfW < b.cx + b.halfW - 1e-3f
                && b.cx - b.halfW < a.cx + a.halfW - 1e-3f;
        final boolean yOverlap = a.cy - a.halfH < b.cy + b.halfH - 1e-3f
                && b.cy - b.halfH < a.cy + a.halfH - 1e-3f;
        return xOverlap && yOverlap;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String name(Control c) {
        final String[] names = {
                "STICK_L", "STICK_R", "DPAD", "A", "B", "X", "Y",
                "LB", "RB", "LT", "RT", "BACK", "START", "MENU", "TAB"
        };
        return names[c.id];
    }

    private static void check(boolean ok, String what, String label) {
        checks++;
        if (!ok) {
            failures++;
            if (failures <= 40) {
                System.out.println("FAIL: " + what + "  [" + label + "]");
            }
        }
    }

    private PadGeometryTest() {
    }
}
