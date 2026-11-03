package ged.chart;

import ged.chart.Layout.Card;
import ged.chart.Layout.Chart;
import ged.chart.Layout.Kind;
import ged.chart.Layout.Link;
import ged.model.Event;
import ged.model.Family;
import ged.model.GedDate;
import ged.model.Genealogy;
import ged.model.Person;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/// Renders a [Chart] as a standalone SVG, or as a single self-contained
/// HTML page (no external files or network) with zoom, drag-to-pan,
/// click-for-details, print-to-fit and, for themes that have one, a
/// light/dark switch.
///
/// What a card shows is decided once, as a list of [Mark]s in card
/// coordinates; the SVG here and [PngChart] only paint them.
public final class ChartPage {
    private final Genealogy g;
    private final Chart chart;
    private final Theme theme;
    private final Labels l;
    private final String title;
    private final boolean notes;
    private final LocalDate today;

    public ChartPage(Genealogy g, Chart chart, Labels labels, String title, boolean notes, LocalDate today) {
        this.g = g;
        this.chart = chart;
        this.theme = chart.theme();
        this.l = labels;
        this.title = title;
        this.notes = notes;
        this.today = today;
    }

    Chart chart() { return chart; }
    Theme theme() { return theme; }
    String title() { return title; }

    public String subtitle() {
        int gens = chart.up() + chart.down() + 1;
        return l.people(chart.peopleCount()) + " · " + l.generations(gens) + " · " + l.made(today);
    }

    // ---- marks ---------------------------------------------------------------

    /// Something drawn on a card, in card coordinates. Colours are palette keys.
    sealed interface Mark permits Box, Bar, Dot, Txt, FlagMark {}

    /// Rounded rectangle; `cls` tags the card background for hover/selection.
    record Box(double x, double y, double w, double h, double r, String fill, String stroke, double strokeW,
               boolean dashed, String cls) implements Mark {}

    /// Classic theme's coloured strip down the card's left edge.
    record Bar(double h, double r, String fill) implements Mark {}

    record Dot(double cx, double cy, double r, String fill) implements Mark {}

    /// A run of text in one style; `color` null means the style's own colour.
    record Run(String text, String style, String color) {}

    /// One line of text (`y` is the baseline). `anchor` is start, middle or
    /// end; `squeezeTo` > 0 compresses the line horizontally to that width.
    record Txt(List<Run> runs, double x, double y, String anchor, double squeezeTo) implements Mark {
        static Txt of(String text, String style, double x, double y) {
            return new Txt(List.of(new Run(text, style, null)), x, y, "start", 0);
        }
    }

    record FlagMark(Flags.Flag flag, double x, double y, double w, double h) implements Mark {}

    List<Mark> marks(Card c) {
        return theme.modern() ? modernMarks(c) : classicMarks(c);
    }

    private static String sexKey(Person p) {
        return switch (p.sex()) { case MALE -> "male"; case FEMALE -> "female"; default -> "unknown"; };
    }

    private Box background(Card c, double r) {
        boolean root = c.kind() == Kind.ROOT, repeat = c.kind() == Kind.REPEAT;
        return new Box(0, 0, theme.cardW(), theme.cardH(), r, root ? "rootFill" : repeat ? "repeatFill" : "card",
                root ? "accent" : "cardLine", root ? 2 : 1, repeat, "bg");
    }

    private List<Mark> classicMarks(Card c) {
        double W = theme.cardW(), H = theme.cardH();
        var p = c.person();
        var out = new ArrayList<Mark>();
        out.add(background(c, 8));
        out.add(new Bar(H, 8, sexKey(p)));

        var nm = theme.font("nm");
        double left = 18, width = W - left - 12;
        var names = nameLines(p, width, nm);
        var years = years(p);
        var tag = c.family().map(this::marriageTag).orElse("");
        var flag = c.kind() == Kind.REPEAT ? Optional.<Flags.Flag>empty() : Flags.forPlace(birthPlace(p));
        double placeX = left + (flag.isPresent() ? 20 : 0), placeW = W - placeX - 12;
        var place = c.kind() == Kind.REPEAT ? l.repeat() : place(p, placeW);
        if (!place.isEmpty()) place = fit(place, placeW, theme.font("pl"));

        boolean yearLine = !years.isEmpty() || !tag.isEmpty();
        double total = names.size() * 18 + (yearLine ? 17 : 0) + (place.isEmpty() ? 0 : 15);
        double y = (H - total) / 2 + 14;
        for (var line : names) {
            out.add(new Txt(List.of(new Run(line, "nm", null)), left, y, "start", squeeze(line, width, nm)));
            y += 18;
        }
        if (yearLine) {
            y += 1;
            double tagW = tag.isEmpty() ? 0 : measure(tag, theme.font("tag")) + 8;
            if (!years.isEmpty()) out.add(Txt.of(fit(years, width - tagW, theme.font("yr")), "yr", left, y));
            if (!tag.isEmpty()) out.add(new Txt(List.of(new Run(tag, "tag", null)), W - 12, y, "end", 0));
            y += 16;
        } else {
            y -= 2;
        }
        if (!place.isEmpty()) {
            double baseline = y;
            flag.ifPresent(f -> out.add(new FlagMark(f, left, baseline - 9, 15, 10)));
            out.add(Txt.of(place, "pl", placeX, y));
        }
        return out;
    }

