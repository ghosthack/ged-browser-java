package ged.chart;

import ged.model.Family;
import ged.model.Genealogy;
import ged.model.Person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Places people on a left-to-right chart: ancestors fan out to the left
/// of the root, descendants to the right, one column per generation. A
/// parent is centred on its children (or a child on its parents), so the
/// chart is compact where the tree is sparse.
///
/// Each position holds a *block*: one person card, plus (for descendants)
/// a card per partner stacked below it, with that partner's children
/// hanging off it. Someone reached twice (cousin marriage, pedigree
/// collapse) is drawn in full once and then as a short "repeat" card.
public final class Layout {
    public static final double MARGIN = 28, HEADER = 34;

    public enum Kind { ROOT, PERSON, SPOUSE, REPEAT }

    /// One drawn card. `family` is the marriage a spouse card stands for.
    public record Card(Person person, Kind kind, Optional<Family> family, double x, double y) {}

    /// A connector from a card's edge (x1, y1) to a relative's edge (x2, y2).
    public record Link(double x1, double y1, double x2, double y2) {}

    public record Column(double centerX, String label) {}

    public record Chart(Person root, List<Card> cards, List<Link> links, List<Link> ties, List<Column> columns,
                        double width, double height, int up, int down, Theme theme) {
        public int peopleCount() {
            return (int) cards.stream().filter(c -> c.kind() != Kind.REPEAT).map(c -> c.person().id()).distinct().count();
        }
    }

    /// `up`/`down`: generations of ancestors/descendants to draw (0 = none).
    public record Spec(int up, int down, boolean spouses, Labels labels, Theme theme) {
        public Spec(int up, int down, boolean spouses, Labels labels) {
            this(up, down, spouses, labels, Theme.CLASSIC);
        }
    }

    private record Slot(Person person, Kind kind, Optional<Family> family) {}

    private record Group(int anchor, List<Block> kids) {}

    private final class Block {
        final List<Slot> slots = new ArrayList<>();
        final List<Group> groups = new ArrayList<>();
        int col;
        double y;

        Block(Person p, Kind k) { slots.add(new Slot(p, k, Optional.empty())); }

        double height() { return slots.size() * cardH + (slots.size() - 1) * stack; }
        double cardY(int i) { return y + i * (cardH + stack); }

        void shift(double dy) {
            y += dy;
            for (var g : groups) for (var k : g.kids) k.shift(dy);
        }
    }

    private final Genealogy g;
    private final Spec spec;
    private final Set<String> seen = new HashSet<>();
    private final double cardW, cardH, stack, gap, pitch;

    private Layout(Genealogy g, Spec spec) {
        this.g = g;
        this.spec = spec;
        var t = spec.theme;
        this.cardW = t.cardW();
        this.cardH = t.cardH();
        this.stack = t.stack();
        this.gap = t.gap();
        this.pitch = t.pitch();
    }

    public static Chart build(Genealogy g, Person root, Spec spec) {
        return new Layout(g, spec).build(root);
    }

    private Chart build(Person root) {
        var desc = descendants(root, 0);
        place(desc, 0, 0);
        Block anc = null;
        if (spec.up > 0) {
            anc = ancestors(root, 0);
            place(anc, 0, 0);
            anc.shift(desc.y - anc.y);
        }

        var cards = new ArrayList<Card>();
        var links = new ArrayList<Link>();
        var ties = new ArrayList<Link>();
        int[] reach = new int[2];
        emit(desc, 1, cards, links, ties, reach, 1);
        if (anc != null) emit(anc, -1, cards, links, ties, reach, 0);

        double minX = cards.stream().mapToDouble(Card::x).min().orElse(0);
        double minY = cards.stream().mapToDouble(Card::y).min().orElse(0);
        double dx = MARGIN - minX, dy = MARGIN + HEADER - minY;
        var placed = cards.stream().map(c -> new Card(c.person(), c.kind(), c.family(), c.x() + dx, c.y() + dy)).toList();
        var moved = links.stream().map(l -> new Link(l.x1 + dx, l.y1 + dy, l.x2 + dx, l.y2 + dy)).toList();
        var movedTies = ties.stream().map(l -> new Link(l.x1 + dx, l.y1 + dy, l.x2 + dx, l.y2 + dy)).toList();

        var columns = new ArrayList<Column>();
        for (int i = 1; i <= reach[0]; i++) columns.add(new Column(-i * pitch + cardW / 2 + dx, spec.labels.generation(i, true)));
        for (int i = 1; i <= reach[1]; i++) columns.add(new Column(i * pitch + cardW / 2 + dx, spec.labels.generation(i, false)));

        double w = placed.stream().mapToDouble(c -> c.x() + cardW).max().orElse(0) + MARGIN;
        double h = placed.stream().mapToDouble(c -> c.y() + cardH).max().orElse(0) + MARGIN;
        return new Chart(root, placed, moved, movedTies, columns, w, h, reach[0], reach[1], spec.theme);
    }

