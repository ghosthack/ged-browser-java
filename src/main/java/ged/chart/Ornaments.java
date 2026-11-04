package ged.chart;

import ged.chart.ChartPage.Dot;
import ged.chart.ChartPage.Mark;
import ged.chart.ChartPage.Orn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Engraved-style decoration for the baroque theme: card frames with
/// concave corners, corner scrollwork, a crest or flag medallion (or, with
/// `--arms`, a shield or lozenge), the page border with its
/// arabesques, and flourishes for the title and column headings.
///
/// Each ornament is drawn once as path data in its own small coordinate
/// space and placed by [#place], which can mirror it, so the four corners
/// of a frame are the same scroll turned four ways. Paths use only
/// absolute M, L, C, Q and Z, which [PngChart] can replay with Java2D.
final class Ornaments {
    private Ornaments() {}

    // ---- the drawings ----------------------------------------------------------

    /// A pair of C-scrolls curling in from a corner, one along each edge.
    private static final String SCROLL_TOP = "M0 0 C7 0 15 1 18 5 C20 8 18 11 15 10.5 C12.5 10 12.8 7 15 7.3";
    private static final String SCROLL_SIDE = "M0 0 C0 7 1 15 5 18 C8 20 11 18 10.5 15 C10 12.5 7 12.8 7.3 15";
    /// A leaf pointing into the corner.
    private static final String LEAF = "M0 0 C4 1 8 4 9 9 C4 8 1 4 0 0 Z";

    /// Beside a flag medallion: a scroll dropping from the top edge and curling back, a tail along the rule.
    private static final String MEDALLION_CURL = "M0 3 C4 3 7 5 7.5 8 C8 10.5 5.5 11.5 4.3 10 C3.3 8.8 4.5 7.4 5.6 8.2";
    private static final String MEDALLION_TAIL = "M7 3.2 C14 2.4 22 4 34 2.9";
    private static final String LEAF_SMALL = "M0 -1 C3 -3.5 7 -3.5 9 -1.2 C6 0.2 3 0.4 0 -1 Z";

    /// Crest centre petal (filled) and one of its curling sides (stroked, mirrored for the other).
    private static final String PETAL = "M0 -6 C2.2 -3 2.2 0.5 0 3.5 C-2.2 0.5 -2.2 -3 0 -6 Z";
    private static final String CREST_CURL = "M0 2.5 C2.5 0 7 -1.5 9.5 0.5 C11.2 2 10 4.2 8.2 3.6 C7 3.2 7.2 1.8 8.3 1.9";
    private static final String CREST_TAIL = "M12 1.6 C17 0.6 23 2.4 32 1.2";

    /// Lozenge either side of the record id, with a tapering tail.
    private static final String LOZENGE = "M0 0 C3 -2.4 6 -2.4 9 0 C6 2.4 3 2.4 0 0 Z";
    private static final String LOZENGE_TAIL = "M11 0 C16 -1 22 1 30 0";

    /// Half of the title flourish, from the centre outwards.
    private static final String FLOURISH = "M7 0 C30 -5 55 5 80 0 C95 -3 108 -9 112 -4 C115 0 110 4 106 1.5 C104 0 105.5 -2 107.5 -1.5";
    private static final String FLOURISH_CURL = "M20 0.6 C26 4.5 34 6 40 3 C43 1.5 42 -1 40 -0.5";
    private static final String DIAMOND = "M0 -4.5 L4.5 0 L0 4.5 L-4.5 0 Z";

    /// A hairline volute: leaves the origin along +x and winds into a small spiral towards -y.
    private static final String VOLUTE = "M0 0 C2.6 0 4.6 -1.3 4.9 -3.2 C5.1 -4.7 3.9 -5.6 2.8 -5.1 C2 -4.7 2.1 -3.7 2.9 -3.7";

