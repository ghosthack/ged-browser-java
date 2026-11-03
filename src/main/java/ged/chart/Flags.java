package ged.chart;

import ged.model.Genealogy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Country flags for birthplaces, worked out from the place text itself
/// (`Rosario, Santa Fe, Argentina` → Argentina), and drawn as simple
/// shapes rather than emoji: emoji flags show as letters on Windows and
/// can't be drawn into a PNG by Java2D.
///
/// Flags are drawn on an 18 × 12 grid. They're simplified (no stars on
/// the US canton, a stylised maple leaf), which is plenty at card size.
public final class Flags {
    private Flags() {}

    public static final double W = 18, H = 12;

    /// A filled shape: `rect` (x y w h), `circle` (cx cy r) or `poly` (x1 y1 x2 y2 ...).
    public record Shape(String kind, double[] v, String color) {}

    public record Flag(String code, String country, List<Shape> shapes) {}

    // Names as written in English, Spanish and Italian records, accent-folded and lower-case.
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("argentina", "ar"), Map.entry("republica argentina", "ar"),
            Map.entry("italia", "it"), Map.entry("italy", "it"), Map.entry("regno d'italia", "it"),
            Map.entry("espana", "es"), Map.entry("spain", "es"), Map.entry("espanya", "es"),
            Map.entry("ireland", "ie"), Map.entry("irlanda", "ie"), Map.entry("eire", "ie"),
            Map.entry("mexico", "mx"), Map.entry("austria", "at"), Map.entry("osterreich", "at"),
            Map.entry("cuba", "cu"),
            Map.entry("united states", "us"), Map.entry("united states of america", "us"), Map.entry("usa", "us"),
            Map.entry("u.s.a.", "us"), Map.entry("u.s.a", "us"), Map.entry("us", "us"), Map.entry("estados unidos", "us"),
            Map.entry("estados unidos de america", "us"), Map.entry("eeuu", "us"), Map.entry("ee.uu.", "us"),
            Map.entry("canada", "ca"),
            Map.entry("scotland", "sct"), Map.entry("escocia", "sct"), Map.entry("scozia", "sct"),
            Map.entry("england", "eng"), Map.entry("inglaterra", "eng"), Map.entry("inghilterra", "eng"),
            Map.entry("france", "fr"), Map.entry("francia", "fr"),
            Map.entry("germany", "de"), Map.entry("alemania", "de"), Map.entry("deutschland", "de"), Map.entry("germania", "de"),
            Map.entry("portugal", "pt"), Map.entry("portogallo", "pt"),
            Map.entry("netherlands", "nl"), Map.entry("holanda", "nl"), Map.entry("paises bajos", "nl"), Map.entry("olanda", "nl"),
            Map.entry("brasil", "br"), Map.entry("brazil", "br"), Map.entry("brasile", "br"),
            Map.entry("chile", "cl"), Map.entry("cile", "cl"));

    // Places recorded without a country, unambiguous enough to place.
    private static final Map<String, String> REGIONS = Map.ofEntries(
            Map.entry("entre rios", "ar"), Map.entry("capital federal", "ar"), Map.entry("caba", "ar"),
            Map.entry("ciudad autonoma de buenos aires", "ar"), Map.entry("provincia de buenos aires", "ar"),
            Map.entry("glasgow", "sct"), Map.entry("edinburgh", "sct"), Map.entry("edimburgo", "sct"),
            Map.entry("calabria", "it"), Map.entry("sicilia", "it"), Map.entry("campania", "it"),
            Map.entry("liguria", "it"), Map.entry("piemonte", "it"), Map.entry("napoli", "it"), Map.entry("genova", "it"),
            Map.entry("galicia", "es"), Map.entry("asturias", "es"),
            Map.entry("california", "us"), Map.entry("new york", "us"), Map.entry("texas", "us"), Map.entry("florida", "us"));

    /// The flag for a place, looking at its parts from the most general (last) inwards.
    public static Optional<Flag> forPlace(String place) {
        var parts = place.split("\\s*,\\s*");
        for (var map : List.of(NAMES, REGIONS)) {
            for (int i = parts.length - 1; i >= 0; i--) {
                var code = map.get(Genealogy.fold(parts[i]).strip());
                if (code != null) return Optional.of(flag(code));
            }
        }
        return Optional.empty();
    }

    private static final String WHITE = "#ffffff";

    static Flag flag(String code) {
        var s = new ArrayList<Shape>();
        String name = switch (code) {
            case "ar" -> { hStripes(s, "#74acdf", WHITE, "#74acdf"); s.add(circle(9, 6, 1.7, "#f6b40e")); yield "Argentina"; }
            case "it" -> { vStripes(s, "#009246", WHITE, "#ce2b37"); yield "Italia"; }
            case "es" -> { s.add(rect(0, 0, 18, 12, "#aa151b")); s.add(rect(0, 3, 18, 6, "#f1bf00")); yield "España"; }
            case "ie" -> { vStripes(s, "#169b62", WHITE, "#ff883e"); yield "Ireland"; }
            case "mx" -> { vStripes(s, "#006847", WHITE, "#ce1126"); s.add(circle(9, 6, 1.6, "#8c6239")); yield "México"; }
            case "at" -> { hStripes(s, "#ed2939", WHITE, "#ed2939"); yield "Austria"; }
            case "fr" -> { vStripes(s, "#0055a4", WHITE, "#ef4135"); yield "France"; }
            case "de" -> { hStripes(s, "#000000", "#dd0000", "#ffce00"); yield "Deutschland"; }
            case "nl" -> { hStripes(s, "#ae1c28", WHITE, "#21468b"); yield "Nederland"; }
            case "pt" -> {
                s.add(rect(0, 0, 18, 12, "#da291c")); s.add(rect(0, 0, 7.2, 12, "#046a38"));
                s.add(circle(7.2, 6, 2.2, "#ffe900")); yield "Portugal";
            }
            case "cu" -> {
                for (int i = 0; i < 5; i++) s.add(rect(0, i * 2.4, 18, 2.4, i % 2 == 0 ? "#002a8f" : WHITE));
                s.add(poly("#cf142b", 0, 0, 7.8, 6, 0, 12)); s.add(star(2.6, 6, 1.5)); yield "Cuba";
            }
            case "cl" -> {
                s.add(rect(0, 0, 18, 6, WHITE)); s.add(rect(0, 6, 18, 6, "#d52b1e"));
                s.add(rect(0, 0, 6, 6, "#0039a6")); s.add(star(3, 3, 1.6)); yield "Chile";
            }
            case "br" -> {
                s.add(rect(0, 0, 18, 12, "#009c3b")); s.add(poly("#ffdf00", 9, 1.3, 16.6, 6, 9, 10.7, 1.4, 6));
                s.add(circle(9, 6, 2.6, "#002776")); yield "Brasil";
            }
            case "us" -> {
                for (int i = 0; i < 13; i++) s.add(rect(0, i * 12.0 / 13, 18, 12.0 / 13 + 0.01, i % 2 == 0 ? "#b22234" : WHITE));
                s.add(rect(0, 0, 7.6, 12.0 * 7 / 13, "#3c3b6e"));
                for (int r = 0; r < 3; r++) for (int c = 0; c < 4; c++) s.add(circle(1.1 + c * 1.8, 1.2 + r * 2.1, 0.38, WHITE));
                yield "United States";
            }
            case "ca" -> {
                s.add(rect(0, 0, 18, 12, WHITE)); s.add(rect(0, 0, 4.5, 12, "#d52b1e")); s.add(rect(13.5, 0, 4.5, 12, "#d52b1e"));
                s.add(poly("#d52b1e", 9, 1.8, 9.8, 3.3, 10.9, 2.9, 10.5, 5, 12, 4.2, 11.6, 5.4, 12.6, 5.6, 11, 7.2, 11.3, 8,
                        9.4, 7.7, 9.3, 9.9, 8.7, 9.9, 8.6, 7.7, 6.7, 8, 7, 7.2, 5.4, 5.6, 6.4, 5.4, 6, 4.2, 7.5, 5, 7.1, 2.9, 8.2, 3.3));
                yield "Canada";
            }
            case "sct" -> {
                s.add(rect(0, 0, 18, 12, "#005eb8"));
                s.add(poly(WHITE, 0, 0, 1.8, 0, 18, 10.8, 18, 12, 16.2, 12, 0, 1.2));
                s.add(poly(WHITE, 18, 0, 18, 1.2, 1.8, 12, 0, 12, 0, 10.8, 16.2, 0));
                yield "Scotland";
            }
            case "eng" -> {
                s.add(rect(0, 0, 18, 12, WHITE)); s.add(rect(7.6, 0, 2.8, 12, "#ce1124")); s.add(rect(0, 4.6, 18, 2.8, "#ce1124"));
                yield "England";
            }
            default -> throw new IllegalArgumentException(code);
        };
        return new Flag(code, name, List.copyOf(s));
    }

    private static void hStripes(List<Shape> s, String a, String b, String c) {
        s.add(rect(0, 0, 18, 4, a)); s.add(rect(0, 4, 18, 4, b)); s.add(rect(0, 8, 18, 4, c));
    }

    private static void vStripes(List<Shape> s, String a, String b, String c) {
        s.add(rect(0, 0, 6, 12, a)); s.add(rect(6, 0, 6, 12, b)); s.add(rect(12, 0, 6, 12, c));
    }

    private static Shape rect(double x, double y, double w, double h, String color) {
        return new Shape("rect", new double[] {x, y, w, h}, color);
    }

    private static Shape circle(double cx, double cy, double r, String color) {
        return new Shape("circle", new double[] {cx, cy, r}, color);
    }

    private static Shape poly(String color, double... xy) {
        return new Shape("poly", xy, color);
    }

    private static Shape star(double cx, double cy, double r) {
        var xy = new double[20];
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5, rr = i % 2 == 0 ? r : r * 0.4;
            xy[2 * i] = cx + rr * Math.cos(a);
            xy[2 * i + 1] = cy + rr * Math.sin(a);
        }
        return new Shape("poly", xy, WHITE);
    }
}
