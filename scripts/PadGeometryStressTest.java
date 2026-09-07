import com.deivid22srk.hellsgate.gamepad.PadGeometry;
import com.deivid22srk.hellsgate.gamepad.PadGeometry.Control;

/**
 * JVM STRESS matrix for the virtual gamepad layout engine - companion to
 * scripts/PadGeometryTest.java (which it does NOT modify). Adds:
 *
 *   1. Extreme display-cutout insets (asymmetric, deep, symmetric) on
 *      1920x1080 and 2340x1080 at scales 0.75/1.0/1.2.
 *   2. Fine aspect-ratio sweep 1.15..2.40 step 0.05 (h fixed at 1080,
 *      w = round(aspect*h)) at scales 0.75/1.0/1.2, zero insets, plus the
 *      extreme insets whenever the CONTENT aspect stays inside the
 *      documented >= 1.15 envelope.
 *   3. Hit-zone sanity: ring/spiral sampling of points inside each control's
 *      VISUAL shape. Invariants: the center always resolves to the control
 *      itself; a visual point never resolves to a control whose center is
 *      FARTHER than the owner's; sticks resolve to themselves for 100% of
 *      their visual points; buttons (and the d-pad) for > 90%.
 *   4. Restore tab: inside the viewport AND its hit zone contains its whole
 *      visual rect, for the same extremes.
 *
 * Compile exactly like scripts/check-pad-geometry.sh (ECJ, PadGeometry.java
 * + this file) and run class PadGeometryStressTest.
 */
public final class PadGeometryStressTest {

    private static long checks;
    private static int failures;

    private static final String[] NAMES = {
            "STICK_L", "STICK_R", "DPAD", "A", "B", "X", "Y",
            "LB", "RB", "LT", "RT", "BACK", "START", "MENU", "TAB"
    };

    private static final int[][] EXTREME_INSETS = {
            {200, 0, 0, 120},   // deep one-sided cutout + bottom gesture bar
            {0, 60, 200, 0},    // top bar + right-side cutout
            {320, 0, 320, 0},   // deep symmetric side cutouts
    };
    private static final int[] ZERO_INSETS = {0, 0, 0, 0};
    private static final float[] SCALES = {0.75f, 1.0f, 1.2f};

    /** Worst visual self-resolution percentage seen, per control id. */
    private static final double[] worstSelfPct = new double[14];
    /** thefts[owner][thief] = visual points of owner resolved to thief. */
    private static final int[][] thefts = new int[14][14];
    private static long sampledPoints;

    public static void main(String[] args) {
        for (int i = 0; i < 14; i++) {
            worstSelfPct[i] = 100.0;
        }

        scenarioExtremeInsets();
        scenarioAspectSweep();
        scenarioHitZones();

        printStats();

        System.out.printf("%nchecks: %d, failures: %d, sampled points: %d%n",
                checks, failures, sampledPoints);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("PAD GEOMETRY STRESS OK");
    }

    // -------------------------------------------------------------------------
    // Scenario 1: extreme cutout insets on two landscape phone resolutions
    // -------------------------------------------------------------------------