    private List<Mark> modernMarks(Card c) {
        double W = theme.cardW(), H = theme.cardH(), right = W - 14;
        var p = c.person();
        var out = new ArrayList<Mark>();
        boolean repeat = c.kind() == Kind.REPEAT;
        out.add(background(c, theme.radius()));

        // Marriage badge, sitting in the gap above a partner's card, beside the tie line.
        c.family().map(this::marriageTag).ifPresent(tag -> {
            var tf = theme.font("tag");
            double bw = measure(tag, tf) + 14, bh = 16, by = -theme.stack() + (theme.stack() - bh) / 2;
            out.add(new Box(theme.tieX() + 8, by, bw, bh, 8, "tagBg", null, 0, false, null));
            out.add(Txt.of(tag, "tag", theme.tieX() + 15, by + 11.5));
        });

        // Initials avatar, tinted by sex.
        var sex = sexKey(p);
        out.add(new Dot(theme.tieX(), H / 2, 19, sex + "Bg"));
        out.add(new Txt(List.of(new Run(initials(p), "ini", sex)), theme.tieX(), H / 2 + 4.6, "middle", 0));

        // Large flag, top right; record id pill, bottom right.
        var flag = repeat ? Optional.<Flags.Flag>empty() : Flags.forPlace(birthPlace(p));
        double flagW = 30, flagH = 20;
        flag.ifPresent(f -> out.add(new FlagMark(f, right - flagW, 13, flagW, flagH)));
        var idf = theme.font("id");
        double idW = measure(p.key(), idf) + 12, idH = 16, idY = H - 12 - idH;
        out.add(new Box(right - idW, idY, idW, idH, 5, "idBg", null, 0, false, null));
        out.add(new Txt(List.of(new Run(p.key(), "id", null)), right - idW / 2, idY + 11.4, "middle", 0));

        double x = theme.tieX() + 30;
        var nm = theme.font("nm");
        double nameW = right - x - (flag.isPresent() ? flagW + 8 : 0);
        var names = nameLines(p, nameW, nm);
        var years = years(p);
        var age = age(p);
        double placeW = right - x - idW - 8;
        var place = repeat ? l.repeat() : place(p, placeW);
        if (!place.isEmpty()) place = fit(place, placeW, theme.font("pl"));

        boolean yearLine = !years.isEmpty() || !age.isEmpty();
        double total = names.size() * 19 + (yearLine ? 18 : 0) + (place.isEmpty() ? 0 : 16);
        double y = (H - total) / 2 + 14;
        for (var line : names) {
            out.add(new Txt(List.of(new Run(line, "nm", null)), x, y, "start", squeeze(line, nameW, nm)));
            y += 19;
        }
        if (yearLine) {
            y += 1;
            var runs = new ArrayList<Run>();
            if (!years.isEmpty()) runs.add(new Run(years, "yr", null));
            if (!age.isEmpty()) runs.add(new Run((years.isEmpty() ? "" : "  ·  ") + age, "age", null));
            out.add(new Txt(runs, x, y, "start", 0));
            y += 17;
        } else {
            y -= 2;
        }
        if (!place.isEmpty()) out.add(Txt.of(place, "pl", x, y));
        return out;
    }

    private String initials(Person p) {
        var nm = p.name();
        var a = firstLetter(nm.given());
        var b = firstLetter(nm.surname());
        var s = (a + b).isEmpty() ? firstLetter(nm.full()) : a + b;
        return s.isEmpty() ? "?" : s.toUpperCase(Locale.ROOT);
    }

    private static String firstLetter(String s) {
        for (int i = 0; i < s.length(); i = s.offsetByCodePoints(i, 1)) {
            int cp = s.codePointAt(i);
            if (Character.isLetter(cp)) return new String(Character.toChars(cp));
        }
        return "";
    }

    // ---- text fitting --------------------------------------------------------

    /// One line if the full name fits, otherwise given names over surname.
    private List<String> nameLines(Person p, double width, Theme.Font f) {
        var nm = p.name();
        var full = nm.full();
        if (full.isEmpty()) return List.of(l.unnamed());
        if (measure(full, f) <= width) return List.of(full);
        var second = (nm.surname() + " " + nm.suffix()).strip();
        var first = nm.given();
        if (first.isEmpty() || second.isEmpty()) {
            var words = full.split(" ");
            int half = Math.max(1, words.length / 2);
            first = String.join(" ", List.of(words).subList(0, half));
            second = String.join(" ", List.of(words).subList(half, words.length));
        }
        return List.of(fit(first, width / 0.86, f), fit(second, width / 0.86, f));
    }

    /// Text that a little horizontal squeeze can make fit gets squeezed; 0 = none needed.
    private static double squeeze(String s, double width, Theme.Font f) {
        return measure(s, f) > width ? width : 0;
    }

    private static String fit(String s, double width, Theme.Font f) {
        if (measure(s, f) <= width) return s;
        var t = s;
        while (t.length() > 1 && measure(t + "…", f) > width) t = t.substring(0, t.length() - 1);
        return t.strip() + "…";
    }

    static double measure(String s, Theme.Font f) {
        if (f.css().equals(Theme.MONO_CSS)) return s.length() * 0.62 * f.size();
        return measure(s, f.size(), f.weight() >= 600) + s.length() * f.tracking() * f.size();
    }

