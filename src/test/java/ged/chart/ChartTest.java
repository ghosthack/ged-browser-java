package ged.chart;

import ged.Fixtures;
import ged.chart.Layout.Card;
import ged.chart.Layout.Kind;
import ged.model.GedDate;
import ged.model.Genealogy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartTest {
    private final Genealogy g = Fixtures.cousins();

    private Layout.Chart chart(String root, int up, int down) {
        return Layout.build(g, g.person(root).orElseThrow(), new Layout.Spec(up, down, true, Labels.EN));
    }

    private static List<String> keys(Layout.Chart c, Kind kind) {
        return c.cards().stream().filter(x -> x.kind() == kind).map(x -> x.person().key()).toList();
    }

    @Test
    void cousinMarriageDrawsTheSpouseOnceThenAsRepeat() {
        var c = chart("I1", 0, 9);
        assertEquals(List.of("I1"), keys(c, Kind.ROOT));
        assertTrue(keys(c, Kind.SPOUSE).contains("I8"), "Sofia drawn in full as Luca's wife");
        assertEquals(List.of("I8"), keys(c, Kind.REPEAT), "and again, dashed, as Anna's daughter");
        assertEquals(9, c.peopleCount());
        assertEquals(3, c.down());
    }

    @Test
    void pedigreeCollapseRepeatsSharedGrandparents() {
        var c = chart("I9", 9, 0);
        assertEquals(3, c.up());
        // Giovanni and Lucia are reached through both Luca and Sofia.
        assertEquals(2, keys(c, Kind.REPEAT).size());
        assertTrue(keys(c, Kind.REPEAT).containsAll(List.of("I1", "I2")));
        assertEquals(List.of("Parents", "Grandparents", "Great-grandparents"),
                c.columns().stream().map(Layout.Column::label).toList());
    }

    @Test
    void ancestorsLeftDescendantsRightAndNothingOverlaps() {
        var c = chart("I7", 2, 2);
        var root = c.cards().stream().filter(x -> x.kind() == Kind.ROOT).findFirst().orElseThrow();
        var father = card(c, "I3");
        var daughter = card(c, "I9");
        assertTrue(father.x() < root.x(), "parents to the left");
        assertTrue(daughter.x() > root.x(), "children to the right");
        assertEquals(1, keys(c, Kind.ROOT).size(), "root drawn once in an hourglass");
        for (var a : c.cards()) {
            for (var b : c.cards()) {
                if (a == b) continue;
                boolean overlap = Math.abs(a.x() - b.x()) < Theme.CLASSIC.cardW() && Math.abs(a.y() - b.y()) < Theme.CLASSIC.cardH();
                assertFalse(overlap, a.person().key() + " overlaps " + b.person().key());
            }
        }
        for (var x : c.cards()) {
            assertTrue(x.x() >= 0 && x.y() >= 0 && x.x() + Theme.CLASSIC.cardW() <= c.width() && x.y() + Theme.CLASSIC.cardH() <= c.height());
        }
    }

    @Test
    void parentIsCentredBetweenItsParents() {
        var c = chart("I9", 1, 0);
        var mid = (card(c, "I7").y() + card(c, "I8").y()) / 2;
        assertEquals(mid, card(c, "I9").y(), 0.01);
    }

    @Test
    void depthLimitsGenerations() {
        var c = chart("I1", 0, 1);
        assertFalse(c.cards().stream().anyMatch(x -> x.person().key().equals("I7")));
        assertEquals(1, c.down());
    }

    @Test
    void htmlIsSelfContainedAndEscaped() {
        var hostile = Genealogy.of(Fixtures.COUSINS.replace("Elena /Rossi/", "Elena </script><b>&\" /Rossi/"));
        var c = Layout.build(hostile, hostile.person("I9").orElseThrow(), new Layout.Spec(2, 2, true, Labels.EN));
        var html = new ChartPage(hostile, c, Labels.EN, "Our <family>", false, LocalDate.of(2026, 11, 3)).html(false);
        assertEquals(1, html.split("</script>", -1).length - 1, "only the page's own closing script tag");
        assertTrue(html.contains("<title>Our &lt;family&gt;</title>"));
        assertTrue(html.contains("made 3 Nov 2026"));
        var urls = Pattern.compile("https?://[^\"' )]+").matcher(html).results().map(r -> r.group()).distinct().toList();
        assertEquals(List.of("http://www.w3.org/2000/svg"), urls, "no external resources");
    }

    @Test
    void notesOnlyWhenAsked() {
        var c = chart("I3", 0, 1);
        assertFalse(new ChartPage(g, c, Labels.EN, "t", false, LocalDate.now()).html(false).contains("Went to sea"));
        assertTrue(new ChartPage(g, c, Labels.EN, "t", true, LocalDate.now()).html(false).contains("Went to sea"));
    }

    @Test
    void spanishLabelsAndDates() {
        assertEquals("hacia 12 jun 1850", Labels.ES.fullDate(GedDate.parse("ABT 12 JUN 1850")));
        assertEquals("between 1800 and 1810", Labels.EN.fullDate(GedDate.parse("BET 1800 AND 1810")));
        assertEquals("c. 1850", Labels.EN.year(GedDate.parse("ABT 1850")));
        assertEquals("Bisabuelos", Labels.ES.generation(3, true));
        assertEquals("2nd great-grandchildren", Labels.EN.generation(4, false));
        var es = Layout.build(g, g.person("I9").orElseThrow(), new Layout.Spec(2, 0, true, Labels.ES));
        var html = new ChartPage(g, es, Labels.ES, "Árbol", false, LocalDate.of(2026, 1, 5)).html(false);
        assertTrue(html.contains("lang=\"es\"") && html.contains("ABUELOS"));
        assertTrue(html.contains("creado el 5 ene 2026"));
    }

    @Test
    void jsonEscapes() {
        assertEquals("{\"a\":[\"x\\\"y\",\"l1\\nl2\"]}", ChartPage.json(Map.of("a", List.of("x\"y", "l1\nl2"))));
    }

    private static Card card(Layout.Chart c, String key) {
        return c.cards().stream().filter(x -> x.person().key().equals(key) && x.kind() != Kind.REPEAT)
                .findFirst().orElseThrow(() -> new AssertionError("no card for " + key));
    }

    @Test
    void pngScaleIsCappedForHugeCharts() {
        System.setProperty("java.awt.headless", "true");
        var png = new PngChart(new ChartPage(g, chart("I9", 3, 3), Labels.EN, "t", false, LocalDate.now()), false);
        assertEquals(2.0, png.effectiveScale(2.0), 1e-9, "small chart keeps full resolution");
        assertTrue(png.effectiveScale(1e6) < 1e6, "absurd scale is capped by the pixel budget");
    }

    @Test
    void flagsComeFromTheBirthplace() {
        assertEquals("ar", code("Rosario, Santa Fe, Argentina"));
        assertEquals("es", code("Toledo, Castilla-La Mancha, España"));
        assertEquals("it", code("Torino, Piemonte, Italia"));
        assertEquals("us", code("Springfield, USA"));
        assertEquals("us", code("Boston, Massachusetts, United States of America"));
        assertEquals("sct", code("Aberdeen, Scotland"));
        assertEquals("sct", code("Glasgow"));
        assertEquals("ar", code("Concordia, Entre Ríos"));
        assertEquals("mx", code("Tijuana, Baja California, México"), "the country wins over a region elsewhere");
        assertEquals("", code("Somewhere"));
        assertEquals("", code(""));
    }

    @Test
    void flagsAreDrawnOncePerCountryAndUsedOnCards() {
        var svg = new ChartPage(g, chart("I9", 9, 0), Labels.EN, "t", false, LocalDate.now()).svg(false, ChartPage.Colors.LIGHT);
        assertEquals(1, svg.split("<symbol id=\"flag-it\"", -1).length - 1, "Italian flag defined once");
        assertTrue(svg.contains("href=\"#flag-it\""), "used by Giovanni (Genova, Italia)");
        assertTrue(svg.contains("href=\"#flag-es\""), "used by José (Sevilla, España)");
    }

    private static String code(String place) {
        return Flags.forPlace(place).map(Flags.Flag::code).orElse("");
    }

    // ---- modern theme ------------------------------------------------------

    private ChartPage modern(String root, int up, int down, LocalDate today) {
        var c = Layout.build(g, g.person(root).orElseThrow(), new Layout.Spec(up, down, true, Labels.EN, Theme.MODERN));
        return new ChartPage(g, c, Labels.EN, "t", false, today);
    }

    @Test
    void agesAreWorkedOutOnlyWhenHonest() {
        var page = modern("I9", 2, 0, LocalDate.of(2026, 11, 3));
        assertEquals("aged 79", page.age(g.person("I9").orElseThrow()), "7 Jan 1910 – 2 Jan 1990: a few days short of 80");
        assertEquals("aged c. 70", page.age(g.person("I1").orElseThrow()), "1820 – ABT 1890");
        assertEquals("", page.age(g.person("I3").orElseThrow()), "no death recorded and born 1850: not 176 years old");
        var living = Genealogy.of(Fixtures.COUSINS.replace("2 DATE 7 JAN 1910\n1 DEAT\n2 DATE 2 JAN 1990\n", "2 DATE 7 JAN 1980\n"));
        var c = Layout.build(living, living.person("I9").orElseThrow(), new Layout.Spec(0, 0, true, Labels.ES, Theme.MODERN));
        assertEquals("46 años", new ChartPage(living, c, Labels.ES, "t", false, LocalDate.of(2026, 11, 3)).age(living.person("I9").orElseThrow()));
    }

    @Test
    void modernCardsShowIdAgeAndLargeFlag() {
        var svg = modern("I9", 2, 0, LocalDate.now()).svg(false, ChartPage.Colors.LIGHT);
        assertTrue(svg.contains(">I9</text>"), "record id on the card");
        assertTrue(svg.contains("aged 79"), "age on the card");
        assertTrue(Pattern.compile("href=\"#flag-it\" x=\"[\\d.]+\" y=\"13\" width=\"30\" height=\"20\"").matcher(svg).find(), "large flag");
        assertTrue(svg.contains(">EV</text>") || svg.contains(">ER</text>"), "initials avatar");
        assertFalse(svg.contains("var(--c-"), "standalone colours are fixed");
    }

    @Test
    void modernCardsDoNotOverlap() {
        var page = modern("I7", 3, 3, LocalDate.now());
        var cards = page.chart().cards();
        double w = Theme.MODERN.cardW(), h = Theme.MODERN.cardH();
        for (var a : cards) for (var b : cards) {
            if (a != b) assertFalse(Math.abs(a.x() - b.x()) < w && Math.abs(a.y() - b.y()) < h, a.person().key() + "/" + b.person().key());
        }
    }

    @Test
    void modernHtmlSwitchesLightAndDarkButPrintsLight() {
        var html = modern("I9", 2, 2, LocalDate.now()).html(false);
        assertTrue(html.contains("@media (prefers-color-scheme:dark){:root:not([data-theme=light]){"));
        assertTrue(html.contains(":root[data-theme=dark]{"));
        assertTrue(html.contains("@media print{:root,"), "print is always light");
        assertTrue(html.contains("id=\"tm\""), "theme switch button");
        assertTrue(html.contains("var(--c-card,#ffffff)"), "cards follow the palette");
        assertTrue(modern("I9", 2, 2, LocalDate.now()).html(true).contains("<html lang=\"en\" data-theme=\"dark\">"));
        var classic = new ChartPage(g, chart("I9", 2, 2), Labels.EN, "t", false, LocalDate.now()).html(false);
        assertFalse(classic.contains("id=\"tm\"") || classic.contains("prefers-color-scheme"), "classic stays light");
    }

    @Test
    void darkPngUsesDarkPaper() throws Exception {
        System.setProperty("java.awt.headless", "true");
        var bytes = new PngChart(modern("I9", 1, 1, LocalDate.now()), true).png(1);
        var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        assertEquals(0x141619, img.getRGB(2, 2) & 0xffffff);
    }

    @Test
    void ageFromDateRangesAndRecordedAge() {
        var today = LocalDate.of(2026, 11, 3);
        // A range that pins the age down (like a birth worked out from ages in later records).
        var narrow = Genealogy.of(Fixtures.COUSINS.replace("2 DATE 7 JAN 1910", "2 DATE BET 28 DEC 1909 AND 1 JAN 1910"));
        assertEquals("aged 80", modernOn(narrow, today).age(narrow.person("I9").orElseThrow()));
        // A range that straddles the birthday gives both possible ages.
        var wide = Genealogy.of(Fixtures.COUSINS.replace("2 DATE 7 JAN 1910", "2 DATE BET 1 JAN 1910 AND 31 DEC 1910"));
        assertEquals("aged 79–80", modernOn(wide, today).age(wide.person("I9").orElseThrow()));
        // No usable birth date: fall back to the age the death record states.
        var recorded = Genealogy.of(Fixtures.COUSINS
                .replace("2 DATE 3 MAR 1820", "2 DATE BEF 1830")
                .replace("2 DATE ABT 1890", "2 DATE ABT 1890\n2 AGE 69y"));
        assertEquals("aged 69", modernOn(recorded, today).age(recorded.person("I1").orElseThrow()));
        // Before/after with nothing recorded: no age rather than a guess.
        var vague = Genealogy.of(Fixtures.COUSINS.replace("2 DATE 3 MAR 1820", "2 DATE BEF 1830"));
        assertEquals("", modernOn(vague, today).age(vague.person("I1").orElseThrow()));
    }

    // ---- timeline ----------------------------------------------------------

    private Layout.Chart timeline(String root, int up, int down) {
        return Timeline.build(g, g.person(root).orElseThrow(), new Layout.Spec(up, down, true, Labels.EN, Theme.MODERN),
                LocalDate.of(2026, 11, 3));
    }

    @Test
    void timelineBarsAreAsLongAsTheLifeAndChildrenBranchAtBirth() {
        var c = timeline("I9", 1, 0);
        assertTrue(c.timeline());
        var elena = card(c, "I9");
        var luca = card(c, "I7");
        var sofia = card(c, "I8");
        // Father above, mother below: both parents next to the child they join.
        assertTrue(luca.y() < elena.y() && elena.y() < sofia.y());
        // 7 Jan 1910 – 2 Jan 1990: a solid bar of just under 80 years.
        assertEquals(80 * Timeline.PX, elena.span().solidTo() - elena.span().solidFrom(), Timeline.PX * 0.1);
        assertEquals(elena.span().end(), elena.span().solidTo(), 1e-9, "nothing estimated");
        // Born 30 years after Luca: her line leaves his bar there and lands where hers starts.
        assertEquals(elena.x() - luca.x(), (1910 + 6 / 365.25 - 1880.5) * Timeline.PX, 0.01);
        assertTrue(c.links().stream().allMatch(k -> k.x1() == k.x2()), "straight down");
        assertTrue(c.links().stream().anyMatch(k -> k.x1() == elena.x() && k.y1() < elena.y() && k.y2() > elena.y()));
        assertEquals(List.of(elena.x() - luca.x()), luca.span().branches());
        assertEquals(1, c.guides().stream().filter(Layout.Guide::today).count());
        assertEquals("Today", c.columns().getLast().label());
    }

    @Test
    void timelineEstimatesMissingDatesAndDrawsThemPale() {
        var c = timeline("I9", 3, 0);
        // Paolo has no dates at all: placed from his wife's birth, solid only from his first known fact.
        var paolo = card(c, "I6").span();
        assertTrue(paolo.solidFrom() > 0, "estimated birth drawn pale");
        // Lucia's death isn't recorded: her bar fades out after the last thing known.
        var lucia = card(c, "I2").span();
        assertTrue(lucia.end() > lucia.solidTo());
        assertEquals(2, keys(c, Kind.REPEAT).size(), "pedigree collapse: shared great-grandparents again, dashed");

        var page = new ChartPage(g, c, Labels.EN, "t", false, LocalDate.of(2026, 11, 3));
        var html = page.html(false);
        assertTrue(html.contains("Pale: dates estimated") && html.contains(">TODAY<"));
        assertTrue(page.width() >= c.width(), "room for labels past the last bar");
    }

    @Test
    void timelineDescendantsAndPng() throws Exception {
        var c = timeline("I1", 0, 9);
        var luca = card(c, "I7");
        var sofia = c.cards().stream().filter(x -> x.person().key().equals("I8") && x.kind() == Kind.SPOUSE).findFirst().orElseThrow();
        assertEquals(luca.y() + Timeline.ROW, sofia.y(), "partner right under");
        assertEquals(1, c.ties().stream().filter(t -> t.y1() < sofia.y() + Timeline.ROW && t.y2() > luca.y()).count());
        for (var a : c.cards()) for (var b : c.cards()) if (a != b) assertTrue(a.y() != b.y(), "one person per row");

        System.setProperty("java.awt.headless", "true");
        var page = new ChartPage(g, c, Labels.EN, "t", false, LocalDate.of(2026, 11, 3));
        var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(new PngChart(page, true).png(1)));
        assertEquals(0x141619, img.getRGB(2, 2) & 0xffffff, "dark paper");
    }

    @Test
    void timelineNeedsSomeDate() {
        var undated = Genealogy.of("0 HEAD\n0 @I1@ INDI\n1 NAME A /B/\n0 TRLR\n");
        var e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                Timeline.build(undated, undated.person("I1").orElseThrow(), new Layout.Spec(2, 2, true, Labels.EN, Theme.MODERN), LocalDate.now()));
        assertTrue(e.getMessage().contains("no birth"));
    }

    // ---- baroque -----------------------------------------------------------

    @Test
    void ornamentPathsMirrorAndReplay() {
        assertEquals("M 10 20 C 8 20 6 22 6 24", Ornaments.place("M0 0 C2 0 4 2 4 4", 10, 20, -1, 1));
        var frame = Ornaments.frame(0, 0, 100, 50, 8);
        assertTrue(frame.startsWith("M8 0 L92 0 Q92 8 100 8"), frame);
        var bounds = PngChart.path(frame).getBounds2D();
        assertEquals(100, bounds.getWidth(), 1e-9);
        assertEquals(50, bounds.getHeight(), 1e-9);
    }

    @Test
    void baroqueFramesCardsAndBordersThePage() throws Exception {
        var c = Layout.build(g, g.person("I9").orElseThrow(), new Layout.Spec(2, 1, true, Labels.EN, Theme.BAROQUE));
        var page = new ChartPage(g, c, Labels.EN, "The Rossi family", false, LocalDate.of(2026, 11, 3));
        var marks = page.marks(card(c, "I9"));
        assertTrue(marks.stream().anyMatch(m -> m instanceof ChartPage.Orn o && "bg".equals(o.cls())), "frame is the hover target");
        assertTrue(marks.stream().filter(m -> m instanceof ChartPage.Orn).count() > 15, "scrollwork, crest and plate");
        // Marco was born in Genova: his flag is a medallion on the card's top edge, not on the birthplace line.
        var flags = page.marks(card(c, "I3")).stream().filter(m -> m instanceof ChartPage.FlagMark).map(m -> (ChartPage.FlagMark) m).toList();
        assertEquals(1, flags.size());
        assertTrue(flags.getFirst().y() < 0 && flags.getFirst().y() + flags.getFirst().h() > 0, "straddles the frame");
        assertEquals(Theme.BAROQUE.cardW() / 2, flags.getFirst().x() + flags.getFirst().w() / 2, 1e-9);
        assertEquals(null, flags.getFirst().clip(), "a plain medallion unless arms are asked for");
        page.arms(true);
        var arms = page.marks(card(c, "I3")).stream().filter(m -> m instanceof ChartPage.FlagMark).map(m -> (ChartPage.FlagMark) m).toList();
        assertEquals(Ornaments.Escutcheon.SHIELD, arms.getFirst().clip(), "with --arms, a man's flag on a shield");
        // Elena has no birthplace: her lozenge is left plain, tinted in her colour.
        var elena = page.marks(card(c, "I9"));
        assertTrue(elena.stream().noneMatch(m -> m instanceof ChartPage.FlagMark));
        assertTrue(elena.stream().anyMatch(m -> m instanceof ChartPage.Orn o && "femaleBg".equals(o.fill())
                && o.d().equals(Ornaments.Escutcheon.LOZENGE.path(Theme.BAROQUE.cardW() / 2 - 14, -13, 28, 28))));
        // Text stays inside the frame, between the crest and the id plate.
        for (var m : marks) {
            if (m instanceof ChartPage.Txt t && !t.runs().getFirst().style().equals("tag")) {
                assertTrue(t.y() > 18 && t.y() < Theme.BAROQUE.cardH() - 6, t.runs() + " at " + t.y());
            }
        }

        var svg = page.svg(true, ChartPage.Colors.LIGHT);
        assertTrue(svg.contains("<clipPath id=\"arms-shield\" clipPathUnits=\"objectBoundingBox\">"), "flags cut to the shield");
        assertTrue(svg.contains("Snell Roundhand") && svg.contains("font:italic"), "script title, italic dates");
        assertTrue(svg.contains(">The Rossi family</text>") && svg.contains("Family tree with 7 people"), svg.substring(0, 0));
        assertTrue(svg.contains("translate(26,134)"), "chart inset inside the border and under the heading");
        var html = page.html(false);
        assertTrue(html.contains("<svg class=\"orn\"") && html.contains("--c-orn:"), "flourish under the HTML title");
        assertTrue(html.contains("<body class=\"ornate\">") && html.contains("id=\"hd\"") && html.contains("class=\"cn tl\""),
                "floating, framed title and controls that can be hidden");
        var modern = new ChartPage(g, chart("I9", 1, 1), Labels.EN, "t", false, LocalDate.now()).html(false);
        assertFalse(modern.contains("id=\"hd\"") || modern.contains("class=\"ornate\""), "other themes keep the header bar");
        var urls = Pattern.compile("https?://[^\"' )]+").matcher(html).results().map(r -> r.group()).distinct().toList();
        assertEquals(List.of("http://www.w3.org/2000/svg"), urls, "still no external resources");

        System.setProperty("java.awt.headless", "true");
        for (boolean dark : new boolean[] {false, true}) {
            var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(new PngChart(page, dark).png(1)));
            assertEquals(dark ? 0x17120c : 0xf2e6cc, img.getRGB(3, 3) & 0xffffff, "parchment / candlelit paper");
            assertEquals((int) Math.ceil(page.pageW(true)), img.getWidth());
        }
        // Leaves along the connectors: a sprig at each bend, drawn once even where siblings' lines share it.
        var leaves = page.linkMarks();
        assertFalse(leaves.isEmpty());
        assertEquals(leaves.size(), leaves.stream().map(m -> ((ChartPage.Orn) m).d()).distinct().count(), "no doubled leaves");
        assertTrue(new ChartPage(g, chart("I9", 2, 1), Labels.EN, "t", false, LocalDate.now()).linkMarks().isEmpty(), "baroque only");
        assertEquals("M 5 0 L 5 2", Ornaments.turn("M0 0 L2 0", 5, 0, 1, Math.PI / 2), "turned a quarter clockwise");
        // Timelines take the baroque look too; classic still can't.
        assertTrue(Theme.BAROQUE.hasTimeline() && !Theme.CLASSIC.hasTimeline());
    }

    private static ChartPage modernOn(Genealogy g, LocalDate today) {
        var c = Layout.build(g, g.person("I9").orElseThrow(), new Layout.Spec(3, 0, true, Labels.EN, Theme.MODERN));
        return new ChartPage(g, c, Labels.EN, "t", false, today);
    }
}
