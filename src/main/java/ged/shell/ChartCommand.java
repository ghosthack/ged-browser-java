package ged.shell;

import ged.chart.ChartPage;
import ged.chart.Labels;
import ged.chart.Layout;
import ged.chart.PngChart;
import ged.chart.Theme;
import ged.chart.Timeline;
import ged.model.Genealogy;
import ged.model.Person;
import ged.vfs.Location;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

final class ChartCommand implements Command {
    private final Options opts = new Options()
            .flag("a", "ancestors", "ancestors only")
            .flag("d", "descendants", "descendants only")
            .flag(null, "timeline", "draw lives on a time axis instead of a tree")
            .value("n", "depth", "N", "generations each way (default 4)")
            .flag(null, "all", "no depth limit")
            .flag("S", "no-spouses", "don't draw partners of descendants")
            .value("o", "output", "FILE", "where to write: .html (default), .png or .svg for an image")
            .value(null, "scale", "N", "PNG resolution multiplier (default 2; 1 = smaller file)")
            .value("t", "title", "TEXT", "heading (default: the person's name)")
            .value(null, "lang", "en|es", "language of labels and dates (default en)")
            .flag(null, "notes", "include notes in the click-for-details panel")
            .value(null, "theme", "modern|classic|baroque", "look of the cards (default modern)")
            .flag(null, "arms", "baroque: bear the flag on a shield (men) or lozenge (women)")
            .flag(null, "dark", "dark colours (HTML starts dark; viewers can switch); not for classic");

    @Override public String name() { return "chart"; }
    @Override public String summary() { return "write a printable family-tree picture (HTML or SVG)"; }

    @Override
    public String usage() {
        return """
                chart [-a|-d] [--timeline] [-n N|--all] [-S] [-o FILE] [--scale N] [-t TITLE] [--lang en|es] [--notes]
                      [--theme modern|classic|baroque] [--arms] [--dark] [PATH]

                Draws PATH (default: current person) with ancestors to the left and
                descendants (with their partners) to the right, one column per
                generation. Writes a single self-contained HTML file: it opens in
                any browser, needs no internet, and can be emailed as is. Click a
                person for dates, places and relatives; Print fits it to a page
                (choose "Save as PDF" there to send a PDF instead).

                Each card shows initials tinted by sex, birth country flag, years,
                age, birthplace and record id; the page follows the viewer's
                light/dark setting and prints light. Someone who appears twice
                (cousin marriages) is drawn once and then as a dashed card. Notes
                are left out unless you ask for them with --notes. --theme classic
                is the plainer warm-paper look (light only). --theme baroque is a
                19th-century engraved look on parchment: framed cards with corner
                scrollwork and the birth flag on top, a bordered page with arabesques and a
                flourished script title; its dark version is candlelit. Add
                --arms to bear each flag on a shield for men or a lozenge for
                women, as old heraldry did.

                  chart                          4 generations each way → NAME-tree.html
                  chart -a -n 6 -o abuelos.html  6 generations of ancestors
                  chart --lang es ~              Spanish labels, home person
                  chart -o arbol.png             a picture to text or email
                  chart -d --all -o family.svg   every descendant, as a scalable image
                  chart --dark -o arbol.png      a dark picture
                  chart --theme classic          the original look
                  chart --theme baroque -o arbol.png   an heirloom-style picture

                --timeline draws everyone on a time axis instead: one bar per life,
                from birth to death (or to today), so longer lives are longer bars,
                and each child branches off its parents' bars in the year it was
                born. Missing dates are estimated from relatives and drawn pale.

                  chart --timeline               the same people, as lifelines
                  chart --timeline -d --all -o descendientes.png --lang es

                A PNG is drawn at 2× for sharp printing; very large charts are drawn
                at a lower resolution so the image stays a manageable size.

                """ + opts.describe();
    }