    /// Rough rendered width of `s`, good enough to decide on wrapping.
    static double measure(String s, double size, boolean bold) {
        var base = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        double em = 0;
        for (int i = 0; i < base.length(); i++) {
            char ch = base.charAt(i);
            em += "iljtf.,;:'!|()[] ".indexOf(ch) >= 0 ? 0.30
                    : "mwMW@".indexOf(ch) >= 0 ? 0.86
                    : Character.isUpperCase(ch) ? 0.68
                    : Character.isDigit(ch) ? 0.55 : 0.52;
        }
        return em * size * (bold ? 1.07 : 1.0);
    }

    // ---- what a card says ----------------------------------------------------

    private String years(Person p) {
        var b = l.year(p.birth());
        var d = l.year(p.death());
        if (b.isEmpty() && d.isEmpty()) return p.isDeceased() ? "†" : "";
        if (d.isEmpty()) return p.isDeceased() ? b + " – ?" : l.born() + b;
        if (b.isEmpty()) return l.died() + d;
        return b + " – " + d;
    }

    /// Age at death, or today's age for the living. A `BET … AND …` date
    /// gives a range of ages: one number when both ends agree, else `62–63`.
    /// Without usable dates, falls back to an `AGE` recorded on the death
    /// (as death acts often give). Empty when it can't be worked out
    /// honestly: before/after dates, no death date for someone who died, or
    /// an implausible result.
    String age(Person p) {
        var computed = computedAge(p);
        if (!computed.isEmpty() || !p.isDeceased()) return computed;
        return p.event("DEAT").map(e -> recordedAge(e.node().text("AGE"))).orElse("");
    }

    private String computedAge(Person p) {
        var b = bounds(p.birth());
        if (b == null) return "";
        boolean living = !p.isDeceased();
        boolean approx = p.birth().qualifier() == GedDate.Qualifier.ABOUT;
        GedDate[] d;
        if (living) {
            var now = new GedDate("", GedDate.Qualifier.EXACT, today.getYear(), today.getMonthValue(), today.getDayOfMonth());
            d = new GedDate[] {now, now};
        } else {
            d = bounds(p.death());
            if (d == null) return "";
            approx |= p.death().qualifier() == GedDate.Qualifier.ABOUT;
        }
        int min = years(b[1], d[0]), max = years(b[0], d[1]);
        if (min < 0 || min > max || max > (living ? 105 : 120)) return "";
        return l.age(min, max, approx, living);
    }

    /// Earliest and latest a date can mean; null if it can't pin one down.
    private static GedDate[] bounds(GedDate d) {
        if (!d.hasYear()) return null;
        return switch (d.qualifier()) {
            case EXACT, ABOUT -> new GedDate[] {d, d};
            case BETWEEN -> {
                var parts = d.raw().toUpperCase(Locale.ROOT).replaceAll("@#D[^@]*@\\s*", "")
                        .replaceFirst("^\\s*BET\\s+", "").split("\\s+AND\\s+");
                if (parts.length != 2) yield null;
                var lo = GedDate.parse(parts[0]);
                var hi = GedDate.parse(parts[1]);
                boolean ok = lo.hasYear() && hi.hasYear() && lo.qualifier() == GedDate.Qualifier.EXACT
                        && hi.qualifier() == GedDate.Qualifier.EXACT && lo.compareTo(hi) <= 0;
                yield ok ? new GedDate[] {lo, hi} : null;
            }
            default -> null;
        };
    }

    /// Whole years from `from` to `to`; month and day are used when both dates have them.
    private static int years(GedDate from, GedDate to) {
        int y = to.year() - from.year();
        if (from.month() > 0 && to.month() > 0
                && (to.month() < from.month() || (to.month() == from.month() && from.day() > 0 && to.day() > 0 && to.day() < from.day()))) {
            y--;
        }
        return y;
    }

    private static final java.util.regex.Pattern AGE = java.util.regex.Pattern.compile("^\\s*(\\d{1,3})\\s*(?:y|yrs?|years?)?\\b.*", java.util.regex.Pattern.CASE_INSENSITIVE);

    /// `62`, `62y`, `62y 3m` → age 62. Anything vaguer (`<1`, `CHILD`) → empty.
    private String recordedAge(String raw) {
        var m = AGE.matcher(raw);
        if (!m.matches()) return "";
        int n = Integer.parseInt(m.group(1));
        return n > 120 ? "" : l.age(n, n, false, false);
    }

    private static String birthPlace(Person p) {
        return p.event("BIRT").or(() -> p.event("CHR")).or(() -> p.event("BAPM")).map(Event::place).orElse("");
    }

    private String place(Person p, double width) {
        var place = birthPlace(p);
        var parts = List.of(place.split("\\s*,\\s*")).stream().filter(s -> !s.isBlank()).toList();
        if (parts.isEmpty()) return "";
        var full = String.join(", ", parts);
        if (parts.size() <= 2 || measure(full, theme.font("pl")) <= width) return full;
        return parts.getFirst() + ", " + parts.getLast();
    }

    private String marriageTag(Family f) {
        var y = f.event("MARR").map(e -> l.year(e.date())).orElse("");
        var tag = "∞" + (y.isEmpty() ? "" : " " + y);
        return f.event("DIV").isPresent() ? tag + " · " + l.divorced() : tag;
    }

    // ---- SVG -----------------------------------------------------------------

    /// How SVG colours are written: as CSS variables (HTML page, switchable),
    /// or as fixed light/dark values (standalone .svg).
    public enum Colors { VARS, LIGHT, DARK }

