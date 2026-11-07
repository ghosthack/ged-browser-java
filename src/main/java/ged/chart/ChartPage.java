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

    private boolean arms;

    /// Baroque only: bear the flag on a shield (men) or lozenge (women) instead of a plain medallion.
    public ChartPage arms(boolean on) {
        this.arms = on;
        return this;
    }

    Chart chart() { return chart; }
    Theme theme() { return theme; }
    String title() { return title; }

    public String subtitle() {
        int gens = chart.up() + chart.down() + 1;
        int n = chart.peopleCount();
        var s = (chart.timeline() ? l.timelineWith(n) : l.treeWith(n)) + " · " + l.generations(gens) + " · " + l.made(today);
        return theme.sephirot() ? dots(s) : s;
    }

    // ---- marks ---------------------------------------------------------------

    /// Something drawn on a card, in card coordinates. Colours are palette keys.
    sealed interface Mark permits Box, Bar, Dot, Txt, FlagMark, Orn, Ring, Slant {}

    /// One line of text centred on (x, y) — the middle of its capitals, not its
    /// baseline — and turned by `angle` (radians, clockwise on screen).
    record Slant(String text, String style, double x, double y, double angle) implements Mark {}

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

    /// A flag stretched to (w × h); `clip` names an [Ornaments.Escutcheon] to cut it to, or is null.
    record FlagMark(Flags.Flag flag, double x, double y, double w, double h, Ornaments.Escutcheon clip) implements Mark {
        FlagMark(Flags.Flag flag, double x, double y, double w, double h) { this(flag, x, y, w, h, null); }
    }

    /// An ornament or frame as SVG path data (absolute M/L/C/Q/Z only); null fill or stroke = none.
    record Orn(String d, String fill, String stroke, double strokeW, boolean dashed, String cls) implements Mark {
        Orn(String d, String fill, String stroke, double strokeW) { this(d, fill, stroke, strokeW, false, null); }
    }

    /// Text set around a circle about (cx, cy), its baseline on radius `r`,
    /// centred on the top (reading clockwise, letters standing outwards) or
    /// on the bottom (reading the other way, letters pointing inwards).
    record Ring(String text, String style, double cx, double cy, double r, boolean top) implements Mark {}

    List<Mark> marks(Card c) {
        if (c.span() != null) return timelineMarks(c);
        if (theme.sephirot()) return sephirotMarks(c);
        if (theme.ornate()) return baroqueMarks(c);
        return theme.modern() ? modernMarks(c) : classicMarks(c);
    }

    private double width = -1;

    /// Chart width, widened on a timeline so labels running past the last bar fit.
    double width() {
        if (width < 0) {
            width = chart.width();
            if (chart.timeline()) {
                for (var c : chart.cards()) {
                    for (var m : marks(c)) if (m instanceof Box b) width = Math.max(width, c.x() + b.x() + b.w() + Layout.MARGIN);
                }
            }
        }
        return width;
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

    /// A timeline row: a label (flag, name, years, age, id) sitting on a
    /// paper chip so lines passing behind don't run through the text, and
    /// below it the life bar — pale where dates are estimated, with a
    /// notch wherever a child's line leaves it.
    private List<Mark> timelineMarks(Card c) {
        var p = c.person();
        var s = c.span();
        var sex = sexKey(p);
        boolean root = c.kind() == Kind.ROOT, repeat = c.kind() == Kind.REPEAT;
        var out = new ArrayList<Mark>();

        var runs = new ArrayList<Run>();
        runs.add(new Run(p.name().full().isEmpty() ? l.unnamed() : p.name().full(), "nm", null));
        var years = years(p);
        var age = age(p);
        if (!years.isEmpty()) runs.add(new Run("   " + years, "yr", null));
        if (!age.isEmpty()) runs.add(new Run((years.isEmpty() ? "   " : "  ·  ") + age, "age", null));
        c.family().map(this::marriageTag).ifPresent(tag -> runs.add(new Run("    " + tag, "tag", null)));
        runs.add(new Run("    " + p.key(), "id", null));
        if (repeat) runs.add(new Run("   " + l.repeat(), "pl", null));
        double textW = runs.stream().mapToDouble(r -> measure(r.text(), theme.font(r.style()))).sum();

        double lx = Timeline.LABEL_X;
        var flag = repeat ? Optional.<Flags.Flag>empty() : Flags.forPlace(birthPlace(p));
        double tx = lx + (flag.isPresent() ? 27 : 0);
        out.add(new Box(lx - 4, 0, tx - lx + textW + 12, 22, 6, "paper", null, 0, false, null));
        flag.ifPresent(f -> out.add(new FlagMark(f, lx, 4.5, 21, 14)));
        out.add(new Txt(runs, tx, 16.5, "start", 0));

        double y = Timeline.BAR_Y, h = Timeline.BAR_H, r = h / 2;
        out.add(new Box(0, y, s.end(), h, r, sex + "Bg", null, 0, false, null));
        if (s.solidTo() - s.solidFrom() >= 1) {
            out.add(new Box(s.solidFrom(), y, s.solidTo() - s.solidFrom(), h, r, sex, null, 0, false, null));
        }
        for (double bx : s.branches()) out.add(new Dot(bx, y + h / 2, 2.1, "paper"));
        // Outline on top: hover/selection target, gold for the root, dashed for a repeat.
        out.add(new Box(0, y, s.end(), h, r, null, root ? "accent" : repeat ? sex : null, root ? 2 : 1.4, repeat, "bg"));
        return out;
    }

    /// An engraved cartouche: concave-cornered frame with an inner rule,
    /// scrollwork in the corners, a crest tinted by sex, everything centred,
    /// and the record id on a plate between two lozenges.
    private List<Mark> baroqueMarks(Card c) {
        double W = theme.cardW(), H = theme.cardH(), cx = W / 2;
        var p = c.person();
        boolean root = c.kind() == Kind.ROOT, repeat = c.kind() == Kind.REPEAT;
        var out = new ArrayList<Mark>();

        c.family().map(this::marriageTag).ifPresent(tag ->
                out.add(Txt.of(tag, "tag", theme.tieX() + 10, -theme.stack() / 2 + 4.5)));

        out.add(new Orn(Ornaments.frame(0, 0, W, H, 9), root ? "rootFill" : repeat ? "repeatFill" : "card",
                root ? "accent" : "cardLine", root ? 2 : 1.3, repeat, "bg"));
        out.add(new Orn(Ornaments.frame(5, 5, W - 10, H - 10, 6), null, "orn", 0.6));
        Ornaments.corners(out, 7.5, 7.5, W - 15, H - 15, 0.62, "orn", 0.8);
        var sex = sexKey(p);
        var flag = repeat ? Optional.<Flags.Flag>empty() : Flags.forPlace(birthPlace(p));
        if (arms) {
            // Arms on the top edge, as heraldry has it: a shield for a man, a lozenge
            // for a woman (an oval when unknown), bearing the birth country's flag,
            // or left plain and tinted when there's no country to show.
            var shape = Ornaments.Escutcheon.of(p.sex());
            double aw = shape.w(), ah = shape.h(), ax = cx - aw / 2, ay = shape.top();
            Ornaments.medallionCurls(out, cx, aw, "orn");
            out.add(new Orn(shape.path(ax - 2.6, ay - 2.6, aw + 5.2, ah + 5.2), root ? "rootFill" : "card", sex, 1.3));
            if (flag.isPresent()) out.add(new FlagMark(flag.get(), ax, ay, aw, ah, shape));
            else out.add(new Orn(shape.path(ax, ay, aw, ah), sex + "Bg", null, 0));
            out.add(new Orn(shape.path(ax, ay, aw, ah), null, "flagLine", 0.4));
        } else if (flag.isPresent()) {
            // The birth country's flag as a medallion on the top edge, framed in the person's colour.
            double fw = 22, fh = 14.6, fy = -fh / 2 + 1;
            Ornaments.medallionCurls(out, cx, fw, "orn");
            out.add(new Box(cx - fw / 2 - 2.5, fy - 2.5, fw + 5, fh + 5, 2.5, root ? "rootFill" : "card", sex, 1.3, false, null));
            out.add(new FlagMark(flag.get(), cx - fw / 2, fy, fw, fh));
        } else {
            Ornaments.crest(out, cx, 12, sex, "orn");
        }

        var idf = theme.font("id");
        double idW = measure(p.key(), idf);
        out.add(new Txt(List.of(new Run(p.key(), "id", null)), cx, H - 10.5, "middle", 0));
        Ornaments.plate(out, cx, H - 13.8, idW + 12, "orn");

        double inner = W - 44;
        var nm = theme.font("nm");
        var names = nameLines(p, inner, nm);
        var years = years(p);
        var age = age(p);
        var place = repeat ? l.repeat() : place(p, inner);
        if (!place.isEmpty()) place = fit(place, inner, theme.font("pl"));

        boolean yearLine = !years.isEmpty() || !age.isEmpty();
        double step = names.size() > 1 ? 17.5 : 19;
        double total = names.size() * step + (yearLine ? 17 : 0) + (place.isEmpty() ? 0 : 15);
        double y = 19 + (H - 19 - 22 - total) / 2 + 13;
        for (var line : names) {
            out.add(new Txt(List.of(new Run(line, "nm", null)), cx, y, "middle", squeeze(line, inner, nm)));
            y += step;
        }
        if (yearLine) {
            var runs = new ArrayList<Run>();
            if (!years.isEmpty()) runs.add(new Run(years.replace(" – ", "–"), "yr", null));
            if (!age.isEmpty()) runs.add(new Run((years.isEmpty() ? "" : "  ·  ") + age, "age", null));
            out.add(new Txt(runs, cx, y, "middle", 0));
            y += 16;
        }
        if (!place.isEmpty()) out.add(new Txt(List.of(new Run(place, "pl", null)), cx, y, "middle", 0));
        return out;
    }

    /// A medallion on an engraved plate: a double ring carrying the birth
    /// (top) and death (bottom) in small capitals; inside, the old sign for
    /// man or woman over the name, years and age; the birth country's flag,
    /// round, beside it. The root is crowned with rays. (How each person is
    /// related is written along the channels; see [#channels].)
    private List<Mark> sephirotMarks(Card c) {
        double R = Sephirot.R, cx = R, cy = R, band = R - 15, inner = band - 3.5;
        var p = c.person();
        boolean root = c.kind() == Kind.ROOT, repeat = c.kind() == Kind.REPEAT;
        var out = new ArrayList<Mark>();

        if (root) {
            for (int i = 0; i < 48; i++) {
                out.add(new Orn(Ornaments.spoke(cx, cy, R + 4, R + (i % 2 == 0 ? 12 : 8), i * Math.PI / 24), null, "accent", 0.8));
            }
        }
        out.add(new Orn(Ornaments.circle(cx, cy, R), root ? "rootFill" : repeat ? "repeatFill" : "card",
                root ? "accent" : "cardLine", root ? 1.8 : 1.2, repeat, "bg"));
        out.add(new Orn(Ornaments.circle(cx, cy, R - 3), null, "orn", 0.5));
        out.add(new Orn(Ornaments.circle(cx, cy, band), null, "orn", 0.9));
        out.add(new Orn(Ornaments.circle(cx, cy, inner), null, "orn", 0.5));
        for (double a : new double[] {0, Math.PI}) {
            out.add(new Orn(Ornaments.spoke(cx, cy, band, R - 3, a), null, "orn", 0.8));
        }

        // Birth around the top of the ring, death around the bottom.
        var rf = theme.font("ring");
        double room = (band + R - 3) / 2 * 2.5;
        var top = repeat ? l.repeat().replaceFirst("^\\P{L}+", "").toUpperCase(Locale.ROOT)
                : ringLine("* ", p.event("BIRT").or(() -> p.event("CHR")).or(() -> p.event("BAPM")), room, rf);
        var bottom = repeat ? "" : ringLine("† ", p.event("DEAT").or(() -> p.event("BURI")), room, rf);
        if (bottom.isEmpty() && p.isDeceased() && !repeat) bottom = "†";
        if (!top.isEmpty()) out.add(new Ring(dots(fit(top, room, rf)), "ring", cx, cy, band + 3.3, true));
        if (!bottom.isEmpty()) out.add(new Ring(dots(fit(bottom, room, rf)), "ring", cx, cy, R - 4.6, false));

        // The birth country's flag, round, just outside the ring (below on the root, whose rays fill its upper side).
        var flag = repeat ? Optional.<Flags.Flag>empty() : Flags.forPlace(birthPlace(p));
        double sa = root ? 0.62 : -0.7, sr = R + (root ? 22 : 13), fx = cx + sr * Math.cos(sa), fy = cy + sr * Math.sin(sa);
        flag.ifPresent(f -> {
            double fh = 15, fw = fh * 1.5;
            out.add(new Orn(Ornaments.circle(fx, fy, fh / 2 + 1.6), "paper", "orn", 0.8));
            out.add(new FlagMark(f, fx - fw / 2, fy - fh / 2, fw, fh, Ornaments.Escutcheon.ROUND));
        });

        // Inside: the sign for man or woman, name, years, age, record id.
        Ornaments.sexSign(out, cx, cy - 37, p.sex(), sexKey(p));
        var nm = theme.font("nm");
        var names = nameLines(p, 98, nm);
        double y = names.size() > 1 ? cy - 8 : cy + 1;
        for (var line : names) {
            out.add(new Txt(List.of(new Run(line, "nm", null)), cx, y, "middle", squeeze(line, 98, nm)));
            y += 15.5;
        }
        var years = years(p).replace(" – ", "–");
        if (!years.isEmpty()) out.add(new Txt(List.of(new Run(fit(years, 92, theme.font("yr")), "yr", null)), cx, cy + 23, "middle", 0));
        var age = age(p);
        if (!age.isEmpty()) out.add(new Txt(List.of(new Run(fit(age, 84, theme.font("age")), "age", null)), cx, cy + 35.5, "middle", 0));
        out.add(new Txt(List.of(new Run(p.key().toUpperCase(Locale.ROOT), "id", null)), cx, cy + 47, "middle", 0));
        return out;
    }

    /// `* 12 JUN 1850 · GENOVA, ITALIA`, shortened until it fits `room`.
    private String ringLine(String sign, Optional<Event> e, double room, Theme.Font f) {
        if (e.isEmpty()) return "";
        var date = l.fullDate(e.get().date());
        var year = l.year(e.get().date());
        var parts = List.of(e.get().place().split("\\s*,\\s*")).stream().filter(x -> !x.isBlank()).toList();
        var full = String.join(", ", parts);
        var ends = parts.size() > 2 ? parts.getFirst() + ", " + parts.getLast() : full;
        var first = parts.isEmpty() ? "" : parts.getFirst();
        String last = "";
        for (var cand : List.of(join(" · ", date, full), join(" · ", date, ends), join(" · ", date, first),
                join(" · ", year, ends), join(" · ", year, first), date, year)) {
            if (cand.isEmpty()) continue;
            last = (sign + cand).toUpperCase(Locale.ROOT);
            if (measure(last, f) <= room) return last;
        }
        return last;
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
        return List.of(fit(initials(first, width / 0.86, f), width / 0.86, f), fit(second, width / 0.86, f));
    }

    /// Given names that don't fit keep the first in full and shorten the
    /// rest to initials, as is usual for middle names: `Elizabeth A. M.`,
    /// `Rosalía R.` for `Rosalía de los Remedios`; failing that, initials
    /// throughout: `E. A. M.`
    static String initials(String given, double width, Theme.Font f) {
        if (measure(given, f) <= width) return given;
        var words = given.strip().split("\\s+");
        if (words.length < 2) return given;
        var rest = new StringBuilder();
        for (int i = 1; i < words.length; i++) {
            var w = words[i];
            if (!w.isEmpty() && Character.isLowerCase(w.charAt(0))) continue;   // `de los` in `Rosalía de los Remedios`
            rest.append(' ').append(initial(w));
        }
        var middle = words[0] + rest;
        if (measure(middle, f) <= width) return middle;
        return initial(words[0]) + rest;
    }

    private static String initial(String word) {
        var letter = firstLetter(word);
        return letter.isEmpty() ? word : letter.toUpperCase(Locale.ROOT) + ".";
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
                var ends = d.ends();
                if (ends.size() != 2) yield null;
                var lo = ends.get(0);
                var hi = ends.get(1);
                boolean ok = lo.hasYear() && hi.hasYear() && lo.qualifier() == GedDate.Qualifier.EXACT
                        && hi.qualifier() == GedDate.Qualifier.EXACT && lo.compareTo(hi) <= 0;
                yield ok ? new GedDate[] {lo, hi} : null;
            }
            default -> null;
        };
    }

    /// Whole years from `from` to `to`; month and day are used when both dates have them.
    private static int years(GedDate from, GedDate to) {
        int y = to.astronomicalYear() - from.astronomicalYear();
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

    // ---- page ----------------------------------------------------------------

    /// Room around the chart for the ornate page border.
    double pad() { return theme.ornate() ? 26 : 0; }

    /// Height of the drawn heading (standalone SVG and PNG).
    double headH() { return theme.ornate() ? 108 : 64; }

    double pageW(boolean heading) { return Math.max(width(), heading ? (theme.ornate() ? 640 : 520) : 0) + 2 * pad(); }

    double pageH(boolean heading) { return chart.height() + (heading ? headH() : 0) + 2 * pad(); }

    /// Where the chart itself starts on the page.
    double originY(boolean heading) { return pad() + (heading ? headH() : 0); }

    /// The page border and heading, in page coordinates.
    List<Mark> pageMarks(boolean heading) {
        var out = new ArrayList<Mark>();
        double w = pageW(heading);
        if (theme.sephirot()) out.addAll(Ornaments.plateEdge(w, pageH(heading), "orn"));
        else if (theme.ornate()) out.addAll(Ornaments.border(w, pageH(heading), "orn"));
        if (!heading) return out;
        if (theme.sephirot()) {
            // The name whole, in spaced capitals, over a rule with the plate's half-dark disc.
            double cx = w / 2, top = pad(), ty = top + 50;
            var name = title.toUpperCase(Locale.ROOT);
            out.add(new Txt(List.of(new Run(name, "title", null)), cx, ty, "middle", squeeze(name, w - 120, theme.font("title"))));
            Ornaments.horizon(out, cx, top + 70, 5, 90, "orn", "orn");
            out.add(new Txt(List.of(new Run(subtitle(), "sub", null)), cx, top + 94, "middle", 0));
        } else if (theme.ornate()) {
            double cx = w / 2, top = pad();
            out.add(new Txt(List.of(new Run(title, "title", null)), cx, top + 50, "middle", squeeze(title, w - 120, theme.font("title"))));
            Ornaments.flourish(out, cx, top + 70, "orn");
            out.add(new Txt(List.of(new Run(subtitle(), "sub", null)), cx, top + 94, "middle", 0));
        } else {
            out.add(Txt.of(title, "title", Layout.MARGIN, 38));
            out.add(Txt.of(subtitle(), "sub", Layout.MARGIN, 58));
        }
        return out;
    }

    /// Decoration in chart coordinates: swashes beside the column headings.
    List<Mark> chartMarks() {
        var out = new ArrayList<Mark>();
        if (!theme.baroque() || chart.timeline()) return out;   // a year axis is too dense for swashes
        var f = theme.font("col");
        for (var c : chart.columns()) {
            var label = f.upper() ? c.label().toUpperCase(Locale.ROOT) : c.label();
            Ornaments.swashes(out, c.centerX(), Layout.MARGIN + 6.5, measure(label, f), "orn");
        }
        return out;
    }

    /// Baroque tree: leaves along the connectors, drawn over the lines.
    List<Mark> linkMarks() {
        var out = new ArrayList<Mark>();
        if (theme.sephirot()) return channels();
        if (!theme.ornate() || chart.timeline()) return out;
        var seen = new java.util.HashSet<String>();
        for (var k : chart.links()) {
            double mx = (k.x1() + k.x2()) / 2, r = Math.min(10, Math.abs(k.y2() - k.y1()) / 2);
            Ornaments.vine(out, k.x1(), k.y1(), k.x2(), k.y2(), r, mx, seen, "orn");
        }
        return out;
    }

    /// Whether links and ties are plain lines (else [#linkMarks] draws them).
    boolean plainLinks() { return !theme.sephirot(); }

    /// Half the width of a sephirot channel: every channel is the same width, as on the plate.
    static final double CHANNEL = 5.5;

    /// Sephirot: every link and tie as a double-ruled channel, all the same
    /// width. Where channels cross they open into each other like streets
    /// at a crossroads: each one's rules stop at the other's edge. A
    /// marriage's channel carries its year (and place, when there's room).
    private List<Mark> channels() {
        var out = new ArrayList<Mark>();
        var bands = new ArrayList<Link>(chart.links());
        bands.addAll(chart.ties());
        var quads = bands.stream().map(k -> quad(k, CHANNEL)).toList();
        for (var q : quads) {
            if (q != null) out.add(new Orn("M" + pt(q[0]) + " L" + pt(q[1]) + " L" + pt(q[2]) + " L" + pt(q[3]) + " Z", "paper", null, 0));
        }
        for (int i = 0; i < quads.size(); i++) {
            var q = quads.get(i);
            if (q == null) continue;
            var d = new StringBuilder();
            for (var side : List.of(new double[][] {q[0], q[1]}, new double[][] {q[3], q[2]})) {
                for (var seg : open(side[0], side[1], quads, i)) d.append(d.isEmpty() ? "M" : " M").append(pt(seg[0])).append(" L").append(pt(seg[1]));
            }
            if (!d.isEmpty()) out.add(new Orn(d.toString(), null, "link", 0.9));
        }

        labelChannels(out, quads);

        // A small knot wherever channels meet away from a medallion.
        var ends = new java.util.HashMap<String, double[]>();
        var counts = new java.util.HashMap<String, Integer>();
        for (var k : chart.links()) {
            for (var p : List.of(new double[] {k.x1(), k.y1()}, new double[] {k.x2(), k.y2()})) {
                var key = Math.round(p[0]) + ":" + Math.round(p[1]);
                ends.put(key, p);
                counts.merge(key, 1, Integer::sum);
            }
        }
        counts.forEach((key, n) -> {
            if (n < 2) return;
            var p = ends.get(key);
            out.add(new Orn(Ornaments.circle(p[0], p[1], CHANNEL + 2.5), "paper", "link", 0.9));
            out.add(new Dot(p[0], p[1], 1.6, "link"));
        });

        var byCentre = new java.util.HashMap<String, Card>();
        for (var c : chart.cards()) byCentre.put(Math.round(c.x() + Sephirot.R) + ":" + Math.round(c.y() + Sephirot.R), c);
        var tf = theme.font("tag");
        for (var t : chart.ties()) {
            var a = byCentre.get(Math.round(t.x1() - Sephirot.R - 2) + ":" + Math.round(t.y1()));
            var b = byCentre.get(Math.round(t.x2() + Sephirot.R + 2) + ":" + Math.round(t.y2()));
            if (a == null || b == null) continue;
            var fam = b.family().or(a::family).or(() -> g.familiesAsSpouse(a.person()).stream()
                    .filter(f -> f.partnerOf(a.person().id()).filter(b.person().id()::equals).isPresent()).findFirst());
            if (fam.isEmpty()) continue;
            double room = t.x2() - t.x1() - 12;
            var tag = marriageTag(fam.get()).toUpperCase(Locale.ROOT);
            var place = fam.get().event("MARR").map(Event::place).orElse("").split("\\s*,\\s*")[0].toUpperCase(Locale.ROOT);
            var year = tag.replaceFirst(" · .*", "");
            String text = null;
            for (var cand : List.of(place.isBlank() ? tag : tag + " · " + place, tag, year)) {
                if (measure(cand, tf) <= room) { text = cand; break; }
            }
            if (text == null) continue;
            out.add(new Txt(List.of(new Run(dots(text), "tag", null)), (t.x1() + t.x2()) / 2, t.y1() + 2.9, "middle", 0));
        }
        return out;
    }

    /// Writes along each channel how the people it joins are related. Above the root it
    /// says what the upper end is to the lower (`FATHER`, `GREAT-GRANDMOTHER`); below, what
    /// the child is to its parents (`SON`, `DAUGHTER`), on the slanting part of the fan that
    /// leads down to the child, or on the whole channel when the child hangs straight below.
    /// Set in the longest stretch clear of crossings, knots and flags; left out where there's no room.
    private void labelChannels(List<Mark> out, List<double[][]> quads) {
        double R = Sephirot.R, rim = R + 2;
        var sf = theme.font("chan");
        double rootY = chart.cards().stream().filter(c -> c.kind() == Kind.ROOT).mapToDouble(Card::y).findFirst().orElse(0);
        var links = chart.links();
        for (int i = 0; i < links.size(); i++) {
            var k = links.get(i);
            Card a = null, b = null, dropA = null, dropB = null;
            for (var c : chart.cards()) {
                double cx = c.x() + R, cy = c.y() + R;
                if (Math.abs(Math.hypot(k.x1() - cx, k.y1() - cy) - rim) < 0.5) a = c;
                if (Math.abs(Math.hypot(k.x2() - cx, k.y2() - cy) - rim) < 0.5) b = c;
                // Where a fan's channel turns to drop straight into this child.
                if (Math.abs(k.x1() - cx) < 0.5 && Math.abs(k.y1() - (cy - Sephirot.DROP)) < 0.5) dropA = c;
                if (Math.abs(k.x2() - cx) < 0.5 && Math.abs(k.y2() - (cy - Sephirot.DROP)) < 0.5) dropB = c;
            }
            String text;
            if (a != null && b != null && a != b) {
                var upper = a.y() < b.y() ? a : b;
                var lower = upper == a ? b : a;
                int gens = (int) Math.round((lower.y() - upper.y()) / Sephirot.ROW);
                if (gens < 1) continue;
                text = lower.y() > rootY + 1 ? l.relation(gens, false, lower.person().sex())   // a descendant
                        : l.relation(gens, true, upper.person().sex());
            } else if (a == null && b == null && (dropA != null || dropB != null)) {
                var child = dropB != null ? dropB : dropA;
                text = l.relation(1, false, child.person().sex());
            } else if ((a == null) != (b == null) && (a != null ? a : b).y() > rootY + 1) {
                var child = a != null ? a : b;
                double other = a != null ? k.y2() : k.y1();
                if (other > child.y()) continue;                 // must come down into the child
                if (dropA == child || dropB == child) continue;  // the short drop of a fan: labelled above it
                text = l.relation(1, false, child.person().sex());
            } else {
                continue;
            }
            text = text.toUpperCase(Locale.ROOT);
            double need = measure(text, sf) + 14;
            // The centre line, kept clear of the flags at a medallion end and the knots elsewhere.
            double len = Math.hypot(k.x2() - k.x1(), k.y2() - k.y1());
            // (A child is entered from straight above, clear of its flag, so it needs less.)
            boolean down = (a != null ? a : b) != null && (a != null ? a : b).y() > rootY + 1;
            double cardEnd = down ? 10 : 26;
            double t0 = (a != null ? cardEnd : 12) / len, t1 = (b != null ? cardEnd : 12) / len;
            if (len * (1 - t0 - t1) < need) continue;
            double[] p0 = lerp(new double[] {k.x1(), k.y1()}, new double[] {k.x2(), k.y2()}, t0);
            double[] p1 = lerp(new double[] {k.x1(), k.y1()}, new double[] {k.x2(), k.y2()}, 1 - t1);
            double[][] best = null;
            double bestLen = 0;
            for (var seg : open(p0, p1, quads, i)) {
                double sl = Math.hypot(seg[1][0] - seg[0][0], seg[1][1] - seg[0][1]);
                if (sl > bestLen) { bestLen = sl; best = seg; }
            }
            if (best == null || bestLen < need) continue;
            double angle = Math.atan2(k.y2() - k.y1(), k.x2() - k.x1());
            if (angle > Math.PI / 2) angle -= Math.PI;       // keep the letters upright
            else if (angle <= -Math.PI / 2) angle += Math.PI;
            out.add(new Slant(text, "chan", (best[0][0] + best[1][0]) / 2, (best[0][1] + best[1][1]) / 2, angle));
        }
    }

    /// The plate's separator: Baskerville's own middle dot sits off-centre, the dot operator doesn't.
    private static String dots(String s) { return s.replace('·', '∙'); }

    private static String pt(double[] p) { return n(p[0]) + " " + n(p[1]); }

    /// The band `half` either side of line k, as its four corners in order; null for a line too short to draw.
    static double[][] quad(Link k, double half) {
        double len = Math.hypot(k.x2() - k.x1(), k.y2() - k.y1());
        if (len < 1) return null;
        double nx = -(k.y2() - k.y1()) / len * half, ny = (k.x2() - k.x1()) / len * half;
        return new double[][] {{k.x1() + nx, k.y1() + ny}, {k.x2() + nx, k.y2() + ny},
                {k.x2() - nx, k.y2() - ny}, {k.x1() - nx, k.y1() - ny}};
    }

    /// The parts of segment a–b lying outside every band but `self`.
    static List<double[][]> open(double[] a, double[] b, List<double[][]> quads, int self) {
        var cuts = new ArrayList<double[]>();
        for (int j = 0; j < quads.size(); j++) {
            if (j == self || quads.get(j) == null) continue;
            var t = inside(a, b, quads.get(j));
            if (t != null) cuts.add(t);
        }
        cuts.sort(java.util.Comparator.comparingDouble(c -> c[0]));
        var out = new ArrayList<double[][]>();
        double from = 0;
        for (var c : cuts) {
            if (c[0] > from) out.add(new double[][] {lerp(a, b, from), lerp(a, b, c[0])});
            from = Math.max(from, c[1]);
        }
        if (from < 1) out.add(new double[][] {lerp(a, b, from), b});
        out.removeIf(s -> Math.hypot(s[1][0] - s[0][0], s[1][1] - s[0][1]) < 0.5);
        return out;
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    /// Where along a–b (0–1) the segment runs strictly inside convex quad q, or null (Cyrus–Beck clipping).
    private static double[] inside(double[] a, double[] b, double[][] q) {
        double lo = 0, hi = 1, dx = b[0] - a[0], dy = b[1] - a[1];
        // Which way round the corners go decides which side of each edge is inside.
        double area = 0;
        for (int i = 0; i < 4; i++) area += q[i][0] * q[(i + 1) % 4][1] - q[(i + 1) % 4][0] * q[i][1];
        double turn = Math.signum(area);
        for (int i = 0; i < 4; i++) {
            var p = q[i];
            var r = q[(i + 1) % 4];
            double ex = r[0] - p[0], ey = r[1] - p[1];
            double nx = -ey * turn, ny = ex * turn;   // inward normal
            double num = nx * (a[0] - p[0]) + ny * (a[1] - p[1]) - 0.05 * Math.hypot(nx, ny);
            double den = nx * dx + ny * dy;
            if (Math.abs(den) < 1e-12) {
                if (num < 0) return null;
                continue;
            }
            double t = -num / den;
            if (den > 0) lo = Math.max(lo, t);
            else hi = Math.min(hi, t);
            if (lo >= hi) return null;
        }
        return new double[] {lo, hi};
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
        double w = pageW(heading), h = pageH(heading), ox = pad(), oy = originY(heading);
        var sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(n(w)).append(' ').append(n(h))
                .append("\" width=\"").append(n(w)).append("\" height=\"").append(n(h)).append("\" role=\"img\" aria-label=\"")
                .append(esc(title)).append("\">\n");
        sb.append(flagDefs(mode));
        if (heading) sb.append("<rect width=\"100%\" height=\"100%\" style=\"fill:").append(color("paper", mode)).append("\"/>\n");
        for (var m : pageMarks(heading)) paint(sb, m, mode);
        boolean shifted = ox != 0 || oy != 0;
        if (shifted) sb.append("\n<g transform=\"translate(").append(n(ox)).append(',').append(n(oy)).append(")\">\n");
        for (var m : chartMarks()) paint(sb, m, mode);
        for (var c : chart.columns()) {
            var label = theme.font("col").upper() ? c.label().toUpperCase(Locale.ROOT) : c.label();
            sb.append("<text x=\"").append(n(c.centerX())).append("\" y=\"").append(n(Layout.MARGIN + 10))
                    .append("\" text-anchor=\"middle\" style=\"").append(fontStyle("col", null, mode)).append("\">")
                    .append(esc(label)).append("</text>\n");
        }
        for (var gd : chart.guides()) {
            sb.append("<line x1=\"").append(n(gd.x())).append("\" y1=\"").append(n(gd.y1())).append("\" x2=\"").append(n(gd.x()))
                    .append("\" y2=\"").append(n(gd.y2())).append("\" style=\"stroke:").append(color(gd.today() ? "accent" : "line", mode))
                    .append(gd.today() ? ";stroke-width:1.5;stroke-dasharray:5 4" : ";stroke-width:1").append("\"/>\n");
        }
        for (var t : plainLinks() ? chart.ties() : List.<Link>of()) {
            sb.append("<line x1=\"").append(n(t.x1())).append("\" y1=\"").append(n(t.y1())).append("\" x2=\"").append(n(t.x2()))
                    .append("\" y2=\"").append(n(t.y2())).append("\" style=\"stroke:").append(color("accent", mode))
                    .append(";stroke-width:2;stroke-dasharray:2 2\"/>\n");
        }
        var linkStyle = "fill:none;stroke:" + color("link", mode) + ";stroke-width:" + (theme.ornate() ? "1.2" : "1.6");
        for (var lk : plainLinks() ? chart.links() : List.<Link>of()) sb.append("<path d=\"").append(elbow(lk)).append("\" style=\"").append(linkStyle).append("\"/>\n");
        for (var m : linkMarks()) paint(sb, m, mode);
        for (var c : chart.cards()) card(sb, c, mode);
        if (shifted) sb.append("</g>\n");
        return sb.append("</svg>\n").toString();
    }

    private void card(StringBuilder sb, Card c, Colors mode) {
        var p = c.person();
        var cls = switch (c.kind()) { case ROOT -> " root"; case REPEAT -> " repeat"; default -> ""; };
        sb.append("<g class=\"card").append(cls).append("\" data-id=\"").append(esc(p.key()))
                .append("\" transform=\"translate(").append(n(c.x())).append(',').append(n(c.y())).append(")\"")
                .append(c.kind() == Kind.REPEAT ? " opacity=\".8\"" : "").append(">");
        sb.append("<title>").append(esc(p.name().full().isEmpty() ? l.unnamed() : p.name().full())).append("</title>");
        for (var m : marks(c)) paint(sb, m, mode);
        sb.append("</g>\n");
    }

    private int ringIds;

    /// Browsers already set Baskerville's asterisk mid-line; Java2D draws it higher.
    private static final double svgStarDrop = 0;

    /// How far to lower a birth asterisk so it sits mid-line rather than at cap height.
    static double starDrop(Theme.Font f) { return Math.round(f.size() * 0.15 * 10) / 10.0; }

    private void paint(StringBuilder sb, Mark m, Colors mode) {
        switch (m) {
            case Box b -> {
                sb.append("<rect").append(b.cls() == null ? "" : " class=\"" + b.cls() + "\"")
                        .append(" x=\"").append(n(b.x())).append("\" y=\"").append(n(b.y()))
                        .append("\" width=\"").append(n(b.w())).append("\" height=\"").append(n(b.h()))
                        .append("\" rx=\"").append(n(b.r())).append("\" style=\"fill:").append(b.fill() == null ? "none" : color(b.fill(), mode));
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
                    .append(n(f.h())).append('"')
                    .append(f.clip() == null ? "" : " clip-path=\"url(#arms-" + f.clip().id() + ")\"")
                    .append("><title>").append(esc(f.flag().country())).append("</title></use>");
            case Orn o -> {
            sb.append("<path").append(o.cls() == null ? "" : " class=\"" + o.cls() + "\"").append(" d=\"").append(o.d())
                    .append("\" style=\"fill:").append(o.fill() == null ? "none" : color(o.fill(), mode));
            if (o.stroke() != null) {
                sb.append(";stroke:").append(color(o.stroke(), mode)).append(";stroke-width:").append(n(o.strokeW()))
                        .append(";stroke-linecap:round;stroke-linejoin:round");
                if (o.dashed()) sb.append(";stroke-dasharray:5 4");
            }
            sb.append("\"/>");
        }
            case Slant t -> {
                var f = theme.font(t.style());
                double deg = Math.toDegrees(t.angle());
                sb.append("<text x=\"").append(n(t.x())).append("\" y=\"").append(n(t.y() + f.size() * 0.34))
                        .append("\" text-anchor=\"middle\" transform=\"rotate(").append(n(deg)).append(' ').append(n(t.x())).append(' ')
                        .append(n(t.y())).append(")\" style=\"").append(fontStyle(t.style(), null, mode)).append("\">")
                        .append(esc(t.text())).append("</text>");
            }
            case Ring r -> {
                if (r.text().isEmpty()) break;
                var id = "ring" + (++ringIds);
                double cx = r.cx(), cy = r.cy(), rr = r.r();
                // Top: left to right over the top; bottom: left to right under the bottom.
                sb.append("<path id=\"").append(id).append("\" d=\"M").append(n(cx - rr)).append(' ').append(n(cy))
                        .append("A").append(n(rr)).append(' ').append(n(rr)).append(" 0 0 ").append(r.top() ? 1 : 0).append(' ')
                        .append(n(cx + rr)).append(' ').append(n(cy)).append("\" style=\"fill:none\"/>");
                sb.append("<text style=\"").append(fontStyle(r.style(), null, mode))
                        .append("\"><textPath href=\"#").append(id).append("\" startOffset=\"50%\" text-anchor=\"middle\">");
                if (r.text().startsWith("*") && svgStarDrop > 0) {
                    // An asterisk sits up at cap height; bring the birth sign down to the middle of the line.
                    var drop = n(svgStarDrop);
                    sb.append("<tspan dy=\"").append(drop).append("\">*</tspan><tspan dy=\"-").append(drop).append("\">")
                            .append(esc(r.text().substring(1))).append("</tspan>");
                } else {
                    sb.append(esc(r.text()));
                }
                sb.append("</textPath></text>");
            }
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

    /// One `<symbol>` per flag used on the chart, referenced by the cards.
    private String flagDefs(Colors mode) {
        var used = new TreeMap<String, Flags.Flag>();
        var clips = new java.util.TreeSet<Ornaments.Escutcheon>();
        for (var c : chart.cards()) {
            for (var m : marks(c)) {
                if (m instanceof FlagMark f) {
                    used.put(f.flag().code(), f.flag());
                    if (f.clip() != null) clips.add(f.clip());
                }
            }
        }
        if (used.isEmpty()) return "";
        double r = theme.modern() ? 1.6 : 0;
        var sb = new StringBuilder("<defs>\n<clipPath id=\"flag-clip\"><rect width=\"18\" height=\"12\" rx=\"")
                .append(n(r)).append("\"/></clipPath>\n");
        // Escutcheon outlines in the flag's own box (0–1), so one clip fits every size.
        for (var e : clips) {
            sb.append("<clipPath id=\"arms-").append(e.id()).append("\" clipPathUnits=\"objectBoundingBox\"><path d=\"")
                    .append(e.path(0, 0, 1, 1)).append("\"/></clipPath>\n");
        }
        for (var f : used.values()) {
            sb.append("<symbol id=\"flag-").append(f.code()).append("\" viewBox=\"0 0 18 12\" preserveAspectRatio=\"none\"><g clip-path=\"url(#flag-clip)\">");
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
                .replace("{{themeCss}}", theme.ornate()
                        ? (ORNATE_CSS + (theme.sephirot() ? SEPHIROT_CSS : ""))
                                .replace("{{old}}", theme.sephirot() ? Theme.PLAIN_CSS : Theme.OLD_CSS).replace("{{script}}", Theme.SCRIPT_CSS)
                        : "")
                .replace("{{titleOrnament}}", theme.ornate() ? titleOrnament() : "")
                .replace("{{deco}}", theme.baroque() ? cornerDeco() : "")
                .replace("{{bodyClass}}", theme.sephirot() ? " class=\"ornate sephirot\"" : theme.ornate() ? " class=\"ornate\"" : "")
                .replace("{{hideButton}}", theme.ornate()
                        ? "<button id=\"hd\" aria-pressed=\"false\" aria-label=\"" + esc(l.hideControls()) + "\" title=\""
                          + esc(l.hideControls()) + "\">" + (theme.sephirot() ? "☉" : "❦") + "\uFE0E</button>" : "")
                .replace("{{toolsHint}}", theme.ornate() ? "<div class=\"thint\">" + esc(l.hint()) + "</div>" : "")
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
                .replace("{{extraLegend}}", chart.timeline()
                        ? "<span><i style=\"background:var(--c-unknownBg)\"></i>" + esc(l.estimated()) + "</span>" : "")
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

    /// The flourish under the HTML page's title.
    private String titleOrnament() {
        var marks = new ArrayList<Mark>();
        if (theme.sephirot()) Ornaments.horizon(marks, 120, 10, 6, 70, "orn", "orn");
        else Ornaments.flourish(marks, 120, 10, "orn");
        var sb = new StringBuilder("<svg class=\"orn\" viewBox=\"0 0 240 20\" width=\"240\" height=\"20\" aria-hidden=\"true\">");
        for (var m : marks) paint(sb, m, Colors.VARS);
        return sb.append("</svg>").toString();
    }

    /// Scrollwork for the four corners of a floating panel (mirrored by CSS).
    private String cornerDeco() {
        var marks = new ArrayList<Mark>();
        Ornaments.corner(marks, 1.5, 1.5, 1.25, 1.25, "orn", 0.9);
        var sb = new StringBuilder("<svg viewBox=\"0 0 28 28\" width=\"28\" height=\"28\">");
        for (var m : marks) paint(sb, m, Colors.VARS);
        var one = sb.append("</svg>").toString();
        var out = new StringBuilder();
        for (var pos : List.of("tl", "tr", "bl", "br")) out.append("<span class=\"cn ").append(pos).append("\" aria-hidden=\"true\">").append(one).append("</span>");
        return out.toString();
    }

    private static final String ORNATE_CSS = """
              body{font-family:{{old}}}
              .legend{display:none}
              /* Title plaque and controls float over the chart, framed like the cards. */
              header{position:fixed;top:18px;left:0;right:0;z-index:5;display:block;padding:0;border:none;pointer-events:none}
              .plate,.tools{pointer-events:auto;background:color-mix(in srgb,var(--c-panel) 95%,transparent);border:1px solid var(--c-cardLine);border-radius:3px;
                box-shadow:0 0 0 4px var(--c-panel),0 0 0 5px var(--c-orn),0 12px 30px rgba(0,0,0,.25);transition:opacity .25s,transform .25s,visibility .25s}
              .plate{position:relative;width:max-content;max-width:calc(100vw - 600px);min-width:min(420px,calc(100vw - 600px));margin:0 auto;text-align:center;padding:14px 56px 12px}
              h1{font:italic 700 34px/1.15 {{script}};letter-spacing:0}
              .sub{font-style:italic;font-size:14px}
              svg.orn{display:block;margin:2px auto 3px}
              .tools{position:fixed;top:18px;right:78px;flex-direction:column;align-items:stretch;gap:8px;padding:22px 22px 16px}
              .tools .row{justify-content:center}
              .thint{font-style:italic;font-size:12px;color:var(--c-muted);text-align:center;max-width:220px;line-height:1.3}
              .cn{position:absolute;width:28px;height:28px;pointer-events:none;line-height:0}
              .cn.tl{top:2px;left:2px}.cn.tr{top:2px;right:2px;transform:scaleX(-1)}
              .cn.bl{bottom:2px;left:2px;transform:scaleY(-1)}.cn.br{bottom:2px;right:2px;transform:scale(-1,-1)}
              #hd{position:fixed;top:22px;right:20px;z-index:6;width:42px;height:42px;min-width:0;border-radius:50%;padding:0;font-size:22px;line-height:1;
                color:var(--c-orn);background:var(--c-panel);border:1px solid var(--c-cardLine);box-shadow:0 0 0 3px var(--c-panel),0 0 0 4px var(--c-orn),0 6px 16px rgba(0,0,0,.2)}
              #hd[aria-pressed=true]{color:var(--c-muted)}
              body.bare .plate,body.bare .tools,body.scrolled .plate{opacity:0;visibility:hidden;transform:translateY(-10px)}
              @media (max-width:900px){
                .plate{max-width:calc(100vw - 150px);min-width:0;padding:10px 30px}
                .sub{font-size:12.5px}
                h1{font-size:25px}
                .tools{top:auto;bottom:16px;right:16px}
                .thint{display:none}
              }
              aside{border-color:var(--c-cardLine);border-radius:4px;box-shadow:0 0 0 4px var(--c-panel),0 0 0 5px var(--c-orn),0 14px 34px rgba(0,0,0,.28)}
              aside h2{font:600 22px/1.25 {{old}}}
              aside h3{font-style:italic;letter-spacing:.12em;color:var(--c-orn)}
              button{border-radius:3px;font-style:italic}
              .card:hover .bg{stroke-width:1.8px}
              @media print{
                header{position:static}
                .plate{box-shadow:none;margin:0 auto 8px}
                #hd{display:none!important}
              }
            """;

    /// Sephirot: an engraved plate, so square panels and tracked capitals instead of script.
    private static final String SEPHIROT_CSS = """
              h1{font:600 24px/1.25 {{old}};letter-spacing:.2em;text-transform:uppercase}
              .plate,.tools,aside,#hd{border-radius:0}
              .plate,.tools{box-shadow:0 0 0 3px var(--c-panel),0 0 0 4px var(--c-orn),0 12px 30px rgba(0,0,0,.35)}
              button{font-style:normal;letter-spacing:.06em}
              aside h3{font-style:normal;letter-spacing:.2em}
              @media (max-width:900px){h1{font-size:19px}}
            """;

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
              .tools .row{display:flex;gap:6px;align-items:center}
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
            {{themeCss}}
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
            <body{{bodyClass}}>
            <header>
              <div class="plate">{{deco}}<h1>{{title}}</h1>{{titleOrnament}}<div class="sub">{{subtitle}}</div></div>
              <div class="tools">{{deco}}
                <span class="row"><button id="zo" aria-label="Zoom out">−</button><span id="zl">100%</span><button id="zi" aria-label="Zoom in">+</button></span>
                <span class="row"><button id="zf">{{fit}}</button>{{themeButton}}<button id="pr">{{print}}</button></span>{{toolsHint}}
              </div>
            </header>
            {{hideButton}}
            <div class="legend">
              <span><i style="background:var(--c-male)"></i>{{male}}</span>
              <span><i style="background:var(--c-female)"></i>{{female}}</span>
              <span><i class="dash"></i>{{repeat}}</span>{{extraLegend}}
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
              // Baroque: the title plaque slips away while scrolling down, and returns on the way back up.
              if (document.body.classList.contains('ornate')) {
                var lastTop = 0, armed = false;
                setTimeout(function(){ lastTop = wrap.scrollTop; armed = true; }, 400);
                wrap.addEventListener('scroll', function(){
                  if (!armed) return;
                  var t = wrap.scrollTop, d = t - lastTop;
                  if (Math.abs(d) < 12 && t > 40) return;
                  document.body.classList.toggle('scrolled', d > 0 && t > 40);
                  lastTop = t;
                }, {passive: true});
              }
              var hd = document.getElementById('hd');
              if (hd) hd.onclick = function(){
                var bare = document.body.classList.toggle('bare');
                hd.setAttribute('aria-pressed', bare ? 'true' : 'false');
              };
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