    /// A bead on the stem (long axis along y) and one of the fine curls beside it.
    private static final String BEAD = "M0 -2.4 L1.5 0 L0 2.4 L-1.5 0 Z";
    private static final String BEAD_CURL = "M1.8 0 C3.4 -0.2 5 -1.3 5.3 -2.8 C5.5 -3.9 4.5 -4.4 3.8 -3.8";

    /// Swash beside a column heading, heading outwards to the right.
    private static final String SWASH = "M0 0 C5 -2.5 10 2.5 16 0 C19 -1.2 20 -3.5 18 -4 C16.5 -4.3 16 -2.5 17 -2";

    /// The shape arms are displayed on: a shield for a man, a lozenge for a
    /// woman (the old heraldic custom), an oval cartouche when unknown.
    /// Outlines are drawn in a 0–1 box; `w`/`h` are the drawn size and
    /// `top` where it sits relative to a card's top edge.
    enum Escutcheon {
        SHIELD("M0 0 L1 0 L1 0.52 C1 0.78 0.76 0.92 0.5 1 C0.24 0.92 0 0.78 0 0.52 Z", 21, 25, -11),
        LOZENGE("M0.5 0 L1 0.5 L0.5 1 L0 0.5 Z", 28, 28, -13),
        OVAL("M0.5 0 C0.78 0 1 0.22 1 0.5 C1 0.78 0.78 1 0.5 1 C0.22 1 0 0.78 0 0.5 C0 0.22 0.22 0 0.5 0 Z", 24, 18, -8);

        private final String unit;
        private final double w, h, top;

        Escutcheon(String unit, double w, double h, double top) {
            this.unit = unit;
            this.w = w;
            this.h = h;
            this.top = top;
        }

        static Escutcheon of(ged.model.Person.Sex sex) {
            return switch (sex) { case MALE -> SHIELD; case FEMALE -> LOZENGE; default -> OVAL; };
        }

        String id() { return name().toLowerCase(Locale.ROOT); }
        double w() { return w; }
        double h() { return h; }
        double top() { return top; }

        /// The outline fitted to the box (x, y, w, h).
        String path(double x, double y, double w, double h) { return place(unit, x, y, w, h); }
    }

    // ---- placement ---------------------------------------------------------------

    /// `template` scaled by (sx, sy) — negative mirrors — then moved to (x, y).
    static String place(String template, double x, double y, double sx, double sy) {
        return map(template, (px, py) -> new double[] {x + px * sx, y + py * sy});
    }

    /// `template` scaled, turned by `angle` (radians, clockwise on screen), then moved to (x, y).
    static String turn(String template, double x, double y, double scale, double angle) {
        double c = Math.cos(angle) * scale, s = Math.sin(angle) * scale;
        return map(template, (px, py) -> new double[] {x + px * c - py * s, y + px * s + py * c});
    }

    private static String map(String template, java.util.function.BiFunction<Double, Double, double[]> f) {
        var out = new StringBuilder();
        Double pendingX = null;
        for (var tok : template.trim().split("\\s+|(?<=[A-Za-z])|(?=[A-Za-z])")) {
            if (tok.isEmpty()) continue;
            if (Character.isLetter(tok.charAt(0))) {
                if (!out.isEmpty()) out.append(' ');
                out.append(tok);
                continue;
            }
            double v = Double.parseDouble(tok);
            if (pendingX == null) {
                pendingX = v;
                continue;
            }
            var p = f.apply(pendingX, v);
            out.append(' ').append(num(p[0])).append(' ').append(num(p[1]));
            pendingX = null;
        }
        return out.toString();
    }

