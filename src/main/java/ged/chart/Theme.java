package ged.chart;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Look of a chart: card geometry, fonts, and colour palettes. `classic`
/// is the original warm paper look (light only); `modern` adds initials
/// avatars, large flags, ages, record ids, and a dark palette.
///
/// Palettes map colour keys (used by [ChartPage] marks and the HTML page
/// chrome) to hex colours. In HTML each key becomes a CSS variable
/// `--c-KEY`, so the page can switch light/dark without re-rendering.
public record Theme(String name, double cardW, double cardH, double stack, double gap, double colGap,
                    double tieX, double radius, Map<String, Font> fonts,
                    Map<String, String> light, Map<String, String> dark) {

    /// A text style. `java` lists font families to try for PNG, in order.
    public record Font(String css, List<String> java, int weight, double size, double tracking, boolean upper, String color) {
        public String cssFont() {
            return weight + " " + ChartPage.n(size) + "px " + css;
        }
    }

    public boolean hasDark() { return dark != null; }
    public boolean modern() { return name.equals("modern"); }
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
            default -> throw new IllegalArgumentException("unknown theme '" + name + "' (use classic or modern)");
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
}