    // ---- building the trees ---------------------------------------------

    private Block descendants(Person p, int gen) {
        var b = new Block(p, gen == 0 ? Kind.ROOT : Kind.PERSON);
        seen.add(p.id());
        if (gen >= spec.down) return b;
        if (!spec.spouses) {
            var kids = g.children(p).stream().distinct().map(k -> child(k, gen)).toList();
            if (!kids.isEmpty()) b.groups.add(new Group(0, kids));
            return b;
        }
        for (var f : g.familiesAsSpouse(p)) {
            var partner = f.partnerOf(p.id()).flatMap(g::person);
            int anchor = 0;
            if (partner.isPresent()) {
                var q = partner.get();
                b.slots.add(new Slot(q, seen.add(q.id()) ? Kind.SPOUSE : Kind.REPEAT, Optional.of(f)));
                anchor = b.slots.size() - 1;
            }
            var kids = f.children().stream().flatMap(id -> g.person(id).stream()).map(k -> child(k, gen)).toList();
            if (!kids.isEmpty()) b.groups.add(new Group(anchor, kids));
        }
        return b;
    }

    private Block child(Person k, int gen) {
        return seen.contains(k.id()) ? new Block(k, Kind.REPEAT) : descendants(k, gen + 1);
    }

    private Block ancestors(Person p, int gen) {
        var b = new Block(p, gen == 0 ? Kind.ROOT : Kind.PERSON);
        seen.add(p.id());
        if (gen >= spec.up) return b;
        var parents = new ArrayList<Block>();
        for (var parent : List.of(g.father(p), g.mother(p))) {
            parent.ifPresent(q -> parents.add(seen.contains(q.id()) ? new Block(q, Kind.REPEAT) : ancestors(q, gen + 1)));
        }
        if (!parents.isEmpty()) b.groups.add(new Group(0, parents));
        return b;
    }

    // ---- positioning -----------------------------------------------------

    /// Lays out `b` and its subtree starting at `top`; returns the bottom edge.
    private double place(Block b, int col, double top) {
        b.col = col;
        double cursor = top, kidsBottom = top;
        Block first = null, last = null;
        for (var grp : b.groups) {
            for (var k : grp.kids) {
                kidsBottom = place(k, col + 1, cursor);
                cursor = kidsBottom + gap;
                if (first == null) first = k;
                last = k;
            }
        }
        if (first == null) {
            b.y = top;
            return top + b.height();
        }
        // Centre this block on the span between its first and last child's main card.
        double span = (first.cardY(0) + last.cardY(0) + cardH) / 2;
        b.y = span - b.height() / 2;
        if (b.y < top) {
            double d = top - b.y;
            for (var grp : b.groups) for (var k : grp.kids) k.shift(d);
            kidsBottom += d;
            b.y = top;
        }
        return Math.max(b.y + b.height(), kidsBottom);
    }

    /// `dir` is 1 for descendants (rightwards), -1 for ancestors (leftwards).
    private void emit(Block b, int dir, List<Card> cards, List<Link> links, List<Link> ties, int[] reach, int side) {
        double x = dir * b.col * pitch;
        reach[side] = Math.max(reach[side], b.col);
        for (int i = 0; i < b.slots.size(); i++) {
            var s = b.slots.get(i);
            boolean sharedRoot = s.kind == Kind.ROOT && dir < 0;
            if (!sharedRoot) cards.add(new Card(s.person, s.kind, s.family, x, b.cardY(i)));
            double tx = x + spec.theme.tieX();
            if (i > 0) ties.add(new Link(tx, b.cardY(i - 1) + cardH, tx, b.cardY(i)));
        }
        double out = dir > 0 ? x + cardW : x;
        for (var grp : b.groups) {
            double ay = b.cardY(grp.anchor) + cardH / 2;
            for (var k : grp.kids) {
                double kx = dir * k.col * pitch + (dir > 0 ? 0 : cardW);
                links.add(new Link(out, ay, kx, k.cardY(0) + cardH / 2));
                emit(k, dir, cards, links, ties, reach, side);
            }
        }
    }
}