    @Override
    public void run(List<String> args, Context ctx) {
        var o = opts.parse(args);
        if (o.positionals().size() > 1) throw new CommandException("too many arguments");
        if (o.has("ancestors") && o.has("descendants")) throw new CommandException("choose one of -a and -d");
        int depth = o.has("all") ? Integer.MAX_VALUE : o.get("depth").map(ChartCommand::depth).orElse(4);
        Labels labels;
        Theme theme;
        try {
            labels = Labels.of(o.get("lang").orElse("en"));
            theme = Theme.of(o.get("theme").orElse("modern"));
        } catch (IllegalArgumentException e) {
            throw new CommandException(e.getMessage());
        }
        boolean dark = o.has("dark");
        if (dark && !theme.hasDark()) throw new CommandException("the classic theme has no dark colours; drop --dark or --theme classic");

        var loc = ctx.nav().resolve(o.positionals().isEmpty() ? "" : o.positionals().getFirst()).location();
        Person root = switch (loc) {
            case Location.PersonDir(var p) -> p;
            case Location.FamilyDir(var f) -> f.husband().or(f::wife).flatMap(ctx.g()::person)
                    .orElseThrow(() -> new CommandException("family " + f.key() + " has no partners"));
            default -> throw new CommandException(ctx.fs().describe(loc) + " is not a person or family");
        };

        var spec = new Layout.Spec(o.has("descendants") ? 0 : depth, o.has("ancestors") ? 0 : depth,
                !o.has("no-spouses"), labels, theme);
        boolean timeline = o.has("timeline");
        if (timeline && !theme.hasTimeline()) throw new CommandException("--timeline needs the modern or baroque look; drop --theme classic");
        Layout.Chart chart;
        try {
            chart = timeline ? Timeline.build(ctx.g(), root, spec, LocalDate.now()) : Layout.build(ctx.g(), root, spec);
        } catch (IllegalArgumentException e) {
            throw new CommandException(e.getMessage());
        }
        var title = o.get("title").orElse(root.name().display());
        if (o.has("arms") && !theme.ornate()) throw new CommandException("--arms goes with --theme baroque");
        var page = new ChartPage(ctx.g(), chart, labels, title, o.has("notes"), LocalDate.now()).arms(o.has("arms"));

        var target = Path.of(o.get("output").orElse(slug(root) + (timeline ? "-timeline.html" : "-tree.html")));
        var file = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (file.endsWith(".ged") || isSource(ctx.g(), target)) {
            throw new CommandException("refusing to overwrite a GEDCOM file; choose a .html or .svg name");
        }
        double scale = o.get("scale").map(ChartCommand::scale).orElse(2.0);
        var note = "";
        byte[] content;
        try {
            if (file.endsWith(".png")) {
                System.setProperty("java.awt.headless", "true");
                var png = new PngChart(page, dark);
                double used = png.effectiveScale(scale);
                if (used < scale - 1e-9) note = String.format(Locale.ROOT, ", reduced to %.2f× to limit size", used);
                content = png.png(scale);
            } else if (file.endsWith(".svg")) {
                content = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + page.svg(true, dark ? ChartPage.Colors.DARK : ChartPage.Colors.LIGHT)).getBytes(StandardCharsets.UTF_8);
            } else {
                content = page.html(dark).getBytes(StandardCharsets.UTF_8);
            }
            var parent = target.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(target, content);
        } catch (IOException e) {
            throw new CommandException("can't write " + target + ": " + e.getMessage());
        }
        var s = ctx.style();
        ctx.out().println(s.green("wrote ") + target + s.dim("  (" + page.subtitle() + note + ")"));
    }

    private static boolean isSource(Genealogy g, Path target) {
        return g.source().map(src -> src.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())).orElse(false);
    }

    /// `José María Pérez` → `jose-maria-perez`
    static String slug(Person p) {
        var s = Normalizer.normalize(p.name().full(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? p.key().toLowerCase(Locale.ROOT) : s;
    }

    private static double scale(String v) {
        try {
            double d = Double.parseDouble(v);
            if (!(d >= 0.5 && d <= 4)) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            throw new CommandException("scale must be between 0.5 and 4, got '" + v + "'");
        }
    }

    private static int depth(String v) {
        try {
            int n = Integer.parseInt(v);
            if (n < 1) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new CommandException("depth must be a positive number, got '" + v + "'");
        }
    }
}
