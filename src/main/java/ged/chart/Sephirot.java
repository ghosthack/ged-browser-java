package ged.chart;

import ged.chart.Layout.Card;
import ged.chart.Layout.Chart;
import ged.chart.Layout.Kind;
import ged.chart.Layout.Link;
import ged.model.Family;
import ged.model.Genealogy;
import ged.model.Person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Lays a tree out like an old engraved Tree of Life: one round medallion
/// per person, generations in rows, the root near the bottom with its
/// ancestors rising above it and its descendants hanging below. Each pair
/// of parents sits side by side joined by a straight channel (a *tie*),
/// and a child is joined to each parent by its own diagonal channel, so
/// every family reads as a triangle.
///
/// Cards are the medallions' bounding squares ([#D] wide). Links and ties
/// run between the medallions' rims, as straight lines.
public final class Sephirot {
    /// Medallion radius and diameter; space between neighbours; row pitch.
    public static final double R = 72, D = 2 * R, GAP = 88, ROW = 264;
    /// Room above the top row, and beside the outer medallions, for their flags.
    static final double CAPTION = 10, SIDE = 18;
    /// How far below a couple's centres their children's channels fan out,
    /// and how far above a child's centre its channel turns to drop straight in.
    static final double STEM = R + 26, DROP = R + 40;
    /// Rays keep at least this angle (radians) from the channels beside them at the root.
    static final double RAY_ANGLE = 0.06;

    private Sephirot() {}

    private record Anc(Person p, Kind kind, List<Anc> parents) {}

    /// A descendant with their partners in one row; `groups` are children by partner.
    private record Desc(Person p, Kind kind, Optional<Family> family, List<Desc> partners, List<Group> groups) {}

    /// Children of `p` with one partner (null when the other parent isn't drawn).
    private record Group(Desc partner, List<Desc> kids) {}

    private record Spot(Person p, Kind kind, Optional<Family> family, double cx, int gen) {}

    public static Chart build(Genealogy g, Person root, Layout.Spec spec) {
        return new Builder(g, spec).build(root);
    }

    private static final class Builder {
        final Genealogy g;
        final Layout.Spec spec;
        final Set<String> seen = new HashSet<>();
        final List<Spot> spots = new ArrayList<>();
        /// Links: x1 g1 x2 g2 trim1 trim2 — ends as x and generation (fractional
        /// for a fan's foot), trim 1 where the end is a medallion's centre.
        /// Ties: x1 g1 x2 g2, centre to centre.
        final List<double[]> links = new ArrayList<>(), ties = new ArrayList<>();

        Builder(Genealogy g, Layout.Spec spec) {
            this.g = g;
            this.spec = spec;
        }

        Chart build(Person root) {
            var desc = desc(root, 0);
            seen.clear();
            seen.add(root.id());
            spots.clear();
            var anc = spec.up() > 0 ? anc(root, 0) : null;
            seen.clear();

            // Descendants first, to find where the root sits; then the ancestors, shifted over it.
            double rootX = placeDesc(desc, 0, 0);
            if (anc != null) {
                var before = spots.size();
                var linksBefore = links.size();
                var tiesBefore = ties.size();
                double ax = placeAnc(anc, 0, 0);
                double shift = rootX - ax;
                for (int i = before; i < spots.size(); i++) {
                    var s = spots.get(i);
                    spots.set(i, new Spot(s.p, s.kind, s.family, s.cx + shift, s.gen));
                }
                for (int i = linksBefore; i < links.size(); i++) { links.get(i)[0] += shift; links.get(i)[2] += shift; }
                for (int i = tiesBefore; i < ties.size(); i++) { ties.get(i)[0] += shift; ties.get(i)[2] += shift; }
            }

            if (anc != null) rays(rootX);

            double minX = spots.stream().mapToDouble(Spot::cx).min().orElse(0) - R;
            double maxX = spots.stream().mapToDouble(Spot::cx).max().orElse(0) + R;
            int top = spots.stream().mapToInt(Spot::gen).max().orElse(0);
            int bottom = spots.stream().mapToInt(Spot::gen).min().orElse(0);
            double dx = Layout.MARGIN + SIDE - minX;
            double dy = Layout.MARGIN + CAPTION + top * ROW;   // y of the top row's centre, minus R

            var cards = new ArrayList<Card>();
            for (var s : spots) cards.add(new Card(s.p, s.kind, s.family, s.cx + dx - R, dy - s.gen * ROW));
            var outLinks = new ArrayList<Link>();
            for (var k : links) outLinks.add(rim(k[0] + dx, dy + R - k[1] * ROW, k[2] + dx, dy + R - k[3] * ROW, k[4], k[5]));
            var outTies = new ArrayList<Link>();
            for (var t : ties) {
                double a = Math.min(t[0], t[2]) + dx, b = Math.max(t[0], t[2]) + dx, y = dy + R - t[1] * ROW;
                outTies.add(new Link(a + R + 2, y, b - R - 2, y));
            }
            double w = maxX + dx + SIDE + Layout.MARGIN;
            double h = Layout.MARGIN + CAPTION + (top - bottom) * ROW + D + Layout.MARGIN;
            return new Chart(root, cards, outLinks, outTies, List.of(), List.of(), w, h, top, -bottom, spec.theme());
        }

