package ged.chart;

import ged.chart.Layout.Card;
import ged.chart.Layout.Chart;
import ged.chart.Layout.Column;
import ged.chart.Layout.Guide;
import ged.chart.Layout.Kind;
import ged.chart.Layout.Link;
import ged.model.Event;
import ged.model.Family;
import ged.model.GedDate;
import ged.model.Genealogy;
import ged.model.Person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/// Places people on a time axis: one row per person, a bar from birth to
/// death (or to today for the living), so longer lives are longer bars.
/// A child's row branches off its parents' rows at the year it was born.
///
/// Rows run top to bottom in tree order. Ancestors are in-order — father's
/// side above a person, mother's side below — so both parents sit near
/// the child they join at. Descendants follow their parent, each partner
/// with that couple's children beneath.
///
/// Missing dates are estimated from relatives (a child is born ~28 years
/// after a parent, partners are close in age); the estimated stretch of a
/// bar is drawn pale. Someone who died on an unknown date fades out after
/// the last thing known about them.
public final class Timeline {
    /// Pixels per year, row height, and where the bar sits in a row.
    public static final double PX = 7, ROW = 46, BAR_Y = 24, BAR_H = 10, LABEL_X = 8;
    static final double GENERATION = 28, LIVING_MAX = 105, LIVING_GUESS_MAX = 90, FADE = 15, MIN_BAR = 4;

    /// A row's bar in row coordinates (0 = the row's leftmost point, an
    /// estimated birth). Solid between `solidFrom` and `solidTo`, pale
    /// before and after up to `end`. `branches` are the x's where
    /// children's lines leave this bar.
    public record Span(double solidFrom, double solidTo, double end, boolean living, List<Double> branches) {}

    private record Row(Person person, Kind kind, Optional<Family> family) {}

    /// Lifetime in fractional years; `from`/`to` bound the solid (known) part.
    private record Life(double start, double from, double to, double end, boolean living) {}

    private final Genealogy g;
    private final Layout.Spec spec;
    private final double now;
    private final List<Row> rows = new ArrayList<>();
    private final List<int[]> parentLinks = new ArrayList<>();   // {parentRow, childRow}
    private final List<int[]> partnerTies = new ArrayList<>();   // {personRow, partnerRow}
    private final Set<String> seen = new HashSet<>();
    private final Map<String, Double> births = new HashMap<>();
    private int up, down;

    private Timeline(Genealogy g, Layout.Spec spec, LocalDate today) {
        this.g = g;
        this.spec = spec;
        this.now = today.getYear() + (today.getDayOfYear() - 1) / 365.25;
    }

    public static Chart build(Genealogy g, Person root, Layout.Spec spec, LocalDate today) {
        return new Timeline(g, spec, today).build(root);
    }

    // ---- rows ----------------------------------------------------------------

    private int add(Person p, Kind k, Optional<Family> f) {
        rows.add(new Row(p, k, f));
        return rows.size() - 1;
    }

    /// In-order: father's ancestors, the person, mother's ancestors. The
    /// root's descendants go right under the root.
    private int ancestors(Person p, int gen, Kind kind) {
        seen.add(p.id());
        up = Math.max(up, gen);
        int[] parent = {-1, -1};
        var father = gen < spec.up() ? g.father(p) : Optional.<Person>empty();
        var mother = gen < spec.up() ? g.mother(p) : Optional.<Person>empty();
        if (father.isPresent()) parent[0] = parent(father.get(), gen);
        int me = add(p, kind, Optional.empty());
        if (kind == Kind.ROOT) descendants(p, me, 0);
        if (mother.isPresent()) parent[1] = parent(mother.get(), gen);
        for (int pr : parent) if (pr >= 0) parentLinks.add(new int[] {pr, me});
        return me;
    }

    private int parent(Person q, int gen) {
        if (seen.contains(q.id())) {
            up = Math.max(up, gen + 1);
            return add(q, Kind.REPEAT, Optional.empty());
        }
        return ancestors(q, gen + 1, Kind.PERSON);
    }

