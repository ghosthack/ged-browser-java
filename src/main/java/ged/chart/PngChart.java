package ged.chart;

import ged.chart.ChartPage.Bar;
import ged.chart.ChartPage.Box;
import ged.chart.ChartPage.Dot;
import ged.chart.ChartPage.FlagMark;
import ged.chart.ChartPage.Txt;
import ged.chart.Layout.Kind;
import ged.chart.Layout.Link;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// Paints the chart to a PNG with Java2D, from the same layout and card
/// marks as the SVG, at `scale` (2 = sharp on high-DPI screens and in print).
public final class PngChart {
    /// Above this many pixels the scale is reduced, to keep memory in check.
    static final long MAX_PIXELS = 60_000_000L;

    private final ChartPage page;
    private final Theme theme;
    private final Map<String, String> palette;
    private final Set<String> installed;
    private final Map<String, Font> fonts = new HashMap<>();

    public PngChart(ChartPage page, boolean dark) {
        this.page = page;
        this.theme = page.theme();
        this.palette = theme.palette(dark);
        this.installed = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
    }

    /// Scale actually used for a requested one, after the pixel cap.
    public double effectiveScale(double requested) {
        return Math.min(requested, Math.sqrt(MAX_PIXELS / (width() * height())));
    }

    private double width() { return Math.max(page.chart().width(), 520); }
    private double height() { return page.chart().height() + 64; }

    private Color color(String key) {
        var hex = palette.get(key);
        if (hex == null) throw new IllegalArgumentException("no colour " + key);
        return Color.decode(hex);
    }

    private Font font(String style) {
        return fonts.computeIfAbsent(style, s -> {
            var f = theme.font(s);
            var family = f.java().stream().filter(installed::contains).findFirst().orElse(f.java().getLast());
            var attrs = new HashMap<TextAttribute, Object>();
            attrs.put(TextAttribute.FAMILY, family);
            attrs.put(TextAttribute.SIZE, (float) f.size());
            attrs.put(TextAttribute.WEIGHT, f.weight() >= 600 ? TextAttribute.WEIGHT_BOLD
                    : f.weight() >= 500 ? TextAttribute.WEIGHT_MEDIUM : TextAttribute.WEIGHT_REGULAR);
            if (f.tracking() > 0) attrs.put(TextAttribute.TRACKING, (float) f.tracking());
            return new Font(attrs);
        });
    }