    private static void scenarioExtremeInsets() {
        final int[][] sizes = {{1920, 1080}, {2340, 1080}};
        for (int[] size : sizes) {
            for (int[] inset : EXTREME_INSETS) {
                for (float scale : SCALES) {
                    verifyLayout(size[0], size[1], inset, scale, "extreme-insets");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 2: fine aspect-ratio sweep (h=1080, w=round(aspect*h))
    // -------------------------------------------------------------------------

    private static void scenarioAspectSweep() {
        final int h = 1080;
        int skipped = 0;
        for (double aspect = 1.15; aspect <= 2.4001; aspect += 0.05) {
            final int w = (int) Math.round(aspect * h);
            for (float scale : SCALES) {
                verifyLayout(w, h, ZERO_INSETS, scale, "aspect-sweep");
            }
            // Bonus coverage: the extreme insets whenever the CONTENT aspect
            // stays inside the documented >= 1.15 envelope (below that, the
            // layout engine is explicitly out of scope - see PadGeometryTest).
            for (int[] inset : EXTREME_INSETS) {
                final float cw = w - inset[0] - inset[2];
                final float ch = h - inset[1] - inset[3];
                if (cw / ch < 1.15f) {
                    skipped++;
                    continue;
                }
                for (float scale : SCALES) {
                    verifyLayout(w, h, inset, scale, "aspect-sweep-insets");
                }
            }
        }
        System.out.println("aspect sweep done (skipped " + skipped
                + " sub-1.15 content-aspect inset configs)");
    }

    // -------------------------------------------------------------------------
    // Scenario 3: hit-zone sanity sampling
    // -------------------------------------------------------------------------

    private static void scenarioHitZones() {
        final int[][] sizes = {
                {1242, 1080},   // 1.15:1 foldable cover display
                {1280, 720},
                {1600, 1200},   // 4:3 tablet (NARROW regime)
                {1920, 1080},
                {2048, 1536},   // 4:3 tablet
                {2340, 1080},
                {2960, 1440},   // 2.05:1 phone
        };
        final int[][] insets = {
                {0, 0, 0, 0},
                {200, 0, 0, 120},
                {0, 60, 200, 0},
                {320, 0, 320, 0},
        };
        for (int[] size : sizes) {
            for (int[] inset : insets) {
                final float cw = size[0] - inset[0] - inset[2];
                final float ch = size[1] - inset[1] - inset[3];
                if (cw / ch < 1.15f) {
                    continue; // out of scope (content too square to fit two thumbs)
                }
                for (float scale : SCALES) {
                    sampleHitZones(size[0], size[1], inset, scale);
                }
            }
        }

        // Verdict per control: sticks must be 100%, buttons/d-pad > 90%.
        for (int id = 0; id < 14; id++) {
            final boolean stick = id == PadGeometry.ID_STICK_L
                    || id == PadGeometry.ID_STICK_R;
            final double floor = stick ? 100.0 : 90.0;
            check(worstSelfPct[id] >= floor,
                    NAMES[id] + " visual self-resolution "
                            + String.format("%.2f%%", worstSelfPct[id])
                            + " below expected " + (stick ? "100" : "90") + "%",
                    "hit-zone-sanity");
        }
    }

    private static void sampleHitZones(int w, int h, int[] inset, float scale) {
        final String label = label(w, h, inset, scale) + " hit-zone";
        final Control[] cs = PadGeometry.build(w, h,
                inset[0], inset[1], inset[2], inset[3], scale);
        for (Control c : cs) {
            sampleControl(cs, c, w, h, inset, scale, label);
        }
    }

    private static void sampleControl(Control[] cs, Control c, int w, int h,
                                      int[] inset, float scale, String label) {
        final double[] self = {0};
        final double[] total = {0};

        // Center: strict identity (must resolve to itself).
        final int atCenter = PadGeometry.hitResolve(cs, c.cx, c.cy);
        check(atCenter == c.id,
                "center of " + name(c) + " resolved to " + idName(atCenter), label);
        total[0]++;
        if (atCenter == c.id) {
            self[0]++;
        } else if (atCenter >= 0) {
            thefts[c.id][atCenter]++;
        }

        if (c.type == PadGeometry.TYPE_PILL) {
            // Inscribed disc (always inside the visual rect) + rect extremes.
            ringSample(cs, c, Math.min(c.halfW, c.halfH), self, total, label);
            rectExtremes(cs, c, self, total, label);
        } else {
            ringSample(cs, c, c.radius, self, total, label);
        }

        final double pct = total[0] == 0 ? 100.0 : 100.0 * self[0] / total[0];
        if (pct < worstSelfPct[c.id]) {
            worstSelfPct[c.id] = pct;
        }
        final boolean stick = c.type == PadGeometry.TYPE_STICK;
        final double floor = stick ? 100.0 : 90.0;
        if (pct < floor) {
            final int thief = topThief(c.id);
            System.out.printf(
                    "NOTE: %s self-resolution %.2f%% (%d/%d) at %s - top thief: %s (%d pts)%n",
                    name(c), pct, (long) (total[0] * pct / 100.0), (long) total[0],
                    label(w, h, inset, scale), idName(thief), thefts[c.id][thief]);
        }
    }

    /** Concentric rings from the center out to radius (spiral-ish sampling). */
    private static void ringSample(Control[] cs, Control c, float radius,
                                   double[] self, double[] total, String label) {
        final int rings = 12;
        final int angles = 24;
        for (int k = 1; k <= rings; k++) {
            final float rk = radius * k / (float) rings;
            for (int a = 0; a < angles; a++) {
                final double ang = a * (Math.PI * 2.0) / angles;
                final float x = c.cx + (float) (Math.cos(ang) * rk);
                final float y = c.cy + (float) (Math.sin(ang) * rk);
                if (!PadGeometry.visualContains(c, x, y)) {
                    continue;
                }
                resolvePoint(cs, c, x, y, self, total, label);
            }
        }
    }

    /** Pill rect corners + edge midpoints (visual extremes). */
    private static void rectExtremes(Control[] cs, Control c,
                                     double[] self, double[] total, String label) {
        final float[] dxs = {-c.halfW, 0, c.halfW, c.halfW, c.halfW, 0, -c.halfW, -c.halfW};
        final float[] dys = {-c.halfH, -c.halfH, -c.halfH, 0, c.halfH, c.halfH, c.halfH, 0};
        for (int i = 0; i < dxs.length; i++) {
            resolvePoint(cs, c, c.cx + dxs[i], c.cy + dys[i], self, total, label);
        }
    }

    private static void resolvePoint(Control[] cs, Control c, float x, float y,
                                     double[] self, double[] total, String label) {
        final int r = PadGeometry.hitResolve(cs, x, y);
        check(r >= 0,
                "visual point of " + name(c) + " resolved to nothing", label);
        if (r < 0) {
            return;
        }
        final Control got = cs[r]; // ids are array indices for build()'s 14
        final Control own = cs[c.id];
        final float dGot = PadGeometry.dist2(x, y, got.cx, got.cy);
        final float dOwn = PadGeometry.dist2(x, y, own.cx, own.cy);
        check(dGot <= dOwn + 1e-3f,
                "visual point of " + name(c) + " resolved to FARTHER "
                        + name(got), label);
        total[0]++;
        sampledPoints++;
        if (r == c.id) {
            self[0]++;
        } else {
            thefts[c.id][r]++;
        }
    }

    private static int topThief(int owner) {
        int best = 0;
        for (int i = 1; i < 14; i++) {
            if (thefts[owner][i] > thefts[owner][best]) {
                best = i;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Shared layout invariants (bounds / overlap / center resolve / tab)
    // -------------------------------------------------------------------------

    private static void verifyLayout(int w, int h, int[] inset, float scale,
                                     String scenario) {
        final String label = scenario + " " + label(w, h, inset, scale);
        final Control[] cs = PadGeometry.build(w, h,
                inset[0], inset[1], inset[2], inset[3], scale);

        check(cs.length == 14, "14 controls", label);

        for (Control c : cs) {
            insideViewport(c, w, h, label);
        }
        for (int i = 0; i < cs.length; i++) {
            for (int j = i + 1; j < cs.length; j++) {
                check(!shapesOverlap(cs[i], cs[j]),
                        "no overlap " + name(cs[i]) + " vs " + name(cs[j]),
                        label);
            }
        }
        for (Control c : cs) {
            final int resolved = PadGeometry.hitResolve(cs, c.cx, c.cy);
            check(resolved == c.id,
                    "center of " + name(c) + " resolves to itself (got "
                            + resolved + ")", label);
        }

        verifyTab(w, h, inset, scale, label);
    }

    private static void verifyTab(int w, int h, int[] inset, float scale,
                                  String label) {
        final Control tab = PadGeometry.buildTab(w, h,
                inset[0], inset[1], inset[2], inset[3], scale);
        insideViewport(tab, w, h, label + " tab");

        // The tab's hit zone must contain its whole visual rect (the tab is
        // the ONLY way back from hidden mode - it must never be untouchable).
        final float[] dxs = {-tab.halfW, 0, tab.halfW, tab.halfW, tab.halfW, 0, -tab.halfW, -tab.halfW};
        final float[] dys = {-tab.halfH, -tab.halfH, -tab.halfH, 0, tab.halfH, tab.halfH, tab.halfH, 0};
        for (int i = 0; i < dxs.length; i++) {
            check(PadGeometry.hitContains(tab, tab.cx + dxs[i], tab.cy + dys[i]),
                    "tab hit zone contains visual point " + i, label + " tab");
        }
    }

    private static void insideViewport(Control c, int w, int h, String label) {
        check(c.visualLeft() >= -0.5f, name(c) + " left edge", label);
        check(c.visualTop() >= -0.5f, name(c) + " top edge", label);
        check(c.visualRight() <= w + 0.5f, name(c) + " right edge", label);
        check(c.visualBottom() <= h + 0.5f, name(c) + " bottom edge", label);
    }

    // -------------------------------------------------------------------------
    // Stats output
    // -------------------------------------------------------------------------

    private static void printStats() {
        System.out.println();
        System.out.println("visual self-resolution (worst across configs):");
        for (int id = 0; id < 14; id++) {
            final boolean stick = id == PadGeometry.ID_STICK_L
                    || id == PadGeometry.ID_STICK_R;
            final double floor = stick ? 100.0 : 90.0;
            final String verdict = worstSelfPct[id] >= floor ? "ok" : "BELOW BAR";
            System.out.printf("  %-8s %6.2f%%  [%s]%n", NAMES[id],
                    worstSelfPct[id], verdict);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String label(int w, int h, int[] inset, float scale) {
        return "w=" + w + " h=" + h
                + " inset=" + inset[0] + "/" + inset[1] + "/" + inset[2] + "/" + inset[3]
                + " scale=" + scale;
    }

    private static boolean shapesOverlap(Control a, Control b) {
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
        final Control circle = a.type != PadGeometry.TYPE_PILL ? a : b;
        final Control rect = a.type != PadGeometry.TYPE_PILL ? b : a;
        if (b.type != PadGeometry.TYPE_PILL) {
            final float nx = clamp(circle.cx, rect.cx - rect.halfW, rect.cx + rect.halfW);
            final float ny = clamp(circle.cy, rect.cy - rect.halfH, rect.cy + rect.halfH);
            return PadGeometry.dist2(circle.cx, circle.cy, nx, ny)
                    < circle.radius * circle.radius - 1e-3f;
        }
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
        return idName(c.id);
    }

    private static String idName(int id) {
        return (id >= 0 && id < NAMES.length) ? NAMES[id] : ("#" + id);
    }

    private static void check(boolean ok, String what, String label) {
        checks++;
        if (!ok) {
            failures++;
            if (failures <= 60) {
                System.out.println("FAIL: " + what + "  [" + label + "]");
            }
        }
    }

    private PadGeometryStressTest() {
    }
}