    private String color(String key, Colors mode) {
        var light = theme.light().get(key);
        if (light == null) throw new IllegalArgumentException("no colour " + key);
        return switch (mode) {
            case VARS -> "var(--c-" + key + "," + light + ")";
            case LIGHT -> light;
            case DARK -> theme.palette(true).get(key);
        };
    }

    private String fontStyle(String style, String colorKey, Colors mode) {
        var f = theme.font(style);
        return "font:" + f.cssFont() + ";fill:" + color(colorKey == null ? f.color() : colorKey, mode)
                + (f.tracking() > 0 ? ";letter-spacing:" + f.tracking() + "em" : "");
    }

    /// The chart as SVG. With `heading`, the title is drawn inside it (for a
    /// standalone .svg file); the HTML page has its own heading instead.
    public String svg(boolean heading, Colors mode) {
        double top = heading ? 64 : 0;
        double w = Math.max(chart.width(), heading ? 520 : 0), h = chart.height() + top;
        var sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(n(w)).append(' ').append(n(h))
                .append("\" width=\"").append(n(w)).append("\" height=\"").append(n(h)).append("\" role=\"img\" aria-label=\"")
                .append(esc(title)).append("\">\n");
        sb.append(flagDefs(mode));
        if (heading) {
            sb.append("<rect width=\"100%\" height=\"100%\" style=\"fill:").append(color("paper", mode)).append("\"/>\n");
            sb.append("<text x=\"").append(n(Layout.MARGIN)).append("\" y=\"38\" style=\"").append(fontStyle("title", null, mode))
                    .append("\">").append(esc(title)).append("</text>\n");
            sb.append("<text x=\"").append(n(Layout.MARGIN)).append("\" y=\"58\" style=\"").append(fontStyle("sub", null, mode))
                    .append("\">").append(esc(subtitle())).append("</text>\n");
            sb.append("<g transform=\"translate(0,").append(n(top)).append(")\">\n");
        }
        for (var c : chart.columns()) {
            var label = theme.font("col").upper() ? c.label().toUpperCase(Locale.ROOT) : c.label();
            sb.append("<text x=\"").append(n(c.centerX())).append("\" y=\"").append(n(Layout.MARGIN + 10))
                    .append("\" text-anchor=\"middle\" style=\"").append(fontStyle("col", null, mode)).append("\">")
                    .append(esc(label)).append("</text>\n");
        }
        for (var t : chart.ties()) {
            sb.append("<line x1=\"").append(n(t.x1())).append("\" y1=\"").append(n(t.y1())).append("\" x2=\"").append(n(t.x2()))
                    .append("\" y2=\"").append(n(t.y2())).append("\" style=\"stroke:").append(color("accent", mode))
                    .append(";stroke-width:2;stroke-dasharray:2 2\"/>\n");
        }
        var linkStyle = "fill:none;stroke:" + color("link", mode) + ";stroke-width:1.6";
        for (var lk : chart.links()) sb.append("<path d=\"").append(elbow(lk)).append("\" style=\"").append(linkStyle).append("\"/>\n");
        for (var c : chart.cards()) card(sb, c, mode);
        if (heading) sb.append("</g>\n");
        return sb.append("</svg>\n").toString();
    }

    private void card(StringBuilder sb, Card c, Colors mode) {
        var p = c.person();
        var cls = switch (c.kind()) { case ROOT -> " root"; case REPEAT -> " repeat"; default -> ""; };
        sb.append("<g class=\"card").append(cls).append("\" data-id=\"").append(esc(p.key()))
                .append("\" transform=\"translate(").append(n(c.x())).append(',').append(n(c.y())).append(")\"")
                .append(c.kind() == Kind.REPEAT ? " opacity=\".8\"" : "").append(">");
        sb.append("<title>").append(esc(p.name().full().isEmpty() ? l.unnamed() : p.name().full())).append("</title>");
        for (var m : marks(c)) {
            switch (m) {
                case Box b -> {
                    sb.append("<rect").append(b.cls() == null ? "" : " class=\"" + b.cls() + "\"")
                            .append(" x=\"").append(n(b.x())).append("\" y=\"").append(n(b.y()))
                            .append("\" width=\"").append(n(b.w())).append("\" height=\"").append(n(b.h()))
                            .append("\" rx=\"").append(n(b.r())).append("\" style=\"fill:").append(color(b.fill(), mode));
                    if (b.stroke() != null) {
                        sb.append(";stroke:").append(color(b.stroke(), mode)).append(";stroke-width:").append(n(b.strokeW()));
                        if (b.dashed()) sb.append(";stroke-dasharray:5 4");
                    }
                    sb.append("\"/>");
                }
                case Bar b -> sb.append("<path d=\"M").append(n(b.r())).append(" 0A").append(n(b.r())).append(' ').append(n(b.r()))
                        .append(" 0 0 0 0 ").append(n(b.r())).append("V").append(n(b.h() - b.r())).append("A").append(n(b.r()))
                        .append(' ').append(n(b.r())).append(" 0 0 0 ").append(n(b.r())).append(' ').append(n(b.h()))
                        .append("Z\" style=\"fill:").append(color(b.fill(), mode)).append("\"/>");
                case Dot d -> sb.append("<circle cx=\"").append(n(d.cx())).append("\" cy=\"").append(n(d.cy()))
                        .append("\" r=\"").append(n(d.r())).append("\" style=\"fill:").append(color(d.fill(), mode)).append("\"/>");
                case FlagMark f -> sb.append("<use href=\"#flag-").append(f.flag().code()).append("\" x=\"").append(n(f.x()))
                        .append("\" y=\"").append(n(f.y())).append("\" width=\"").append(n(f.w())).append("\" height=\"")
                        .append(n(f.h())).append("\"><title>").append(esc(f.flag().country())).append("</title></use>");
                case Txt t -> {
                    sb.append("<text x=\"").append(n(t.x())).append("\" y=\"").append(n(t.y())).append('"');
                    if (!t.anchor().equals("start")) sb.append(" text-anchor=\"").append(t.anchor()).append('"');
                    if (t.squeezeTo() > 0) sb.append(" textLength=\"").append(n(t.squeezeTo())).append("\" lengthAdjust=\"spacingAndGlyphs\"");
                    if (t.runs().size() == 1) {
                        var r = t.runs().getFirst();
                        sb.append(" style=\"").append(fontStyle(r.style(), r.color(), mode)).append("\">").append(esc(r.text()));
                    } else {
                        sb.append('>');
                        for (var r : t.runs()) {
                            sb.append("<tspan style=\"").append(fontStyle(r.style(), r.color(), mode)).append("\">")
                                    .append(esc(r.text()).replace("  ", "  ")).append("</tspan>");
                        }
                    }
                    sb.append("</text>");
                }
            }
        }
        sb.append("</g>\n");
    }

