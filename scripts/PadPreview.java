import com.deivid22srk.hellsgate.gamepad.PadGeometry;
import com.deivid22srk.hellsgate.gamepad.PadGeometry.Control;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Renders the REAL PadGeometry layout (the exact engine the overlay uses) to
 * PNG snapshots for visual review. This is a LAYOUT preview: shapes, anchors,
 * proportions and labels are exact; the glass shading / press-glow details of
 * the Android renderer are approximated here (flat fills + strokes).
 *
 * Run via scripts/render-pad-preview.sh -> /home/z/my-project/download/pad_preview
 */
public final class PadPreview {

    private static final Color BG = new Color(0x0A0C10);
    private static final Color GLASS = new Color(0x1E2733);
    private static final Color GLASS_PRESSED = new Color(0x2A3848);
    private static final Color STROKE = new Color(0x9FB4CC);
    private static final Color ACCENT_NEUTRAL = new Color(0xB8C4D6);
    private static final Color ACCENT_A = new Color(0x7ED957);
    private static final Color ACCENT_B = new Color(0xFF5A5F);
    private static final Color ACCENT_X = new Color(0x4FC3F7);
    private static final Color ACCENT_Y = new Color(0xFFD54F);

    public static void main(String[] args) throws Exception {
        Path outDir = Paths.get(args.length > 0 ? args[0] : "/home/z/my-project/download/pad_preview");
        Files.createDirectories(outDir);

        // name, w, h, insets(L,T,R,B), scale, right-stick deflection x/y (-1..1)
        Object[][] cases = {
                {"phone_2340x1080_s100", 2340, 1080, new int[]{0, 0, 0, 0}, 1.00f, 0.0f, 0.0f},
                {"phone_2340x1080_s120_stick", 2340, 1080, new int[]{0, 0, 0, 0}, 1.20f, 0.7f, -0.6f},
                {"phone_2400x1080_cutout_s100", 2400, 1080, new int[]{110, 0, 110, 0}, 1.00f, 0.0f, 0.0f},
                {"phone_1920x1080_s085", 1920, 1080, new int[]{0, 0, 0, 0}, 0.85f, 0.0f, 0.0f},
                {"tablet_2048x1536_s100", 2048, 1536, new int[]{0, 0, 0, 0}, 1.00f, 0.0f, 0.0f},
                {"wide_2960x1440_s100", 2960, 1440, new int[]{0, 0, 0, 0}, 1.00f, 0.0f, 0.0f},
        };

        for (Object[] c : cases) {
            String name = (String) c[0];
            int w = (Integer) c[1];
            int h = (Integer) c[2];
            int[] inset = (int[]) c[3];
            float scale = (Float) c[4];
            float rsx = (Float) c[5];
            float rsy = (Float) c[6];

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BG);
            g.fillRect(0, 0, w, h);

            Control[] cs = PadGeometry.build(w, h, inset[0], inset[1], inset[2], inset[3], scale);
            for (Control control : cs) {
                drawControl(g, control, rsx, rsy);
            }

            // Simulated game area label + safe-area frame for context.
            g.setColor(new Color(0x1B2A38));
            g.setStroke(new BasicStroke(2));
            g.drawRect(inset[0], inset[1], w - inset[0] - inset[2], h - inset[1] - inset[3]);
            g.setColor(new Color(0x31465C));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 36));
            g.drawString(name + "   (layout preview - exact PadGeometry)", inset[0] + 40,
                    Math.min(h - 40, inset[1] + h - 60));

            g.dispose();
            File out = outDir.resolve(name + ".png").toFile();
            ImageIO.write(img, "png", out);
            System.out.println("wrote " + out);
        }
        System.out.println("PREVIEW OK");
    }

    private static void drawControl(Graphics2D g, Control c, float rsx, float rsy) {
        Color accent = accentOf(c.id);
        switch (c.type) {
            case PadGeometry.TYPE_PILL: {
                g.setColor(GLASS);
                g.fill(new RoundRectangle2D.Float(c.cx - c.halfW, c.cy - c.halfH,
                        c.halfW * 2, c.halfH * 2, c.halfH * 2, c.halfH * 2));
                g.setColor(STROKE);
                g.setStroke(new BasicStroke(Math.max(2f, c.halfH * 0.10f)));
                g.draw(new RoundRectangle2D.Float(c.cx - c.halfW, c.cy - c.halfH,
                        c.halfW * 2, c.halfH * 2, c.halfH * 2, c.halfH * 2));
                label(g, labelOf(c.id), c.cx, c.cy, c.halfH * 0.9f, Color.WHITE);
                break;
            }
            case PadGeometry.TYPE_STICK: {
                float r = c.radius;
                g.setColor(GLASS);
                g.fill(new Ellipse2D.Float(c.cx - r, c.cy - r, r * 2, r * 2));
                g.setStroke(new BasicStroke(Math.max(2f, r * 0.045f)));
                g.setColor(STROKE);
                g.draw(new Ellipse2D.Float(c.cx - r, c.cy - r, r * 2, r * 2));
                // cap with deflection (illustrates the live cap feedback)
                float mag = (float) Math.hypot(rsx, rsy);
                float clamped = Math.min(1f, mag);
                float ux = mag > 0.001f ? rsx / mag : 0f;
                float uy = mag > 0.001f ? rsy / mag : 0f;
                float capR = r * 0.55f;
                // Mirrors VirtualPadView.updateStick deflection factor (0.42R).
                float capX = c.cx + ux * clamped * r * 0.42f;
                float capY = c.cy + uy * clamped * r * 0.42f;
                g.setColor(GLASS_PRESSED);
                g.fill(new Ellipse2D.Float(capX - capR, capY - capR, capR * 2, capR * 2));
                g.setColor(new Color(0xD8E4F2));
                g.draw(new Ellipse2D.Float(capX - capR, capY - capR, capR * 2, capR * 2));
                break;
            }
            case PadGeometry.TYPE_DPAD: {
                float r = c.radius;
                float armW = r * 0.46f;
                g.setColor(GLASS);
                arm(g, c.cx, c.cy - r * 0.55f, armW, r * 1.10f, true);
                arm(g, c.cx, c.cy + r * 0.55f, armW, r * 1.10f, true);
                arm(g, c.cx - r * 0.55f, c.cy, r * 1.10f, armW, false);
                arm(g, c.cx + r * 0.55f, c.cy, r * 1.10f, armW, false);
                g.setColor(GLASS_PRESSED);
                g.fill(new Ellipse2D.Float(c.cx - armW * 0.62f, c.cy - armW * 0.62f,
                        armW * 1.24f, armW * 1.24f));
                arrow(g, c.cx, c.cy - r * 0.72f, armW, true, false);
                arrow(g, c.cx, c.cy + r * 0.72f, armW, true, true);
                arrow(g, c.cx - r * 0.72f, c.cy, armW, false, false);
                arrow(g, c.cx + r * 0.72f, c.cy, armW, false, true);
                break;
            }
            case PadGeometry.TYPE_BUTTON: {
                float r = c.radius;
                g.setColor(GLASS);
                g.fill(new Ellipse2D.Float(c.cx - r, c.cy - r, r * 2, r * 2));
                g.setStroke(new BasicStroke(Math.max(2f, r * 0.09f)));
                g.setColor(accent);
                g.draw(new Ellipse2D.Float(c.cx - r, c.cy - r, r * 2, r * 2));
                switch (c.id) {
                    case PadGeometry.ID_BACK:
                        chevrons(g, c.cx, c.cy, r, false, accent);
                        break;
                    case PadGeometry.ID_START:
                        chevrons(g, c.cx, c.cy, r, true, accent);
                        break;
                    case PadGeometry.ID_MENU:
                        g.setColor(ACCENT_NEUTRAL);
                        g.setStroke(new BasicStroke(Math.max(2f, r * 0.28f)));
                        g.draw(new Ellipse2D.Float(c.cx - r * 0.55f, c.cy - r * 0.55f,
                                r * 1.1f, r * 1.1f));
                        break;
                    default:
                        label(g, labelOf(c.id), c.cx, c.cy, r * 1.05f, accent);
                }
                break;
            }
            default:
                break;
        }
    }

    private static void arm(Graphics2D g, float cx, float cy, float w, float h, boolean vertical) {
        RoundRectangle2D.Float rr = vertical
                ? new RoundRectangle2D.Float(cx - w / 2, cy - h / 2, w, h, w * 0.6f, w * 0.6f)
                : new RoundRectangle2D.Float(cx - h / 2, cy - w / 2, h, w, w * 0.6f, w * 0.6f);
        g.fill(rr);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0x506B84));
        g.draw(rr);
    }

    private static void arrow(Graphics2D g, float cx, float cy, float armW,
                              boolean vertical, boolean flip) {
        // Mirrors VirtualPadView.drawArrow exactly (enlarged per UI/UX review:
        // half 0.30, len 0.45 of armW; idle contrast lives in PadTheme there).
        float half = armW * 0.30f;
        float len = armW * 0.45f;
        float tipA = flip ? len : -len;
        float baseA = flip ? -len * 0.6f : len * 0.6f;
        g.setColor(new Color(0xC9D6E4));
        java.awt.Polygon p = new java.awt.Polygon();
        if (vertical) {
            p.addPoint((int) cx, (int) (cy + tipA));
            p.addPoint((int) (cx - half), (int) (cy + baseA));
            p.addPoint((int) (cx + half), (int) (cy + baseA));
        } else {
            p.addPoint((int) (cx + tipA), (int) cy);
            p.addPoint((int) (cx + baseA), (int) (cy - half));
            p.addPoint((int) (cx + baseA), (int) (cy + half));
        }
        g.fillPolygon(p);
    }

    private static void chevrons(Graphics2D g, float cx, float cy, float r,
                                 boolean right, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(Math.max(2f, r * 0.16f), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        float dx = r * 0.34f;
        float h = r * 0.44f;
        for (int i = 0; i < 2; i++) {
            // Mirrors VirtualPadView.drawChevrons exactly (apex toward the
            // chevron direction: right=true -> » , right=false -> «).
            float off = (i == 0 ? -dx * 0.5f : dx * 0.6f) * (right ? 1 : -1);
            float base = cx + off + (right ? -dx * 0.45f : dx * 0.45f);
            float tip = cx + off - (right ? -dx * 0.45f : dx * 0.45f);
            g.drawLine((int) base, (int) (cy - h), (int) tip, (int) cy);
            g.drawLine((int) tip, (int) cy, (int) base, (int) (cy + h));
        }
    }

    private static void label(Graphics2D g, String s, float cx, float cy, float size,
                              Color color) {
        if (s == null || s.isEmpty()) {
            return;
        }
        g.setColor(color);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(size)));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2f,
                cy - (fm.getAscent() + fm.getDescent()) / 2f + fm.getAscent());
    }

    private static Color accentOf(int id) {
        switch (id) {
            case PadGeometry.ID_A: return ACCENT_A;
            case PadGeometry.ID_B: return ACCENT_B;
            case PadGeometry.ID_X: return ACCENT_X;
            case PadGeometry.ID_Y: return ACCENT_Y;
            default: return ACCENT_NEUTRAL;
        }
    }

    private static String labelOf(int id) {
        switch (id) {
            case PadGeometry.ID_A: return "A";
            case PadGeometry.ID_B: return "B";
            case PadGeometry.ID_X: return "X";
            case PadGeometry.ID_Y: return "Y";
            case PadGeometry.ID_LB: return "LB";
            case PadGeometry.ID_RB: return "RB";
            case PadGeometry.ID_LT: return "LT";
            case PadGeometry.ID_RT: return "RT";
            default: return "";
        }
    }

    private PadPreview() {
    }
}