        /// Rays, as on the old plates: a long channel from the root, the sun of the
        /// chart, straight up to every ancestor from the grandparents on, crossing the
        /// channels between. Skipped only where the line would pass behind another
        /// medallion (and so seem to join it), or would leave the root almost on top
        /// of a channel already there.
        void rays(double rootX) {
            var leaving = new ArrayList<Double>();
            for (var s : spots) if (s.gen == 1) leaving.add(Math.atan2(-ROW, s.cx - rootX));
            var far = spots.stream().filter(s -> s.gen >= 2 && s.kind != Kind.REPEAT)
                    .sorted(java.util.Comparator.comparingInt(Spot::gen)).toList();
            for (var s : far) {
                double y = -s.gen * ROW;
                boolean clear = true;
                for (var o : spots) {
                    if (o == s || (o.gen == 0 && Math.abs(o.cx - rootX) < 1)) continue;
                    if (distance(o.cx, -o.gen * ROW, rootX, 0, s.cx, y) < R + 14) { clear = false; break; }
                }
                double angle = Math.atan2(y, s.cx - rootX);
                for (double d : leaving) if (Math.abs(angle - d) < RAY_ANGLE) clear = false;
                if (!clear) continue;
                leaving.add(angle);
                links.add(new double[] {rootX, 0, s.cx, s.gen, 1, 1});
            }
        }

        /// Distance from (px, py) to the segment (x1, y1)–(x2, y2).
        static double distance(double px, double py, double x1, double y1, double x2, double y2) {
            double dx = x2 - x1, dy = y2 - y1, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.clamp(((px - x1) * dx + (py - y1) * dy) / len2, 0, 1);
            return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
        }

        /// The straight line between two points, cut back to the rim at each end that is a medallion's centre.
        static Link rim(double x1, double y1, double x2, double y2, double trim1, double trim2) {
            double len = Math.hypot(x2 - x1, y2 - y1), ux = (x2 - x1) / len, uy = (y2 - y1) / len, r = R + 2;
            return new Link(x1 + ux * r * trim1, y1 + uy * r * trim1, x2 - ux * r * trim2, y2 - uy * r * trim2);
        }

        // ---- ancestors: rising above the root ------------------------------------

        Anc anc(Person p, int gen) {
            seen.add(p.id());
            var parents = new ArrayList<Anc>();
            if (gen < spec.up()) {
                for (var parent : List.of(g.father(p), g.mother(p))) {
                    parent.ifPresent(q -> parents.add(seen.contains(q.id())
                            ? new Anc(q, Kind.REPEAT, List.of()) : anc(q, gen + 1)));
                }
            }
            return new Anc(p, gen == 0 ? Kind.ROOT : Kind.PERSON, parents);
        }

        static double width(Anc a) {
            if (a.parents.isEmpty()) return D;
            double sum = a.parents.stream().mapToDouble(Builder::width).sum() + GAP * (a.parents.size() - 1);
            return Math.max(D, sum);
        }

        /// Places `a` and its ancestors in a band starting at `left`; returns its centre.
        double placeAnc(Anc a, double left, int gen) {
            double w = width(a), cx;
            if (a.parents.isEmpty()) {
                cx = left + w / 2;
            } else {
                double inner = a.parents.stream().mapToDouble(Builder::width).sum() + GAP * (a.parents.size() - 1);
                double x = left + (w - inner) / 2;
                var xs = new ArrayList<Double>();
                for (var par : a.parents) {
                    xs.add(placeAnc(par, x, gen + 1));
                    x += width(par) + GAP;
                }
                cx = (xs.getFirst() + xs.getLast()) / 2;
                for (double px : xs) links.add(new double[] {cx, gen, px, gen + 1, 1, 1});
                if (xs.size() == 2) ties.add(new double[] {xs.get(0), gen + 1, xs.get(1), gen + 1});
            }
            if (gen > 0) spots.add(new Spot(a.p, a.kind, Optional.empty(), cx, gen));   // the root is placed with its descendants
            return cx;
        }

        // ---- descendants: hanging below ------------------------------------------