    private static String num(double v) {
        var s = String.format(Locale.ROOT, "%.2f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("-0") ? "0" : s;
    }

    /// A rectangle whose corners are scooped inwards (radius `r`): the classic cartouche.
    static String frame(double x, double y, double w, double h, double r) {
        double x2 = x + w, y2 = y + h;
        return "M" + num(x + r) + " " + num(y)
                + " L" + num(x2 - r) + " " + num(y) + " Q" + num(x2 - r) + " " + num(y + r) + " " + num(x2) + " " + num(y + r)
                + " L" + num(x2) + " " + num(y2 - r) + " Q" + num(x2 - r) + " " + num(y2 - r) + " " + num(x2 - r) + " " + num(y2)
                + " L" + num(x + r) + " " + num(y2) + " Q" + num(x + r) + " " + num(y2 - r) + " " + num(x) + " " + num(y2 - r)
                + " L" + num(x) + " " + num(y + r) + " Q" + num(x + r) + " " + num(y + r) + " " + num(x + r) + " " + num(y) + " Z";
    }

    /// Scrollwork in all four corners of the box (x, y, w, h), at `scale`.
    static void corners(List<Mark> out, double x, double y, double w, double h, double scale, String color, double strokeW) {
        double[][] at = {{x, y, 1, 1}, {x + w, y, -1, 1}, {x, y + h, 1, -1}, {x + w, y + h, -1, -1}};
        for (var c : at) corner(out, c[0], c[1], c[2] * scale, c[3] * scale, color, strokeW);
    }

    /// One corner's scrollwork at (x, y), opening towards (sx, sy).
    static void corner(List<Mark> out, double x, double y, double sx, double sy, String color, double strokeW) {
        out.add(new Orn(place(SCROLL_TOP, x, y, sx, sy), null, color, strokeW));
        out.add(new Orn(place(SCROLL_SIDE, x, y, sx, sy), null, color, strokeW));
        out.add(new Orn(place(LEAF, x, y, sx, sy), color, null, 0));
        out.add(new Dot(x + 12.6 * sx, y + 12.6 * sy, 0.9 * Math.abs(sx), color));
    }

    /// The small crest at the top of a card; the petal takes the person's colour.
    static void crest(List<Mark> out, double cx, double cy, String petal, String color) {
        out.add(new Orn(place(PETAL, cx, cy, 1, 1), petal, null, 0));
        for (int s : new int[] {1, -1}) {
            out.add(new Orn(place(CREST_CURL, cx, cy, s, 1), null, color, 0.9));
            out.add(new Orn(place(CREST_TAIL, cx, cy, s, 1), null, color, 0.7));
        }
    }

    /// Curls and tails running out along a card's top edge from a medallion `width` wide at `cx`.
    static void medallionCurls(List<Mark> out, double cx, double width, String color) {
        for (int s : new int[] {1, -1}) {
            double x = cx + s * (width / 2 + 3);
            out.add(new Orn(place(MEDALLION_CURL, x, 0, s, 1), null, color, 0.9));
            out.add(new Orn(place(MEDALLION_TAIL, x, 0, s, 1), null, color, 0.7));
            out.add(new Orn(place(LEAF_SMALL, x, 0, s, 1), color, null, 0));
        }
    }

    /// Lozenges and tails either side of a gap `gap` wide centred on (cx, cy).
    static void plate(List<Mark> out, double cx, double cy, double gap, String color) {
        for (int s : new int[] {1, -1}) {
            double x = cx + s * gap / 2;
            out.add(new Orn(place(LOZENGE, x, cy, s, 1), color, null, 0));
            out.add(new Orn(place(LOZENGE_TAIL, x, cy, s, 1), null, color, 0.7));
        }
    }

    /// The title flourish, centred on (cx, cy).
    static void flourish(List<Mark> out, double cx, double cy, String color) {
        out.add(new Orn(place(DIAMOND, cx, cy, 1, 1), color, null, 0));
        for (int s : new int[] {1, -1}) {
            out.add(new Orn(place(FLOURISH, cx, cy, s, 1), null, color, 1.1));
            out.add(new Orn(place(FLOURISH_CURL, cx, cy, s, 1), null, color, 0.8));
            out.add(new Dot(cx + s * 12, cy, 1.2, color));
        }
    }

    /// Swashes either side of a heading `width` wide, centred on (cx, cy).
    static void swashes(List<Mark> out, double cx, double cy, double width, String color) {
        for (int s : new int[] {1, -1}) out.add(new Orn(place(SWASH, cx + s * (width / 2 + 6), cy, s, 1), null, color, 0.9));
    }

    /// A bead on the stem at (x, y), the stem running at `angle`, with a curl either side.
    static void knot(List<Mark> out, double x, double y, double angle, String color) {
        out.add(new Orn(turn(BEAD, x, y, 1.25, angle), color, null, 0));
        out.add(new Orn(turn(BEAD_CURL, x, y, 1, angle), null, color, 0.6));
        out.add(new Orn(turn(place(BEAD_CURL, 0, 0, -1, 1), x, y, 1, angle), null, color, 0.6));   // mirrored across the stem
    }

    /// Fine ornament on an elbow connector from (x1, y1) to (x2, y2),
    /// turning at `mx` with corner radius `r`: a bead where the line meets
    /// the stem, a hairline volute on the outside of the bend into each
    /// card, and a bead with curls halfway down a long run. `seen` drops
    /// ornaments a sibling's connector already drew.
    static void vine(List<Mark> out, double x1, double y1, double x2, double y2, double r, double mx,
                     java.util.Set<String> seen, String color) {
        double sx = Math.signum(x2 - x1), sy = Math.signum(y2 - y1);
        if (sy == 0) {
            if (seen.add("m" + Math.round(mx) + ":" + Math.round(y1))) knot(out, mx, y1, Math.PI / 2, color);
            return;
        }
        if (seen.add("j" + Math.round(mx) + ":" + Math.round(y1))) {
            out.add(new Orn(turn(BEAD, mx, y1, 1.1, Math.PI / 2), color, null, 0));
        }
        if (seen.add("b" + Math.round(mx) + ":" + Math.round(y2))) {
            // On the outside of the bend, winding away from the card it leads to.
            double bx = mx + 0.29 * r * sx, by = y2 - 0.29 * r * sy;
            var v = place(VOLUTE, 0, 0, 1, sx * sy > 0 ? 1 : -1);
            out.add(new Orn(turn(v, bx, by, 0.95, Math.atan2(sy, -sx)), null, color, 0.6));
        }
        if (Math.abs(y2 - y1) > 60) {
            double ym = (y1 + y2) / 2;
            if (seen.add("v" + Math.round(mx) + ":" + Math.round(ym))) knot(out, mx, ym, 0, color);
        }
    }

    // ---- sephirot: engraved plate --------------------------------------------------

    private static final double K = 0.5523;   // cubic Bézier handle for a quarter circle

    /// A full circle as four cubic arcs.
    static String circle(double cx, double cy, double r) {
        double k = K * r;
        return "M" + num(cx + r) + " " + num(cy)
                + " C" + num(cx + r) + " " + num(cy + k) + " " + num(cx + k) + " " + num(cy + r) + " " + num(cx) + " " + num(cy + r)
                + " C" + num(cx - k) + " " + num(cy + r) + " " + num(cx - r) + " " + num(cy + k) + " " + num(cx - r) + " " + num(cy)
                + " C" + num(cx - r) + " " + num(cy - k) + " " + num(cx - k) + " " + num(cy - r) + " " + num(cx) + " " + num(cy - r)
                + " C" + num(cx + k) + " " + num(cy - r) + " " + num(cx + r) + " " + num(cy - k) + " " + num(cx + r) + " " + num(cy) + " Z";
    }

    /// The lower half of a circle, closed along its diameter.
    static String lowerHalf(double cx, double cy, double r) {
        double k = K * r;
        return "M" + num(cx + r) + " " + num(cy)
                + " C" + num(cx + r) + " " + num(cy + k) + " " + num(cx + k) + " " + num(cy + r) + " " + num(cx) + " " + num(cy + r)
                + " C" + num(cx - k) + " " + num(cy + r) + " " + num(cx - r) + " " + num(cy + k) + " " + num(cx - r) + " " + num(cy) + " Z";
    }

    /// A short line from radius `r1` to `r2` at `angle` (radians, clockwise from 3 o'clock) about (cx, cy).
    static String spoke(double cx, double cy, double r1, double r2, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return "M" + num(cx + r1 * c) + " " + num(cy + r1 * s) + " L" + num(cx + r2 * c) + " " + num(cy + r2 * s);
    }

    /// The old planetary signs for man and woman, ♂ (Mars) and ♀ (Venus), drawn as lines around a ring at (x, y).
    static void sexSign(List<Mark> out, double x, double y, ged.model.Person.Sex sex, String color) {
        double r = 3.6;
        switch (sex) {
            case MALE -> {
                double cx = x - 1.2, cy = y + 1.2, d = r * 0.7071, tx = cx + d + 5.2, ty = cy - d - 5.2;
                out.add(new Orn(circle(cx, cy, r), null, color, 1.1));
                out.add(new Orn("M" + num(cx + d) + " " + num(cy - d) + " L" + num(tx) + " " + num(ty)
                        + " M" + num(tx - 3.6) + " " + num(ty) + " L" + num(tx) + " " + num(ty) + " L" + num(tx) + " " + num(ty + 3.6),
                        null, color, 1.1));
            }
            case FEMALE -> {
                double cy = y - 2.2;
                out.add(new Orn(circle(x, cy, r), null, color, 1.1));
                out.add(new Orn("M" + num(x) + " " + num(cy + r) + " L" + num(x) + " " + num(cy + r + 6.4)
                        + " M" + num(x - 2.8) + " " + num(cy + r + 3.2) + " L" + num(x + 2.8) + " " + num(cy + r + 3.2),
                        null, color, 1.1));
            }
            default -> out.add(new Orn(circle(x, y, 2.4), null, color, 1.1));
        }
    }

    /// The emblem on the plate's title: a disc, its lower half dark, between two fine rules.
    static void horizon(List<Mark> out, double cx, double cy, double r, double rule, String color, String dark) {
        out.add(new Orn(lowerHalf(cx, cy, r), dark, null, 0));
        out.add(new Orn(circle(cx, cy, r), null, color, 1.1));
        out.add(new Orn("M" + num(cx - r) + " " + num(cy) + " L" + num(cx + r) + " " + num(cy), null, color, 0.6));
        if (rule > 0) {
            for (int s : new int[] {1, -1}) {
                out.add(new Orn("M" + num(cx + s * (r + 6)) + " " + num(cy) + " L" + num(cx + s * (r + 6 + rule)) + " " + num(cy), null, color, 0.7));
                out.add(new Dot(cx + s * (r + 10 + rule), cy, 1.3, color));
            }
        }
    }

    /// A plain double rule just inside a (w × h) page: the edge of an engraved plate.
    static List<Mark> plateEdge(double w, double h, String color) {
        var out = new ArrayList<Mark>();
        out.add(new Orn(frame(8, 8, w - 16, h - 16, 0), null, color, 1.4));
        out.add(new Orn(frame(12.5, 12.5, w - 25, h - 25, 0), null, color, 0.5));
        return out;
    }

    /// A double-ruled page border with arabesques in the corners, inside a (w × h) page.
    static List<Mark> border(double w, double h, String color) {
        var out = new ArrayList<Mark>();
        out.add(new Orn(frame(8, 8, w - 16, h - 16, 0), null, color, 1.6));
        out.add(new Orn(frame(13, 13, w - 26, h - 26, 0), null, color, 0.6));
        corners(out, 17, 17, w - 34, h - 34, 1.9, color, 1.0);
        return out;
    }
}
