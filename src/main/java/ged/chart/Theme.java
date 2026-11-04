package ged.chart;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Look of a chart: card geometry, fonts, and colour palettes. `classic`
/// is the original warm paper look (light only); `modern` adds initials
/// avatars, large flags, ages, record ids, and a dark palette; `baroque`
/// is a 19th-century engraved look on parchment, with framed cards,
/// scrollwork and a bordered page (see [Ornaments]), and a candlelit dark;
/// `sephirot` draws the tree as an old engraved plate, ivory on black, with
/// round medallions joined by channels, ancestors rising to the top (see
/// [Sephirot]).
///
/// Palettes map colour keys (used by [ChartPage] marks and the HTML page
/// chrome) to hex colours. In HTML each key becomes a CSS variable
/// `--c-KEY`, so the page can switch light/dark without re-rendering.
public record Theme(String name, double cardW, double cardH, double stack, double gap, double colGap,
                    double tieX, double radius, Map<String, Font> fonts,
                    Map<String, String> light, Map<String, String> dark) {

    /// A text style. `java` lists font families to try for PNG, in order.
    public record Font(String css, List<String> java, int weight, double size, double tracking, boolean upper, String color,
                       boolean italic) {
        public Font(String css, List<String> java, int weight, double size, double tracking, boolean upper, String color) {
            this(css, java, weight, size, tracking, upper, color, false);
        }

        public String cssFont() {
            return (italic ? "italic " : "") + weight + " " + ChartPage.n(size) + "px " + css;
        }
    }

    public boolean hasDark() { return dark != null; }
    public boolean modern() { return name.equals("modern"); }
    /// A decorated page: bordered, with a floating title plaque and controls.
    public boolean ornate() { return baroque() || sephirot(); }
    public boolean baroque() { return name.equals("baroque"); }
    public boolean sephirot() { return name.equals("sephirot"); }
    /// Opens dark (the HTML can still switch; `--light` for pictures).
    public boolean startsDark() { return sephirot(); }
    /// Timelines need the tinted palette and id/age styles classic lacks; sephirot is a tree only.
    public boolean hasTimeline() { return light.containsKey("maleBg") && fonts.containsKey("id") && !sephirot(); }
    public double pitch() { return cardW + colGap; }

    public Font font(String style) {
        var f = fonts.get(style);
        if (f == null) throw new IllegalArgumentException("no font for " + style);
        return f;
    }

    public Map<String, String> palette(boolean darkMode) {
        return darkMode && dark != null ? dark : light;
    }

    public static Theme of(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "classic" -> CLASSIC;
            case "modern" -> MODERN;
            case "baroque" -> BAROQUE;
            case "sephirot" -> SEPHIROT;
            default -> throw new IllegalArgumentException("unknown theme '" + name + "' (use modern, classic, baroque or sephirot)");
        };
    }

    // ---- fonts -------------------------------------------------------------

    static final String SERIF_CSS = "'Iowan Old Style','Palatino Linotype',Palatino,Georgia,serif";
    static final String SANS_CSS = "-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif";
    static final String MONO_CSS = "ui-monospace,'SF Mono',Menlo,Consolas,'DejaVu Sans Mono',monospace";
    static final List<String> SERIF = List.of("Iowan Old Style", "Palatino Linotype", "Palatino", "Georgia", "Serif");
    static final List<String> SANS = List.of("Helvetica Neue", "Segoe UI", "Roboto", "Arial", "SansSerif");
    static final List<String> MONO = List.of("SF Mono", "Menlo", "Consolas", "DejaVu Sans Mono", "Monospaced");

    private static Font serif(int w, double size, String color) { return new Font(SERIF_CSS, SERIF, w, size, 0, false, color); }
    private static Font sans(int w, double size, String color) { return new Font(SANS_CSS, SANS, w, size, 0, false, color); }

    // ---- classic -----------------------------------------------------------

    public static final Theme CLASSIC = new Theme("classic", 236, 84, 8, 14, 64, 22, 8,
            Map.of(
                    "title", serif(700, 22, "ink"),
                    "sub", sans(400, 11, "pl"),
                    "col", new Font(SANS_CSS, SANS, 600, 12, 0.08, true, "col"),
                    "nm", serif(700, 15, "ink"),
                    "yr", sans(500, 12.5, "yr"),
                    "pl", sans(400, 11, "pl"),
                    "tag", sans(600, 11.5, "tag")),
            Map.ofEntries(
                    Map.entry("paper", "#faf7f2"), Map.entry("ink", "#2b2620"), Map.entry("muted", "#7a705f"),
                    Map.entry("line", "#e3dccf"), Map.entry("panel", "#ffffff"), Map.entry("btn", "#ffffff"),
                    Map.entry("btnLine", "#d6cdbb"), Map.entry("btnHover", "#f3eee4"), Map.entry("href", "#3d6fa8"),
                    Map.entry("card", "#ffffff"), Map.entry("cardLine", "#ddd5c6"), Map.entry("hover", "#8a7f6c"),
                    Map.entry("rootFill", "#fff7df"), Map.entry("repeatFill", "#fbfaf7"), Map.entry("accent", "#c9a227"),
                    Map.entry("link", "#b4aa98"), Map.entry("col", "#8a7f6c"),
                    Map.entry("yr", "#4f473c"), Map.entry("pl", "#857b6b"), Map.entry("tag", "#a8861a"),
                    Map.entry("male", "#3d6fa8"), Map.entry("female", "#b8506f"), Map.entry("unknown", "#9b9385"),
                    Map.entry("flagLine", "#000000")),
            null);

    // ---- modern ------------------------------------------------------------

    public static final Theme MODERN = new Theme("modern", 268, 92, 20, 14, 60, 34, 12,
            Map.of(
                    "title", serif(700, 24, "ink"),
                    "sub", sans(400, 11.5, "pl"),
                    "col", new Font(SANS_CSS, SANS, 650, 11.5, 0.12, true, "col"),
                    "nm", serif(700, 15.5, "ink"),
                    "yr", sans(550, 12.5, "yr"),
                    "age", sans(400, 12, "age"),
                    "pl", sans(400, 11, "pl"),
                    "tag", sans(650, 10.5, "tag"),
                    "id", new Font(MONO_CSS, MONO, 600, 10, 0, false, "idText"),
                    "ini", sans(650, 13, "ink")),
            Map.ofEntries(
                    Map.entry("paper", "#f4f2ee"), Map.entry("ink", "#1f1c18"), Map.entry("muted", "#7d7568"),
                    Map.entry("line", "#e2ddd3"), Map.entry("panel", "#ffffff"), Map.entry("btn", "#ffffff"),
                    Map.entry("btnLine", "#d9d3c8"), Map.entry("btnHover", "#efebe4"), Map.entry("href", "#2f5f9e"),
                    Map.entry("card", "#ffffff"), Map.entry("cardLine", "#e2ddd3"), Map.entry("hover", "#a39a8b"),
                    Map.entry("rootFill", "#fdf6e3"), Map.entry("repeatFill", "#faf9f7"), Map.entry("accent", "#b8892b"),
                    Map.entry("link", "#c6beb0"), Map.entry("col", "#8f8576"),
                    Map.entry("yr", "#3d3830"), Map.entry("age", "#8a8173"), Map.entry("pl", "#7b7366"),
                    Map.entry("tag", "#8a6716"), Map.entry("tagBg", "#f6edd5"),
                    Map.entry("idText", "#6b6458"), Map.entry("idBg", "#f0ece5"),
                    Map.entry("male", "#2f5f9e"), Map.entry("female", "#a3405f"), Map.entry("unknown", "#6e675c"),
                    Map.entry("maleBg", "#dce7f5"), Map.entry("femaleBg", "#f6dde5"), Map.entry("unknownBg", "#e8e5df"),
                    Map.entry("flagLine", "#000000")),
            Map.ofEntries(
                    Map.entry("paper", "#141619"), Map.entry("ink", "#f1eee8"), Map.entry("muted", "#9a9386"),
                    Map.entry("line", "#2a2e34"), Map.entry("panel", "#1d2025"), Map.entry("btn", "#1f2227"),
                    Map.entry("btnLine", "#363a42"), Map.entry("btnHover", "#2a2e35"), Map.entry("href", "#8fb8ec"),
                    Map.entry("card", "#1f2227"), Map.entry("cardLine", "#33373e"), Map.entry("hover", "#6b717b"),
                    Map.entry("rootFill", "#2b261b"), Map.entry("repeatFill", "#191b1f"), Map.entry("accent", "#e0b354"),
                    Map.entry("link", "#4a5059"), Map.entry("col", "#8f887d"),
                    Map.entry("yr", "#d3cdc3"), Map.entry("age", "#8f887d"), Map.entry("pl", "#9b9488"),
                    Map.entry("tag", "#e8c46f"), Map.entry("tagBg", "#3a3220"),
                    Map.entry("idText", "#b9b2a6"), Map.entry("idBg", "#2c3036"),
                    Map.entry("male", "#9cc2f0"), Map.entry("female", "#f0a7c0"), Map.entry("unknown", "#c4beb4"),
                    Map.entry("maleBg", "#233449"), Map.entry("femaleBg", "#47283a"), Map.entry("unknownBg", "#34373c"),
                    Map.entry("flagLine", "#ffffff")));

    // ---- baroque -----------------------------------------------------------

    static final String OLD_CSS = "'Hoefler Text','Big Caslon',Baskerville,'Baskerville Old Face','Palatino Linotype',Palatino,Georgia,serif";
    static final List<String> OLD = List.of("Hoefler Text", "Big Caslon", "Baskerville", "Baskerville Old Face", "Palatino Linotype", "Palatino", "Georgia", "Serif");
    static final String SCRIPT_CSS = "'Snell Roundhand','Apple Chancery','Edwardian Script ITC','Palatino Linotype',Palatino,Georgia,serif";
    static final List<String> SCRIPT = List.of("Snell Roundhand", "Apple Chancery", "Edwardian Script ITC", "Palatino Linotype", "Palatino", "Serif");

    private static Font old(int w, double size, String color, boolean italic) {
        return new Font(OLD_CSS, OLD, w, size, 0, false, color, italic);
    }

    public static final Theme BAROQUE = new Theme("baroque", 272, 108, 24, 18, 76, 30, 0,
            Map.ofEntries(
                    Map.entry("title", new Font(SCRIPT_CSS, SCRIPT, 700, 34, 0, false, "ink", true)),
                    Map.entry("sub", old(400, 12.5, "pl", true)),
                    Map.entry("col", new Font(OLD_CSS, OLD, 600, 11, 0.22, true, "col")),
                    Map.entry("nm", old(600, 16.5, "ink", false)),
                    Map.entry("yr", old(400, 13, "yr", true)),
                    Map.entry("age", old(400, 12.5, "age", true)),
                    Map.entry("pl", old(400, 11.5, "pl", true)),
                    Map.entry("tag", old(600, 12, "tag", true)),
                    Map.entry("id", new Font(OLD_CSS, OLD, 600, 9.5, 0.12, true, "idText")),
                    Map.entry("ini", old(600, 13, "ink", false))),
            Map.ofEntries(
                    Map.entry("paper", "#f2e6cc"), Map.entry("ink", "#2e1f12"), Map.entry("muted", "#7a6247"),
                    Map.entry("line", "#dcc9a3"), Map.entry("panel", "#fbf3df"), Map.entry("btn", "#fbf3df"),
                    Map.entry("btnLine", "#c9b083"), Map.entry("btnHover", "#efe2c4"), Map.entry("href", "#7a2e22"),
                    Map.entry("card", "#fbf4e1"), Map.entry("cardLine", "#7d5e30"), Map.entry("hover", "#5a3f1c"),
                    Map.entry("rootFill", "#f8ebc7"), Map.entry("repeatFill", "#f5ecd8"), Map.entry("accent", "#8b2e23"),
                    Map.entry("orn", "#9a7433"), Map.entry("link", "#a88b5e"), Map.entry("col", "#7d5e30"),
                    Map.entry("yr", "#4a3520"), Map.entry("age", "#7a6247"), Map.entry("pl", "#6e573c"),
                    Map.entry("tag", "#8b2e23"), Map.entry("tagBg", "#f2e6cc"),
                    Map.entry("idText", "#8a7050"), Map.entry("idBg", "#fbf4e1"),
                    Map.entry("male", "#2f4a6b"), Map.entry("female", "#8b2e3f"), Map.entry("unknown", "#6b5a45"),
                    Map.entry("maleBg", "#d9dcd2"), Map.entry("femaleBg", "#ecd6c8"), Map.entry("unknownBg", "#e4d8c0"),
                    Map.entry("flagLine", "#2e1f12")),
            Map.ofEntries(
                    Map.entry("paper", "#17120c"), Map.entry("ink", "#efe3c8"), Map.entry("muted", "#a8946f"),
                    Map.entry("line", "#3a2e1e"), Map.entry("panel", "#221a11"), Map.entry("btn", "#241b12"),
                    Map.entry("btnLine", "#4d3c25"), Map.entry("btnHover", "#30251a"), Map.entry("href", "#e3b97a"),
                    Map.entry("card", "#211910"), Map.entry("cardLine", "#a8823f"), Map.entry("hover", "#e0c48a"),
                    Map.entry("rootFill", "#2e2312"), Map.entry("repeatFill", "#1c150e"), Map.entry("accent", "#d9a85b"),
                    Map.entry("orn", "#c9a45a"), Map.entry("link", "#6e5a3a"), Map.entry("col", "#c9a45a"),
                    Map.entry("yr", "#dccdaa"), Map.entry("age", "#a8946f"), Map.entry("pl", "#b09c78"),
                    Map.entry("tag", "#e0b46a"), Map.entry("tagBg", "#17120c"),
                    Map.entry("idText", "#a8946f"), Map.entry("idBg", "#211910"),
                    Map.entry("male", "#9fb6d3"), Map.entry("female", "#dba3ac"), Map.entry("unknown", "#c4b49a"),
                    Map.entry("maleBg", "#2a2d2c"), Map.entry("femaleBg", "#3a2522"), Map.entry("unknownBg", "#2f281d"),
                    Map.entry("flagLine", "#efe3c8")));

    // ---- sephirot ----------------------------------------------------------

    /// A plain engraver's roman with level figures, upright throughout.
    static final String PLAIN_CSS = "Baskerville,'Baskerville Old Face','Libre Baskerville','Times New Roman',Times,serif";
    static final List<String> PLAIN = List.of("Baskerville", "Baskerville Old Face", "Libre Baskerville", "Times New Roman", "Times", "Serif");

    private static Font caps(int w, double size, double tracking, String color) {
        return new Font(PLAIN_CSS, PLAIN, w, size, tracking, true, color);
    }

    private static Font plain(int w, double size, String color, boolean italic) {
        return new Font(PLAIN_CSS, PLAIN, w, size, 0, false, color, italic);
    }

    /// Card geometry is unused by [Sephirot] (medallions have their own), but kept sane.
    public static final Theme SEPHIROT = new Theme("sephirot", 144, 144, 0, 44, 0, 0, 0,
            Map.ofEntries(
                    Map.entry("title", caps(400, 25, 0.2, "ink")),
                    Map.entry("sub", plain(400, 12.5, "pl", true)),
                    Map.entry("col", caps(400, 9, 0.2, "col")),
                    Map.entry("ring", caps(400, 8, 0.14, "ring")),
                    Map.entry("nm", plain(400, 14.5, "ink", false)),
                    Map.entry("yr", plain(400, 11.5, "yr", false)),
                    Map.entry("age", plain(400, 10, "age", false)),
                    Map.entry("pl", plain(400, 10.5, "pl", false)),
                    Map.entry("tag", caps(400, 8, 0.12, "tag")),
                    Map.entry("chan", caps(400, 7, 0.18, "age")),
                    Map.entry("id", caps(400, 7.5, 0.12, "idText"))),
            Map.ofEntries(
                    Map.entry("paper", "#efe6d2"), Map.entry("ink", "#1c1712"), Map.entry("muted", "#6e6252"),
                    Map.entry("line", "#d6cab0"), Map.entry("panel", "#f6efdf"), Map.entry("btn", "#f6efdf"),
                    Map.entry("btnLine", "#a8987a"), Map.entry("btnHover", "#e9dfc8"), Map.entry("href", "#7a3b22"),
                    Map.entry("card", "#f6efdf"), Map.entry("cardLine", "#2a231b"), Map.entry("hover", "#7a3b22"),
                    Map.entry("rootFill", "#f3e6c8"), Map.entry("repeatFill", "#efe6d2"), Map.entry("accent", "#8a4a2a"),
                    Map.entry("orn", "#3a3026"), Map.entry("link", "#2a231b"), Map.entry("col", "#2a231b"),
                    Map.entry("ring", "#3a3026"), Map.entry("yr", "#2a231b"), Map.entry("age", "#5a4e40"),
                    Map.entry("pl", "#5a4e40"), Map.entry("tag", "#8a4a2a"), Map.entry("idText", "#6e6252"),
                    Map.entry("male", "#2f4a6b"), Map.entry("female", "#8b2e3f"), Map.entry("unknown", "#5a4e40"),
                    Map.entry("maleBg", "#d9dcd2"), Map.entry("femaleBg", "#ecd6c8"), Map.entry("unknownBg", "#e4d8c0"),
                    Map.entry("flagLine", "#1c1712")),
            Map.ofEntries(
                    Map.entry("paper", "#0b0a09"), Map.entry("ink", "#f1e7d2"), Map.entry("muted", "#a39780"),
                    Map.entry("line", "#2a2620"), Map.entry("panel", "#12100e"), Map.entry("btn", "#15130f"),
                    Map.entry("btnLine", "#4a4234"), Map.entry("btnHover", "#221e18"), Map.entry("href", "#e8b48e"),
                    Map.entry("card", "#0e0c0b"), Map.entry("cardLine", "#eadfc6"), Map.entry("hover", "#e8b48e"),
                    Map.entry("rootFill", "#1a140f"), Map.entry("repeatFill", "#0b0a09"), Map.entry("accent", "#e3a57c"),
                    Map.entry("orn", "#d8cbb0"), Map.entry("link", "#e3d7bd"), Map.entry("col", "#eadfc6"),
                    Map.entry("ring", "#e0d3b6"), Map.entry("yr", "#e6dbc3"), Map.entry("age", "#b3a68c"),
                    Map.entry("pl", "#b3a68c"), Map.entry("tag", "#e3a57c"), Map.entry("idText", "#a39780"),
                    Map.entry("male", "#a9c0dc"), Map.entry("female", "#e3aab4"), Map.entry("unknown", "#c9bca2"),
                    Map.entry("maleBg", "#1d2229"), Map.entry("femaleBg", "#2a1b1e"), Map.entry("unknownBg", "#1f1b16"),
                    Map.entry("flagLine", "#f1e7d2")));
}