        Desc desc(Person p, int gen) {
            seen.add(p.id());
            var partners = new ArrayList<Desc>();
            var groups = new ArrayList<Group>();
            var d = new Desc(p, gen == 0 ? Kind.ROOT : Kind.PERSON, Optional.empty(), partners, groups);
            if (gen >= spec.down()) return d;
            if (!spec.spouses()) {
                var kids = g.children(p).stream().distinct().map(k -> child(k, gen)).toList();
                if (!kids.isEmpty()) groups.add(new Group(null, kids));
                return d;
            }
            for (var f : g.familiesAsSpouse(p)) {
                Desc partner = null;
                var q = f.partnerOf(p.id()).flatMap(g::person);
                if (q.isPresent()) {
                    partner = new Desc(q.get(), seen.add(q.get().id()) ? Kind.SPOUSE : Kind.REPEAT, Optional.of(f), List.of(), List.of());
                    partners.add(partner);
                }
                var kids = f.children().stream().flatMap(id -> g.person(id).stream()).map(k -> child(k, gen)).toList();
                if (!kids.isEmpty()) groups.add(new Group(partner, kids));
            }
            return d;
        }

        Desc child(Person k, int gen) {
            return seen.contains(k.id()) ? new Desc(k, Kind.REPEAT, Optional.empty(), List.of(), List.of()) : desc(k, gen + 1);
        }

        /// Row order: partners alternate right and left of the person, so each tie is short.
        static List<Desc> row(Desc d) {
            var row = new ArrayList<Desc>();
            row.add(d);
            for (int i = 0; i < d.partners.size(); i++) {
                if (i % 2 == 0) row.add(d.partners.get(i));
                else row.addFirst(d.partners.get(i));
            }
            return row;
        }

        /// Children's groups in the left-to-right order of the partners they belong to.
        static List<Group> orderedGroups(Desc d) {
            var row = row(d);
            return d.groups.stream().sorted(java.util.Comparator.comparingInt(
                    (Group gr) -> row.indexOf(gr.partner == null ? d : gr.partner))).toList();
        }

        static double kidsWidth(Desc d) {
            var kids = d.groups.stream().flatMap(gr -> gr.kids.stream()).toList();
            return kids.isEmpty() ? 0 : kids.stream().mapToDouble(Builder::width).sum() + GAP * (kids.size() - 1);
        }

        static double width(Desc d) {
            int n = row(d).size();
            return Math.max(n * D + (n - 1) * GAP, kidsWidth(d));
        }

        /// Places `d`, its partners and its descendants from `left`, one row per generation down; returns d's centre.
        double placeDesc(Desc d, double left, int depth) {
            double w = width(d);
            var row = row(d);
            double rowW = row.size() * D + (row.size() - 1) * GAP;
            var at = new java.util.IdentityHashMap<Desc, Double>();
            double x = left + (w - rowW) / 2 + R;
            for (var s : row) {
                at.put(s, x);
                spots.add(new Spot(s.p, s.kind, s.family, x, -depth));
                x += D + GAP;
            }
            double self = at.get(d);
            for (var s : d.partners) ties.add(new double[] {self, -depth, at.get(s), -depth});

            // Children fan out from the foot of a stem dropped from the middle of the couple's
            // channel (or from a lone parent), below the row, and each channel turns to drop
            // straight into its child, so none runs behind a partner's medallion.
            double kw = kidsWidth(d), kx = left + (w - kw) / 2, foot = -depth - STEM / ROW, drop = -(depth + 1) + DROP / ROW;
            var groups = orderedGroups(d);
            if (groups.size() == 1) {
                // One family: centre its children under the couple, as far as the block allows.
                var only = groups.getFirst();
                double mid = only.partner == null ? self : (self + at.get(only.partner)) / 2;
                kx = Math.clamp(mid - kw / 2, left, left + w - kw);
            }
            for (var gr : groups) {
                double mid = gr.partner == null ? self : (self + at.get(gr.partner)) / 2;
                var centres = new ArrayList<Double>();
                for (var k : gr.kids) {
                    centres.add(placeDesc(k, kx, depth + 1));
                    kx += width(k) + GAP;
                }
                boolean fan = centres.stream().anyMatch(kc -> Math.abs(kc - mid) >= 1);
                if (fan) links.add(new double[] {mid, -depth, mid, foot, gr.partner == null ? 1 : 0, 0});
                for (double kc : centres) {
                    if (Math.abs(kc - mid) >= 1) {
                        links.add(new double[] {mid, foot, kc, drop, 0, 0});
                        links.add(new double[] {kc, drop, kc, -(depth + 1), 0, 1});
                    } else if (fan) {
                        links.add(new double[] {mid, foot, kc, -(depth + 1), 0, 1});   // straight on down from the fan's knot
                    } else {
                        links.add(new double[] {mid, -depth, kc, -(depth + 1), gr.partner == null ? 1 : 0, 1});
                    }
                }
            }
            return self;
        }
    }
}