    private void descendants(Person p, int me, int gen) {
        down = Math.max(down, gen);
        if (gen >= spec.down()) return;
        if (!spec.spouses()) {
            for (var k : byBirth(g.children(p).stream().distinct().toList())) child(k, me, gen);
            return;
        }
        var fams = new ArrayList<>(g.familiesAsSpouse(p));
        fams.sort(Comparator.comparingDouble(this::familyYear));
        for (var f : fams) {
            int from = me;
            var partner = f.partnerOf(p.id()).flatMap(g::person);
            if (partner.isPresent()) {
                var q = partner.get();
                from = add(q, seen.add(q.id()) ? Kind.SPOUSE : Kind.REPEAT, Optional.of(f));
                partnerTies.add(new int[] {me, from});
            }
            var kids = f.children().stream().flatMap(id -> g.person(id).stream()).toList();
            for (var k : byBirth(kids)) child(k, from, gen);
        }
    }

    private void child(Person k, int from, int gen) {
        if (seen.contains(k.id())) {
            down = Math.max(down, gen + 1);
            parentLinks.add(new int[] {from, add(k, Kind.REPEAT, Optional.empty())});
            return;
        }
        seen.add(k.id());
        int row = add(k, Kind.PERSON, Optional.empty());
        parentLinks.add(new int[] {from, row});
        descendants(k, row, gen + 1);
    }

    private List<Person> byBirth(List<Person> ps) {
        var sorted = new ArrayList<>(ps);
        sorted.sort(Comparator.comparingDouble(p -> year(p.birth()).orElse(Double.MAX_VALUE)));
        return sorted;
    }

    private double familyYear(Family f) {
        var marr = f.event("MARR").map(Event::date).flatMap(d -> boxed(year(d)));
        if (marr.isPresent()) return marr.get();
        return f.children().stream().flatMap(id -> g.person(id).stream())
                .mapToDouble(k -> year(k.birth()).orElse(Double.MAX_VALUE)).min().orElse(Double.MAX_VALUE);
    }

    // ---- dates -----------------------------------------------------------------

    /// A date as a fractional year (mid-month, mid-year when vague).
    static OptionalDouble year(GedDate d) {
        if (!d.hasYear()) return OptionalDouble.empty();
        if (d.month() == 0) return OptionalDouble.of(d.astronomicalYear() + 0.5);
        return OptionalDouble.of(d.astronomicalYear() + (d.month() - 1) / 12.0 + (d.day() > 0 ? (d.day() - 1) / 365.25 : 1 / 24.0));
    }

    private static Optional<Double> boxed(OptionalDouble d) {
        return d.isPresent() ? Optional.of(d.getAsDouble()) : Optional.empty();
    }

    /// A date good enough to draw solid: exact, about, or a between range.
    private static boolean firm(GedDate d) {
        return d.hasYear() && switch (d.qualifier()) {
            case EXACT, ABOUT, BETWEEN -> true;
            default -> false;
        };
    }

    private static OptionalDouble recordedBirth(Person p) {
        for (var tag : List.of("BIRT", "CHR", "BAPM")) {
            var d = p.event(tag).map(Event::date).orElse(GedDate.EMPTY);
            if (d.hasYear()) return year(d);
        }
        return OptionalDouble.empty();
    }

    private static boolean firmBirth(Person p) {
        for (var tag : List.of("BIRT", "CHR", "BAPM")) {
            var d = p.event(tag).map(Event::date).orElse(GedDate.EMPTY);
            if (d.hasYear()) return firm(d);
        }
        return false;
    }

    private static OptionalDouble recordedDeath(Person p) {
        for (var tag : List.of("DEAT", "BURI", "CREM")) {
            var d = p.event(tag).map(Event::date).orElse(GedDate.EMPTY);
            if (d.hasYear()) return year(d);
        }
        return OptionalDouble.empty();
    }

    /// Birth year, recorded or estimated from relatives; empty if there's nothing to go on.
    private OptionalDouble birth(Person p) {
        var b = births.get(p.id());
        if (b != null) return OptionalDouble.of(b);
        return recordedBirth(p);
    }