    /// One `<symbol>` per flag used on the chart, referenced by the cards.
    private String flagDefs(Colors mode) {
        var used = new TreeMap<String, Flags.Flag>();
        for (var c : chart.cards()) for (var m : marks(c)) if (m instanceof FlagMark f) used.put(f.flag().code(), f.flag());
        if (used.isEmpty()) return "";
        double r = theme.modern() ? 1.6 : 0;
        var sb = new StringBuilder("<defs>\n<clipPath id=\"flag-clip\"><rect width=\"18\" height=\"12\" rx=\"")
                .append(n(r)).append("\"/></clipPath>\n");
        for (var f : used.values()) {
            sb.append("<symbol id=\"flag-").append(f.code()).append("\" viewBox=\"0 0 18 12\"><g clip-path=\"url(#flag-clip)\">");
            for (var sh : f.shapes()) {
                var v = sh.v();
                switch (sh.kind()) {
                    case "rect" -> sb.append("<rect x=\"").append(n2(v[0])).append("\" y=\"").append(n2(v[1]))
                            .append("\" width=\"").append(n2(v[2])).append("\" height=\"").append(n2(v[3])).append('"');
                    case "circle" -> sb.append("<circle cx=\"").append(n2(v[0])).append("\" cy=\"").append(n2(v[1]))
                            .append("\" r=\"").append(n2(v[2])).append('"');
                    default -> {
                        sb.append("<polygon points=\"");
                        for (int i = 0; i < v.length; i += 2) sb.append(i == 0 ? "" : " ").append(n2(v[i])).append(',').append(n2(v[i + 1]));
                        sb.append('"');
                    }
                }
                sb.append(" fill=\"").append(sh.color()).append("\"/>");
            }
            sb.append("</g><rect x=\".3\" y=\".3\" width=\"17.4\" height=\"11.4\" rx=\"").append(n(r))
                    .append("\" style=\"fill:none;stroke:").append(color("flagLine", mode))
                    .append(";stroke-opacity:.2;stroke-width:.6\"/></symbol>\n");
        }
        return sb.append("</defs>\n").toString();
    }

    /// Right-angled connector with rounded corners, turning halfway between columns.
    private static String elbow(Link k) {
        double mx = (k.x1() + k.x2()) / 2, dy = k.y2() - k.y1();
        if (Math.abs(dy) < 1) return "M" + n(k.x1()) + " " + n(k.y1()) + "H" + n(k.x2());
        double r = Math.min(10, Math.abs(dy) / 2), sx = Math.signum(k.x2() - k.x1()), sy = Math.signum(dy);
        return "M" + n(k.x1()) + " " + n(k.y1())
                + "H" + n(mx - r * sx)
                + "Q" + n(mx) + " " + n(k.y1()) + " " + n(mx) + " " + n(k.y1() + r * sy)
                + "V" + n(k.y2() - r * sy)
                + "Q" + n(mx) + " " + n(k.y2()) + " " + n(mx + r * sx) + " " + n(k.y2())
                + "H" + n(k.x2());
    }

    // ---- HTML ----------------------------------------------------------------

    /// `startDark`: open in dark mode (the viewer can still switch).
    public String html(boolean startDark) {
        var themeButton = theme.hasDark() ? "<button id=\"tm\" aria-label=\"Theme\"></button>" : "";
        return PAGE
                .replace("{{lang}}", l.code())
                .replace("{{scheme}}", theme.hasDark() ? "light dark" : "light")
                .replace("{{vars}}", cssVars())
                .replace("{{start}}", startDark ? " data-theme=\"dark\"" : "")
                .replace("{{title}}", esc(title))
                .replace("{{subtitle}}", esc(subtitle()))
                .replace("{{hint}}", esc(l.hint()))
                .replace("{{fit}}", esc(l.fit()))
                .replace("{{print}}", esc(l.print()))
                .replace("{{close}}", esc(l.close()))
                .replace("{{male}}", esc(l.male()))
                .replace("{{female}}", esc(l.female()))
                .replace("{{repeat}}", esc(l.repeatLegend()))
                .replace("{{themeButton}}", themeButton)
                .replace("{{labels}}", json(Map.of("parents", l.parents(), "spouses", l.spouses(),
                        "children", l.children(), "notes", l.notes(), "none", l.noFacts(),
                        "auto", l.themeAuto(), "light", l.themeLight(), "dark", l.themeDark())))
                .replace("{{data}}", details().replace("</", "<\\/"))
                .replace("{{svg}}", svg(false, Colors.VARS));
    }

