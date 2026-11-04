package ged.chart;

import ged.model.Event;
import ged.model.GedDate;
import ged.model.Person;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Words a reader sees on the chart, in English or Spanish.
public enum Labels {
    EN, ES;

    public static Labels of(String code) {
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "en", "english" -> EN;
            case "es", "spanish", "español", "espanol" -> ES;
            default -> throw new IllegalArgumentException("unknown language '" + code + "' (use en or es)");
        };
    }

    public String code() { return name().toLowerCase(Locale.ROOT); }

    private String pick(String en, String es) { return this == EN ? en : es; }

    /// Subtitle lead-in: `Family tree with 32 people`.
    public String treeWith(int n) { return pick("Family tree with ", "Árbol genealógico con ") + people(n); }
    public String timelineWith(int n) { return pick("Family timeline with ", "Línea de tiempo familiar con ") + people(n); }
    public String today() { return pick("Today", "Hoy"); }
    public String estimated() { return pick("Pale: dates estimated", "Claro: fechas estimadas"); }
    public String people(int n) { return n + pick(n == 1 ? " person" : " people", n == 1 ? " persona" : " personas"); }
    public String generations(int n) { return n + pick(n == 1 ? " generation" : " generations", n == 1 ? " generación" : " generaciones"); }
    public String made(LocalDate d) { return pick("made ", "creado el ") + date(d.getDayOfMonth(), d.getMonthValue(), d.getYear()); }
    public String unnamed() { return pick("(unnamed)", "(sin nombre)"); }
    public String repeat() { return pick("↻ also shown elsewhere", "↻ aparece en otro lugar"); }
    public String born() { return pick("b. ", "n. "); }
    public String died() { return pick("d. ", "f. "); }
    public String divorced() { return pick("div.", "div."); }
    public String male() { return pick("Male", "Hombre"); }
    public String female() { return pick("Female", "Mujer"); }
    public String repeatLegend() { return pick("Appears more than once", "Aparece más de una vez"); }
    public String parents() { return pick("Parents", "Padres"); }
    public String spouses() { return pick("Married to", "Casado/a con"); }
    public String children() { return pick("Children", "Hijos"); }
    public String notes() { return pick("Notes", "Notas"); }
    public String hint() { return pick("Click a person for details · drag to move around", "Toca una persona para ver detalles · arrastra para moverte"); }
    public String fit() { return pick("Fit", "Ajustar"); }
    public String print() { return pick("Print", "Imprimir"); }
    public String close() { return pick("Close", "Cerrar"); }
    public String noFacts() { return pick("No dates or places recorded.", "Sin fechas ni lugares registrados."); }
    public String hideControls() { return pick("Hide or show the title and controls", "Ocultar o mostrar el título y los controles"); }
    public String themeAuto() { return pick("Auto", "Auto"); }
    public String themeLight() { return pick("Light", "Claro"); }
    public String themeDark() { return pick("Dark", "Oscuro"); }

    /// `aged 70` (died at 70) or `age 46` (living); `aged 62–63` when the
    /// dates allow either; `c.` when a date is approximate.
    public String age(int min, int max, boolean approx, boolean living) {
        var n = (approx ? "c. " : "") + (min == max ? String.valueOf(min) : min + "–" + max);
        if (this == ES) return n + (min == max && min == 1 ? " año" : " años");
        return (living ? "age " : "aged ") + n;
    }

    /// Column heading for a generation: `up` > 0 for ancestors, `down` > 0 for descendants.
    public String generation(int n, boolean up) {
        if (this == ES) {
            var names = up ? List.of("Padres", "Abuelos", "Bisabuelos", "Tatarabuelos", "Trastatarabuelos")
                    : List.of("Hijos", "Nietos", "Bisnietos", "Tataranietos", "Trastataranietos");
            return n <= names.size() ? names.get(n - 1) : (up ? "Ancestros" : "Descendientes") + " · gen. " + n;
        }
        var base = up ? "parents" : "children";
        return switch (n) {
            case 1 -> up ? "Parents" : "Children";
            case 2 -> "Grand" + base;
            case 3 -> "Great-grand" + base;
            default -> ordinal(n - 2) + " great-grand" + base;
        };
    }

    /// One person's relation to the root: `n` generations up (ancestors) or down.
    public String relation(int n, boolean up, Person.Sex sex) {
        int s = sex == Person.Sex.MALE ? 0 : sex == Person.Sex.FEMALE ? 1 : 2;
        if (this == ES) {
            var stems = up ? List.of("padre|madre|progenitor", "abuel", "bisabuel", "tatarabuel", "trastatarabuel")
                    : List.of("hij", "niet", "bisniet", "tataraniet", "trastataraniet");
            if (n > stems.size()) return (up ? "Ancestro" : "Descendiente") + " · gen. " + n;
            var stem = stems.get(n - 1);
            if (stem.contains("|")) return cap(stem.split("\\|")[s]);
            return cap(stem + List.of("o", "a", "o/a").get(s));
        }
        var base = up ? List.of("father", "mother", "parent") : List.of("son", "daughter", "child");
        var word = n == 1 ? base.get(s)
                : n == 2 ? "grand" + base.get(s)
                : n == 3 ? "great-grand" + base.get(s)
                : ordinal(n - 2) + " great-grand" + base.get(s);
        return cap(word);
    }

    public String spouse(Person.Sex sex) {
        return switch (sex) {
            case MALE -> pick("Husband", "Esposo");
            case FEMALE -> pick("Wife", "Esposa");
            default -> pick("Spouse", "Cónyuge");
        };
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static String ordinal(int n) {
        var suffix = n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th";
        };
        return n + suffix;
    }

    /// Year with a reader-friendly qualifier: `1850`, `c. 1850`, `bef. 1900`.
    public String year(GedDate d) {
        if (!d.hasYear()) return d.isEmpty() ? "" : "?";
        var prefix = switch (d.qualifier()) {
            case ABOUT, BETWEEN, PHRASE, PERIOD -> "c. ";
            case BEFORE -> pick("bef. ", "antes de ");
            case AFTER -> pick("aft. ", "desp. de ");
            default -> "";
        };
        return prefix + d.year();
    }

    private static final List<String> MONTHS_EN =
            List.of("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec");
    private static final List<String> MONTHS_ES =
            List.of("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic");
    private static final List<String> GED_MONTHS =
            List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

    private String date(int day, int month, int year) {
        var m = (this == EN ? MONTHS_EN : MONTHS_ES).get(month - 1);
        return (day > 0 ? day + " " : "") + m + " " + year;
    }

    /// A full GEDCOM date phrase in plain words: `ABT 12 JUN 1850` → `about 12 Jun 1850`.
    public String fullDate(GedDate d) {
        if (d.isEmpty()) return "";
        var words = d.raw().replaceAll("@#D[^@]*@\\s*", "").strip();
        if (words.startsWith("(") && words.endsWith(")")) return words.substring(1, words.length() - 1);
        var sb = new StringBuilder();
        for (var w : words.split("\\s+")) {
            var u = w.toUpperCase(Locale.ROOT);
            int mi = GED_MONTHS.indexOf(u);
            var word = mi >= 0 ? (this == EN ? MONTHS_EN : MONTHS_ES).get(mi) : switch (u) {
                case "ABT", "ABOUT", "CIRCA" -> pick("about", "hacia");
                case "CAL" -> pick("calculated", "calculado");
                case "EST" -> pick("estimated", "estimado");
                case "BEF" -> pick("before", "antes de");
                case "AFT" -> pick("after", "después de");
                case "BET" -> pick("between", "entre");
                case "AND" -> pick("and", "y");
                case "FROM" -> pick("from", "desde");
                case "TO" -> pick("to", "hasta");
                case "INT" -> "";
                default -> w;
            };
            if (!word.isEmpty()) sb.append(sb.isEmpty() ? "" : " ").append(word);
        }
        return sb.toString();
    }

    private static final Map<String, String> EVENTS_ES = Map.ofEntries(
            Map.entry("BIRT", "Nacimiento"), Map.entry("CHR", "Bautismo"), Map.entry("BAPM", "Bautismo"),
            Map.entry("DEAT", "Fallecimiento"), Map.entry("BURI", "Sepultura"), Map.entry("CREM", "Cremación"),
            Map.entry("CONF", "Confirmación"), Map.entry("FCOM", "Primera comunión"), Map.entry("EMIG", "Emigración"),
            Map.entry("IMMI", "Inmigración"), Map.entry("NATU", "Naturalización"), Map.entry("CENS", "Censo"),
            Map.entry("GRAD", "Graduación"), Map.entry("RETI", "Jubilación"), Map.entry("RESI", "Residencia"),
            Map.entry("OCCU", "Ocupación"), Map.entry("EDUC", "Educación"), Map.entry("RELI", "Religión"),
            Map.entry("TITL", "Título"), Map.entry("NATI", "Nacionalidad"), Map.entry("MARR", "Casamiento"),
            Map.entry("DIV", "Divorcio"), Map.entry("ENGA", "Compromiso"), Map.entry("EVEN", "Evento"),
            Map.entry("ADOP", "Adopción"), Map.entry("ORDN", "Ordenación"), Map.entry("WILL", "Testamento"),
            Map.entry("PROB", "Sucesión"), Map.entry("FACT", "Dato"));

    private static final Map<String, String> EVENTS_EN = Map.of(
            "BIRT", "Birth", "CHR", "Baptism", "BAPM", "Baptism", "DEAT", "Death", "BURI", "Burial",
            "MARR", "Marriage", "DIV", "Divorce");

    public String event(Event e) {
        if (!e.type().isEmpty() && (e.tag().equals("EVEN") || e.tag().equals("FACT"))) return e.type();
        var m = this == EN ? EVENTS_EN : EVENTS_ES;
        return m.getOrDefault(e.tag(), e.label());
    }
}