    /// Fills in births for everyone on the chart, letting estimates spread
    /// along the family a few steps (a great-grandparent with no dates
    /// gets one from a grandchild's).
    private void estimateBirths() {
        for (var r : rows) recordedBirth(r.person).ifPresent(y -> births.put(r.person.id(), y));
        for (int pass = 0; pass < 8; pass++) {
            boolean changed = false;
            for (var r : rows) {
                var p = r.person;
                if (births.containsKey(p.id())) continue;
                var est = guess(p);
                if (est.isPresent()) {
                    births.put(p.id(), est.getAsDouble());
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }

    private OptionalDouble guess(Person p) {
        var partners = g.spouses(p).stream().map(this::birth).filter(OptionalDouble::isPresent)
                .mapToDouble(OptionalDouble::getAsDouble).average();
        if (partners.isPresent()) return partners;
        var kids = g.children(p).stream().map(this::birth).filter(OptionalDouble::isPresent)
                .mapToDouble(OptionalDouble::getAsDouble).min();
        if (kids.isPresent()) return OptionalDouble.of(kids.getAsDouble() - GENERATION);
        var parents = g.parents(p).stream().map(this::birth).filter(OptionalDouble::isPresent)
                .mapToDouble(OptionalDouble::getAsDouble).average();
        if (parents.isPresent()) return OptionalDouble.of(parents.getAsDouble() + GENERATION);
        var marr = g.familiesAsSpouse(p).stream().flatMap(f -> f.event("MARR").stream())
                .map(e -> year(e.date())).filter(OptionalDouble::isPresent).mapToDouble(OptionalDouble::getAsDouble).min();
        if (marr.isPresent()) return OptionalDouble.of(marr.getAsDouble() - 25);
        var death = recordedDeath(p);
        if (death.isPresent()) return OptionalDouble.of(death.getAsDouble() - 60);
        var first = evidence(p).min();
        return first.isPresent() ? OptionalDouble.of(first.getAsDouble() - 20) : OptionalDouble.empty();
    }

    /// Years of everything recorded about a person while alive: own events,
    /// marriages, children's births.
    private java.util.stream.DoubleStream evidence(Person p) {
        var own = p.events().stream().filter(e -> !List.of("DEAT", "BURI", "CREM", "PROB", "WILL").contains(e.tag()))
                .map(e -> year(e.date()));
        var fams = g.familiesAsSpouse(p).stream().flatMap(f -> f.event("MARR").stream()).map(e -> year(e.date()));
        var kids = g.children(p).stream().map(k -> recordedBirth(k));
        return java.util.stream.Stream.of(own, fams, kids).flatMap(s -> s)
                .filter(OptionalDouble::isPresent).mapToDouble(OptionalDouble::getAsDouble);
    }

    private Life life(Person p) {
        double start = births.get(p.id());
        boolean firmStart = firmBirth(p);
        var known = evidence(p).filter(y -> y >= start - 1).toArray();
        double firstKnown = java.util.Arrays.stream(known).min().orElse(start);
        double lastKnown = java.util.Arrays.stream(known).max().orElse(start);

        var death = recordedDeath(p);
        // An estimated birth is only a guess: don't assume a very old person is still alive.
        boolean living = !p.isDeceased() && death.isEmpty() && now - start <= (firmStart ? LIVING_MAX : LIVING_GUESS_MAX);
        double to, end;
        if (death.isPresent()) {
            to = end = Math.max(death.getAsDouble(), start);
            if (!firmStart) firstKnown = Math.min(firstKnown, to);
        } else if (living) {
            to = end = now;
            if (!firmStart) firstKnown = Math.min(firstKnown, now);
        } else {
            to = Math.max(start, lastKnown);
            end = Math.min(now, Math.max(to + FADE, start + 40));
        }
        double from = firmStart ? start : Math.min(Math.max(firstKnown, start), to);
        if (!firmStart && known.length == 0 && death.isEmpty()) from = to = start;   // nothing known: all pale
        if (end - start < MIN_BAR / PX) end = start + MIN_BAR / PX;
        return new Life(start, from, Math.max(to, from), Math.max(end, to), living);
    }

    // ---- geometry --------------------------------------------------------------

    private Chart build(Person root) {
        ancestors(root, 0, Kind.ROOT);
        estimateBirths();
        var missing = rows.stream().filter(r -> !births.containsKey(r.person.id())).toList();
        if (missing.size() == rows.size()) {
            throw new IllegalArgumentException("no birth, death or other dates to place anyone on a timeline");
        }
        // Undated and unconnected to anyone dated: leave them off rather than guess wildly.
        var lives = new ArrayList<Life>();
        var keep = new ArrayList<Integer>();
        var remap = new int[rows.size()];
        java.util.Arrays.fill(remap, -1);
        for (int i = 0; i < rows.size(); i++) {
            if (!births.containsKey(rows.get(i).person.id())) continue;
            remap[i] = keep.size();
            keep.add(i);
            lives.add(life(rows.get(i).person));
        }

        double first = lives.stream().mapToDouble(Life::start).min().orElse(now);
        int startYear = (int) Math.floor(first / 10) * 10;
        double x0 = Layout.MARGIN + 6;
        double top = Layout.MARGIN + Layout.HEADER;

        var branches = new ArrayList<List<Double>>();
        for (int i = 0; i < keep.size(); i++) branches.add(new ArrayList<>());
        var links = new ArrayList<Link>();
        for (var pl : parentLinks) {
            int a = remap[pl[0]], b = remap[pl[1]];
            if (a < 0 || b < 0) continue;
            double x = x(lives.get(b).start, startYear, x0);
            links.add(new Link(x, mid(a, top), x, mid(b, top)));
            var pa = lives.get(a);
            double px = x(pa.start, startYear, x0);
            if (x >= px && x <= x(pa.end, startYear, x0)) branches.get(a).add(x - px);
        }
        var ties = new ArrayList<Link>();
        for (var t : partnerTies) {
            int a = remap[t[0]], b = remap[t[1]];
            if (a < 0 || b < 0) continue;
            var f = rows.get(t[1]).family.orElseThrow();
            double when = f.event("MARR").map(Event::date).map(Timeline::year).filter(OptionalDouble::isPresent)
                    .map(OptionalDouble::getAsDouble)
                    .orElse(Math.max(lives.get(a).start, lives.get(b).start) + 22);
            when = Math.max(when, Math.max(lives.get(a).start, lives.get(b).start));
            double x = x(when, startYear, x0);
            ties.add(new Link(x, mid(a, top) + BAR_H / 2, x, mid(b, top) - BAR_H / 2));
        }

        var cards = new ArrayList<Card>();
        double barsRight = x(now, startYear, x0);
        for (int i = 0; i < keep.size(); i++) {
            var r = rows.get(keep.get(i));
            var life = lives.get(i);
            double cx = x(life.start, startYear, x0);
            var span = new Span((life.from - life.start) * PX, (life.to - life.start) * PX, (life.end - life.start) * PX,
                    life.living, List.copyOf(branches.get(i)));
            cards.add(new Card(r.person, r.kind, r.family, cx, top + i * ROW, span));
            barsRight = Math.max(barsRight, cx + span.end());
        }

        double height = top + keep.size() * ROW + Layout.MARGIN;
        var columns = new ArrayList<Column>();
        var guides = new ArrayList<Guide>();
        double todayX = x(now, startYear, x0);
        for (int y = startYear; y <= now; y += 10) {
            double gx = x(y, startYear, x0);
            guides.add(new Guide(gx, top - 10, height - Layout.MARGIN, false));
            if (todayX - gx > 34) columns.add(new Column(gx, String.valueOf(y)));
        }
        guides.add(new Guide(todayX, top - 10, height - Layout.MARGIN, true));
        columns.add(new Column(todayX, spec.labels().today()));

        return new Chart(root, cards, links, ties, columns, guides, barsRight + Layout.MARGIN, height, up, down, spec.theme());
    }

    private static double x(double year, int startYear, double x0) {
        return x0 + (year - startYear) * PX;
    }

    private static double mid(int row, double top) {
        return top + row * ROW + BAR_Y + BAR_H / 2;
    }
}