    /// Palette as CSS variables: light by default, dark when the system asks
    /// (unless switched to light) or when switched to dark; always light in print.
    private String cssVars() {
        var sb = new StringBuilder();
        sb.append(":root{").append(vars(theme.light())).append("}\n");
        if (theme.hasDark()) {
            sb.append("@media (prefers-color-scheme:dark){:root:not([data-theme=light]){").append(vars(theme.dark())).append("}}\n");
            sb.append(":root[data-theme=dark]{").append(vars(theme.dark())).append("}\n");
            sb.append("@media print{:root,:root:not([data-theme=light]),:root[data-theme=dark]{").append(vars(theme.light())).append("}}\n");
        }
        return sb.toString();
    }

    private static String vars(Map<String, String> palette) {
        return new TreeMap<>(palette).entrySet().stream().map(e -> "--c-" + e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(";"));
    }

    /// Per-person facts and relatives for the detail panel, as JSON.
    private String details() {
        var people = new LinkedHashMap<String, Object>();
        for (var c : chart.cards()) {
            var p = c.person();
            if (people.containsKey(p.key())) continue;
            var m = new LinkedHashMap<String, Object>();
            m.put("id", p.key());
            m.put("name", p.name().full().isEmpty() ? l.unnamed() : p.name().full());
            m.put("years", years(p));
            m.put("age", age(p));
            var facts = new ArrayList<List<String>>();
            for (var e : p.events()) fact(e, facts);
            m.put("facts", facts);
            m.put("parents", refs(g.parents(p)));
            var spouses = new ArrayList<List<String>>();
            for (var f : g.familiesAsSpouse(p)) {
                f.partnerOf(p.id()).flatMap(g::person).ifPresent(q -> {
                    var marr = f.event("MARR").map(e -> join(" · ", l.fullDate(e.date()), e.place())).orElse("");
                    spouses.add(List.of(q.key(), name(q), marr));
                });
            }
            m.put("spouses", spouses);
            m.put("children", refs(g.children(p)));
            if (notes) m.put("notes", p.notes().stream().map(g::noteText).filter(s -> !s.isBlank()).toList());
            people.put(p.key(), m);
        }
        return json(people);
    }

    private void fact(Event e, List<List<String>> facts) {
        var detail = e.detail().equalsIgnoreCase("Y") ? "" : e.detail();
        if (notes) {
            var en = e.node().children().stream().filter(c -> c.tag().equals("NOTE"))
                    .map(c -> g.noteText(c.value())).filter(s -> !s.isBlank()).collect(Collectors.joining(" "));
            detail = join(" — ", detail, en);
        }
        if (e.date().isEmpty() && e.place().isEmpty() && detail.isEmpty()) return;
        facts.add(List.of(l.event(e), l.fullDate(e.date()), e.place(), detail));
    }

    private List<List<String>> refs(List<Person> ps) {
        return ps.stream().distinct().map(q -> List.of(q.key(), name(q))).toList();
    }

    private String name(Person p) {
        var y = years(p);
        return (p.name().full().isEmpty() ? l.unnamed() : p.name().full()) + (y.isEmpty() ? "" : " (" + y + ")");
    }

    private static String join(String sep, String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + sep + b;
    }

    // ---- tiny serialisers ----------------------------------------------------