    public byte[] png(double requestedScale) throws IOException {
        double scale = effectiveScale(requestedScale);
        int w = (int) Math.ceil(width() * scale), h = (int) Math.ceil(height() * scale);
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.scale(scale, scale);
            g.setColor(color("paper"));
            g.fill(new Rectangle2D.Double(0, 0, width() + 1, height() + 1));

            g.setFont(font("title"));
            g.setColor(color(theme.font("title").color()));
            g.drawString(page.title(), (float) Layout.MARGIN, 38f);
            g.setFont(font("sub"));
            g.setColor(color(theme.font("sub").color()));
            g.drawString(page.subtitle(), (float) Layout.MARGIN, 58f);
            g.translate(0, 64);

            g.setFont(font("col"));
            g.setColor(color(theme.font("col").color()));
            for (var c : page.chart().columns()) {
                var label = theme.font("col").upper() ? c.label().toUpperCase(Locale.ROOT) : c.label();
                float tw = (float) g.getFontMetrics().getStringBounds(label, g).getWidth();
                g.drawString(label, (float) (c.centerX() - tw / 2), (float) (Layout.MARGIN + 10));
            }

            g.setColor(color("accent"));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {2, 2}, 0));
            for (var t : page.chart().ties()) g.draw(new Line2D.Double(t.x1(), t.y1(), t.x2(), t.y2()));

            g.setColor(color("link"));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            for (var k : page.chart().links()) g.draw(elbow(k));

            for (var c : page.chart().cards()) {
                var cg = (Graphics2D) g.create();
                try {
                    cg.translate(c.x(), c.y());
                    if (c.kind() == Kind.REPEAT) cg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f));
                    for (var m : page.marks(c)) paint(cg, m);
                } finally {
                    cg.dispose();
                }
            }
        } finally {
            g.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }

    private static Path2D elbow(Link k) {
        var p = new Path2D.Double();
        double mx = (k.x1() + k.x2()) / 2, dy = k.y2() - k.y1();
        p.moveTo(k.x1(), k.y1());
        if (Math.abs(dy) < 1) {
            p.lineTo(k.x2(), k.y1());
            return p;
        }
        double r = Math.min(10, Math.abs(dy) / 2), sx = Math.signum(k.x2() - k.x1()), sy = Math.signum(dy);
        p.lineTo(mx - r * sx, k.y1());
        p.quadTo(mx, k.y1(), mx, k.y1() + r * sy);
        p.lineTo(mx, k.y2() - r * sy);
        p.quadTo(mx, k.y2(), mx + r * sx, k.y2());
        p.lineTo(k.x2(), k.y2());
        return p;
    }

    private void paint(Graphics2D g, ChartPage.Mark m) {
        switch (m) {
            case Box b -> {
                var shape = new RoundRectangle2D.Double(b.x(), b.y(), b.w(), b.h(), 2 * b.r(), 2 * b.r());
                g.setColor(color(b.fill()));
                g.fill(shape);
                if (b.stroke() != null) {
                    g.setColor(color(b.stroke()));
                    g.setStroke(b.dashed()
                            ? new BasicStroke((float) b.strokeW(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {5, 4}, 0)
                            : new BasicStroke((float) b.strokeW()));
                    g.draw(shape);
                }
            }
            case Bar b -> {
                var p = new Path2D.Double();
                double r = b.r(), h = b.h();
                p.moveTo(r, 0);
                p.quadTo(0, 0, 0, r);
                p.lineTo(0, h - r);
                p.quadTo(0, h, r, h);
                p.closePath();
                g.setColor(color(b.fill()));
                g.fill(p);
            }
            case Dot d -> {
                g.setColor(color(d.fill()));
                g.fill(new Ellipse2D.Double(d.cx() - d.r(), d.cy() - d.r(), 2 * d.r(), 2 * d.r()));
            }
            case FlagMark f -> flag(g, f);
            case Txt t -> text(g, t);
        }
    }

    private void flag(Graphics2D g0, FlagMark f) {
        var g = (Graphics2D) g0.create();
        try {
            g.translate(f.x(), f.y());
            g.scale(f.w() / Flags.W, f.h() / Flags.H);
            double r = theme.modern() ? 1.6 : 0;
            g.clip(new RoundRectangle2D.Double(0, 0, Flags.W, Flags.H, 2 * r, 2 * r));
            for (var sh : f.flag().shapes()) {
                var v = sh.v();
                g.setColor(Color.decode(sh.color()));
                switch (sh.kind()) {
                    case "rect" -> g.fill(new Rectangle2D.Double(v[0], v[1], v[2], v[3]));
                    case "circle" -> g.fill(new Ellipse2D.Double(v[0] - v[2], v[1] - v[2], 2 * v[2], 2 * v[2]));
                    default -> {
                        var p = new Path2D.Double();
                        p.moveTo(v[0], v[1]);
                        for (int i = 2; i < v.length; i += 2) p.lineTo(v[i], v[i + 1]);
                        p.closePath();
                        g.fill(p);
                    }
                }
            }
            var line = color("flagLine");
            g.setColor(new Color(line.getRed(), line.getGreen(), line.getBlue(), 51));
            g.setStroke(new BasicStroke(0.6f));
            g.draw(new RoundRectangle2D.Double(0.3, 0.3, Flags.W - 0.6, Flags.H - 0.6, 2 * r, 2 * r));
        } finally {
            g.dispose();
        }
    }

    private void text(Graphics2D g, Txt t) {
        double total = 0;
        var widths = new double[t.runs().size()];
        for (int i = 0; i < widths.length; i++) {
            var r = t.runs().get(i);
            g.setFont(font(r.style()));
            widths[i] = g.getFontMetrics().getStringBounds(r.text(), g).getWidth();
            total += widths[i];
        }
        // Real metrics can differ from the estimate the layout used; squeeze rather than overflow.
        double target = t.squeezeTo() > 0 ? Math.min(t.squeezeTo(), total) : total;
        double start = switch (t.anchor()) {
            case "middle" -> t.x() - target / 2;
            case "end" -> t.x() - target;
            default -> t.x();
        };
        var saved = g.getTransform();
        g.translate(start, t.y());
        if (target < total - 0.5) g.transform(AffineTransform.getScaleInstance(target / total, 1));
        double x = 0;
        for (int i = 0; i < widths.length; i++) {
            var r = t.runs().get(i);
            g.setFont(font(r.style()));
            g.setColor(color(r.color() != null ? r.color() : theme.font(r.style()).color()));
            g.drawString(r.text(), (float) x, 0f);
            x += widths[i];
        }
        g.setTransform(saved);
    }
}