    static String n(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String n2(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    static String esc(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String json(Object o) {
        return switch (o) {
            case null -> "null";
            case String s -> {
                var sb = new StringBuilder("\"");
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    switch (c) {
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        case '\n' -> sb.append("\\n");
                        case '\r' -> sb.append("\\r");
                        case '\t' -> sb.append("\\t");
                        default -> {
                            if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                            else sb.append(c);
                        }
                    }
                }
                yield sb.append('"').toString();
            }
            case Map<?, ?> m -> m.entrySet().stream()
                    .map(e -> json(String.valueOf(e.getKey())) + ":" + json(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
            case List<?> list -> list.stream().map(ChartPage::json).collect(Collectors.joining(",", "[", "]"));
            case Optional<?> opt -> json(opt.orElse(null));
            default -> json(String.valueOf(o));
        };
    }

    private static final String PAGE = """
            <!doctype html>
            <html lang="{{lang}}"{{start}}>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="{{scheme}}">
            <title>{{title}}</title>
            <style>
            {{vars}}
              *{box-sizing:border-box}
              html,body{margin:0;height:100%}
              body{background:var(--c-paper);color:var(--c-ink);font:15px/1.45 -apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;display:flex;flex-direction:column;transition:background .2s}
              header{padding:18px 24px 10px;border-bottom:1px solid var(--c-line);display:flex;flex-wrap:wrap;gap:10px 24px;align-items:flex-end;justify-content:space-between}
              h1{font:700 26px/1.2 'Iowan Old Style','Palatino Linotype',Palatino,Georgia,serif;margin:0;letter-spacing:-.01em}
              .sub{color:var(--c-muted);font-size:13.5px;margin-top:3px}
              .tools{display:flex;gap:6px;align-items:center;flex-wrap:wrap}
              button{font:inherit;font-size:14px;border:1px solid var(--c-btnLine);background:var(--c-btn);color:var(--c-ink);border-radius:8px;padding:6px 12px;cursor:pointer;min-width:38px}
              button:hover{background:var(--c-btnHover)}
              #zl{min-width:48px;text-align:center;color:var(--c-muted);font-size:13px}
              .legend{display:flex;gap:16px;flex-wrap:wrap;align-items:center;color:var(--c-muted);font-size:13px;padding:8px 24px;border-bottom:1px solid var(--c-line)}
              .legend i{display:inline-block;width:12px;height:12px;border-radius:50%;margin-right:6px;vertical-align:-1px}
              .legend .dash{border:1.5px dashed var(--c-link);border-radius:3px;background:var(--c-card)}
              .legend .hint{margin-left:auto}
              #wrap{flex:1;overflow:auto;cursor:grab;position:relative}
              #wrap.drag{cursor:grabbing;user-select:none}
              #wrap svg{display:block;margin:0 auto}
              .card{cursor:pointer}
              .card:hover .bg{stroke:var(--c-hover)!important}
              .card.sel .bg{stroke:var(--c-ink)!important;stroke-width:2.5px!important}
              aside{position:fixed;right:16px;bottom:16px;width:min(360px,calc(100vw - 32px));max-height:min(70vh,560px);overflow:auto;background:var(--c-panel);color:var(--c-ink);border:1px solid var(--c-line);border-radius:14px;box-shadow:0 12px 32px rgba(0,0,0,.22);padding:18px 20px 16px;display:none}
              aside.open{display:block}
              aside h2{font:700 20px/1.25 'Iowan Old Style','Palatino Linotype',Palatino,Georgia,serif;margin:0 28px 2px 0}
              aside .yrs{color:var(--c-muted);margin-bottom:10px}
              aside .id{font:600 11px ui-monospace,'SF Mono',Menlo,Consolas,monospace;background:var(--c-line);border-radius:5px;padding:1px 6px;margin-left:4px;vertical-align:1px}
              aside .x{position:absolute;top:10px;right:10px;border:none;background:none;font-size:20px;color:var(--c-muted);padding:2px 8px}
              aside dl{margin:0 0 6px;display:grid;grid-template-columns:auto 1fr;gap:4px 12px}
              aside dt{color:var(--c-muted);font-size:13px;padding-top:1px}
              aside dd{margin:0}
              aside dd small{display:block;color:var(--c-muted)}
              aside h3{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--c-muted);margin:14px 0 4px}
              aside ul{margin:0;padding-left:18px}
              aside a{color:var(--c-href);cursor:pointer;text-decoration:underline;text-decoration-color:color-mix(in srgb,var(--c-href) 35%,transparent)}
              aside p{margin:4px 0;white-space:pre-wrap}
              @media (max-width:640px){header{padding:12px 16px 8px}.legend{padding:8px 16px}.legend .hint{margin-left:0}h1{font-size:21px}}
              @media print{
                @page{size:landscape;margin:10mm}
                html,body{height:auto;display:block;background:#fff}
                .tools,.legend .hint,aside{display:none!important}
                header,.legend{border:none;padding:0 0 6px}
                #wrap{overflow:visible}
                #wrap svg{width:100%!important;height:auto!important}
              }
            </style>
            </head>
            <body>
            <header>
              <div><h1>{{title}}</h1><div class="sub">{{subtitle}}</div></div>
              <div class="tools">
                <button id="zo" aria-label="Zoom out">−</button><span id="zl">100%</span><button id="zi" aria-label="Zoom in">+</button>
                <button id="zf">{{fit}}</button>{{themeButton}}<button id="pr">{{print}}</button>
              </div>
            </header>
            <div class="legend">
              <span><i style="background:var(--c-male)"></i>{{male}}</span>
              <span><i style="background:var(--c-female)"></i>{{female}}</span>
              <span><i class="dash"></i>{{repeat}}</span>
              <span class="hint">{{hint}}</span>
            </div>
            <div id="wrap">
            {{svg}}</div>
            <aside id="panel" aria-live="polite"><button class="x" id="px" aria-label="{{close}}">×</button><div id="pc"></div></aside>
            <script>
            (function(){
              var DATA = {{data}};
              var L = {{labels}};
              var wrap = document.getElementById('wrap'), svg = wrap.querySelector('svg');
              var W = +svg.getAttribute('width'), H = +svg.getAttribute('height'), z = 1;

              var tm = document.getElementById('tm');
              if (tm) {
                var root = document.documentElement, KEY = 'ged-chart-theme';
                var mode = root.getAttribute('data-theme') || 'auto';
                try { mode = localStorage.getItem(KEY) || mode; } catch (e) {}
                var apply = function(m){
                  mode = m;
                  if (m === 'auto') root.removeAttribute('data-theme'); else root.setAttribute('data-theme', m);
                  tm.textContent = (m === 'dark' ? '☾ ' : m === 'light' ? '☀ ' : '◐ ') + L[m];
                };
                apply(mode);
                tm.onclick = function(){
                  apply({auto:'light', light:'dark', dark:'auto'}[mode]);
                  try { localStorage.setItem(KEY, mode); } catch (e) {}
                };
              }

              function zoom(nz, keepCenter){
                var cx = (wrap.scrollLeft + wrap.clientWidth/2)/z, cy = (wrap.scrollTop + wrap.clientHeight/2)/z;
                z = Math.max(0.15, Math.min(3, nz));
                svg.style.width = (W*z)+'px'; svg.style.height = (H*z)+'px';
                document.getElementById('zl').textContent = Math.round(z*100)+'%';
                if (keepCenter){ wrap.scrollLeft = cx*z - wrap.clientWidth/2; wrap.scrollTop = cy*z - wrap.clientHeight/2; }
              }
              function fit(){ zoom(Math.min(1, (wrap.clientWidth-16)/W, (wrap.clientHeight-16)/H)); }
              document.getElementById('zi').onclick = function(){ zoom(z*1.25, true); };
              document.getElementById('zo').onclick = function(){ zoom(z/1.25, true); };
              document.getElementById('zf').onclick = fit;
              document.getElementById('pr').onclick = function(){ window.print(); };
              zoom(Math.max(0.55, Math.min(1, (wrap.clientWidth-16)/W)));
              var rootCard = svg.querySelector('.card.root');
              if (rootCard) center(rootCard, false);

              function center(el, smooth){
                var r = el.getBoundingClientRect(), w = wrap.getBoundingClientRect();
                wrap.scrollTo({left: wrap.scrollLeft + r.left - w.left - (w.width - r.width)/2,
                               top: wrap.scrollTop + r.top - w.top - (w.height - r.height)/2,
                               behavior: smooth ? 'smooth' : 'auto'});
              }

              var drag = null, moved = false;
              wrap.addEventListener('pointerdown', function(e){
                if (e.button !== 0) return;
                drag = {x:e.clientX, y:e.clientY, l:wrap.scrollLeft, t:wrap.scrollTop}; moved = false;
              });
              window.addEventListener('pointermove', function(e){
                if (!drag) return;
                var dx = e.clientX-drag.x, dy = e.clientY-drag.y;
                if (!moved && Math.abs(dx)+Math.abs(dy) < 5) return;
                moved = true; wrap.classList.add('drag');
                wrap.scrollLeft = drag.l-dx; wrap.scrollTop = drag.t-dy;
              });
              window.addEventListener('pointerup', function(){ drag = null; wrap.classList.remove('drag'); });

              function esc(s){ return String(s).replace(/[&<>"']/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
              function ref(r){
                return svg.querySelector('[data-id="'+r[0]+'"]') ? '<a data-go="'+esc(r[0])+'">'+esc(r[1])+'</a>' : esc(r[1]);
              }
              function show(id, scroll){
                var p = DATA[id]; if (!p) return;
                svg.querySelectorAll('.card.sel').forEach(function(c){ c.classList.remove('sel'); });
                var el = svg.querySelector('[data-id="'+id+'"]:not(.repeat)') || svg.querySelector('[data-id="'+id+'"]');
                if (el){ el.classList.add('sel'); if (scroll) center(el, true); }
                var sub = [p.years, p.age].filter(Boolean).join(' · ');
                var h = '<h2>'+esc(p.name)+'</h2><div class="yrs">'+esc(sub)+'<span class="id">'+esc(p.id)+'</span></div>';
                if (p.facts.length){
                  h += '<dl>';
                  p.facts.forEach(function(f){
                    var main = [f[1], f[2]].filter(Boolean).join(' · ');
                    h += '<dt>'+esc(f[0])+'</dt><dd>'+esc(main || f[3])+(main && f[3] ? '<small>'+esc(f[3])+'</small>' : '')+'</dd>';
                  });
                  h += '</dl>';
                } else h += '<div class="yrs">'+esc(L.none)+'</div>';
                if (p.parents.length) h += '<h3>'+esc(L.parents)+'</h3><ul>'+p.parents.map(function(r){ return '<li>'+ref(r)+'</li>'; }).join('')+'</ul>';
                if (p.spouses.length) h += '<h3>'+esc(L.spouses)+'</h3><ul>'+p.spouses.map(function(r){ return '<li>'+ref(r)+(r[2] ? '<br><small>∞ '+esc(r[2])+'</small>' : '')+'</li>'; }).join('')+'</ul>';
                if (p.children.length) h += '<h3>'+esc(L.children)+'</h3><ul>'+p.children.map(function(r){ return '<li>'+ref(r)+'</li>'; }).join('')+'</ul>';
                if (p.notes && p.notes.length) h += '<h3>'+esc(L.notes)+'</h3>'+p.notes.map(function(n){ return '<p>'+esc(n)+'</p>'; }).join('');
                document.getElementById('pc').innerHTML = h;
                document.getElementById('panel').classList.add('open');
              }
              svg.addEventListener('click', function(e){
                if (moved) return;
                var c = e.target.closest('.card'); if (c) show(c.getAttribute('data-id'), false);
              });
              document.getElementById('pc').addEventListener('click', function(e){
                var a = e.target.closest('a[data-go]'); if (a) show(a.getAttribute('data-go'), true);
              });
              document.getElementById('px').onclick = function(){
                document.getElementById('panel').classList.remove('open');
                svg.querySelectorAll('.card.sel').forEach(function(c){ c.classList.remove('sel'); });
              };
              document.addEventListener('keydown', function(e){ if (e.key === 'Escape') document.getElementById('px').click(); });
            })();
            </script>
            </body>
            </html>
            """;
}
