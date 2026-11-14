package app.candidatures.cv;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Import d'un CV existant (PDF texte) : extrait le texte et le range dans les sections de l'éditeur.
 *
 * C'est une lecture par heuristiques (titres de sections connus, e-mail / téléphone / liens repérés,
 * lignes de dates = début d'une expérience) : le résultat est un brouillon à relire, pas une copie parfaite.
 * Un PDF scanné (image sans texte) ne peut pas être lu.
 */
public final class CvImport {

    /** CV lu et avertissements à montrer à l'utilisateur. */
    public record Result(Map<String, Object> cv, List<String> warnings) {}

    /** Période trouvée dans une ligne : début, fin, « en cours », et le texte de la ligne sans la date. */
    record Range(String start, String end, boolean current, String rest) {}

    /** Lignes d'une entrée (expérience, projet, formation) avec la position de son intitulé et de sa date. */
    private record Entry(List<String> lines, int head, Integer date) {}

    private static final int U = Pattern.UNICODE_CHARACTER_CLASS;
    private static final int I = U | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String BULLET = "•";

    // ---------- titres de sections reconnus (comparés sans accents ni majuscules) ----------
    private static final Map<String, String> HEADING_INDEX = new HashMap<>();

    private static void headings(String key, String... names) {
        for (String name : names) {
            HEADING_INDEX.put(name, key);
        }
    }

    static {
        headings("summary", "profil", "profile", "resume", "summary", "a propos", "a propos de moi", "about", "about me", "objectif",
                "objective", "accroche", "presentation", "profil professionnel", "professional summary");
        headings("experience", "experience", "experiences", "experience professionnelle", "experiences professionnelles",
                "parcours professionnel", "work experience", "professional experience", "employment", "employment history",
                "stages", "stages et experiences", "experiences et stages", "alternance", "work history", "emplois");
        headings("projects", "projets", "projects", "projets personnels", "projets academiques", "personal projects", "realisations",
                "projets realises", "side projects", "portfolio");
        headings("education", "formation", "formations", "education", "etudes", "diplomes", "diplomes et formations", "cursus",
                "parcours academique", "parcours scolaire", "academic background", "formation academique", "scolarite");
        headings("hard_skills", "competences", "competences techniques", "skills", "technical skills", "hard skills", "outils",
                "technologies", "informatique", "competences informatiques", "stack", "stack technique",
                "outils et technologies", "connaissances", "connaissances techniques", "savoir-faire", "expertise");
        headings("soft_skills", "soft skills", "savoir-etre", "savoir etre", "qualites", "competences comportementales", "atouts",
                "qualites personnelles", "personal skills", "competences personnelles", "aptitudes");
        headings("languages", "langues", "languages", "langues etrangeres", "language skills", "langues parlees");
        headings("certifications", "certifications", "certification", "certificats", "certificates", "licences et certifications",
                "licenses & certifications", "licenses and certifications");
        headings("interests", "centres d'interet", "centres d interet", "centre d'interet", "loisirs", "interets", "hobbies",
                "interests", "passions", "activites", "activites extra-professionnelles", "vie associative");
    }

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("janv", 1), Map.entry("janvier", 1), Map.entry("january", 1),
            Map.entry("feb", 2), Map.entry("fev", 2), Map.entry("fevr", 2), Map.entry("fevrier", 2), Map.entry("february", 2),
            Map.entry("mar", 3), Map.entry("mars", 3), Map.entry("march", 3),
            Map.entry("apr", 4), Map.entry("avr", 4), Map.entry("avril", 4), Map.entry("april", 4),
            Map.entry("mai", 5), Map.entry("may", 5),
            Map.entry("jun", 6), Map.entry("juin", 6), Map.entry("june", 6),
            Map.entry("jul", 7), Map.entry("juil", 7), Map.entry("juillet", 7), Map.entry("july", 7),
            Map.entry("aou", 8), Map.entry("aout", 8), Map.entry("aug", 8), Map.entry("august", 8),
            Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("septembre", 9), Map.entry("september", 9),
            Map.entry("oct", 10), Map.entry("octobre", 10), Map.entry("october", 10),
            Map.entry("nov", 11), Map.entry("novembre", 11), Map.entry("november", 11),
            Map.entry("dec", 12), Map.entry("decembre", 12), Map.entry("december", 12));

    private static final String PRESENT = "(?:aujourd'hui|aujourd’hui|present|présent|actuel(?:lement)?|en cours|now|current|ce jour|maintenant)";
    private static final String MONTH_RE;
    private static final String DATE;

    static {
        Set<String> names = new HashSet<>(MONTHS.keySet());
        names.addAll(List.of("févr", "fév", "déc", "décembre", "août", "février"));
        MONTH_RE = "(?:" + names.stream().sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .map(Pattern::quote).collect(Collectors.joining("|")) + ")\\.?";
        DATE = "(?:" + MONTH_RE + "\\s+\\d{4}|\\d{1,2}[/.-]\\d{4}|\\d{4}[/.-]\\d{1,2}|\\d{4})";
    }

    private static final Pattern RANGE_RE = Pattern.compile("(?<start>" + DATE + ")\\s*(?:[-–—]|à|a|to|au|>|->)\\s*(?<end>" + DATE + "|" + PRESENT + ")"
            + "|(?:depuis|since)\\s+(?<since>" + DATE + ")|(?<single>" + DATE + ")", I);
    private static final Pattern PRESENT_RE = Pattern.compile(PRESENT, I);

    private static final Pattern EMAIL_RE = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+", U);
    private static final Pattern PHONE_RE = Pattern.compile("(?:\\+\\d{1,3}[\\s.]?)?\\(?\\d{1,4}\\)?(?:[\\s.-]?\\d{2,4}){3,5}", U);
    private static final Pattern URL_RE = Pattern.compile("(?:https?://)?(?:www\\.)?[\\w-]+(?:\\.[\\w-]+)+(?:/[^\\s|,]*)?", I);
    private static final Pattern BULLET_RE = Pattern.compile("^\\s*[•●▪■◦○►▸‣⁃\\-–*·\\x7f]+\\s*", U);
    private static final Pattern SEP_RE = Pattern.compile("\\s+[—–|@·]\\s+|\\s+-\\s+|\\s+chez\\s+|\\s+at\\s+", U);

    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00a0]+");
    private static final Pattern ANY_SPACES = Pattern.compile("\\s+", U);
    private static final Pattern MARKS = Pattern.compile("\\p{Mn}+");
    private static final Pattern NO_JOIN = Pattern.compile("@|\\||https?:|www\\.");
    private static final Pattern HEADER_SPLIT = Pattern.compile("\\s*[|•·◆♦\\x7f\\uf0b7]\\s*|\\s{3,}", U);
    private static final Pattern PHONE_LABEL = Pattern.compile("^(tel|tél|phone|mobile)\\s*:?\\s*", I);
    private static final Pattern ADDRESS_LABEL = Pattern.compile("^(adresse|address)\\s*:?\\s*", I);
    private static final Pattern AVAILABILITY = Pattern.compile("disponib|available|a partir de|à partir de");
    private static final Pattern LICENCE = Pattern.compile("permis|vehicule|driving");
    private static final Pattern POSTCODE = Pattern.compile("\\b\\d{5}\\b", U);
    private static final Pattern ADDRESS_WORD = Pattern.compile("^(adresse|address)\\b", U);
    private static final Pattern NON_DIGIT = Pattern.compile("\\D", U);
    private static final Pattern LABELLED = Pattern.compile("^\\s*\\w+\\s*:", U);
    private static final Pattern YEAR_AT_END = Pattern.compile("\\d{4}\\s*$", U);
    private static final Pattern MONTH_YEAR_1 = Pattern.compile("(\\d{1,2})[/.-](\\d{4})", U);
    private static final Pattern MONTH_YEAR_2 = Pattern.compile("(\\d{4})[/.-](\\d{1,2})", U);
    private static final Pattern MONTH_NAME_YEAR = Pattern.compile("([a-z]+)\\.?\\s+(\\d{4})", U);
    private static final Pattern YEAR = Pattern.compile("\\d{4}", U);
    private static final Pattern TECH_LINE = Pattern.compile("^(technologies|techno|stack|outils|tech)\\s*:", I);
    private static final Pattern PIPE = Pattern.compile("\\s*\\|\\s*", U);
    private static final Pattern TECH_SPLIT = Pattern.compile("[,;/]");
    private static final Pattern COMMA = Pattern.compile("\\s*,\\s*", U);
    private static final Pattern ORG_WORDS = Pattern.compile("\\b(university|universite|université|college|collège|school|ecole|école|institute|institut|iut|lycee|lycée|"
            + "academy|academie|académie|inc\\.?|ltd|llc|sas|sarl|sa|gmbh|group|groupe|labs?|bank|banque|corp\\.?|"
            + "company|technologies|solutions|consulting|agency|agence)$", I);
    private static final Pattern ORG_DASH_PLACE = Pattern.compile("^(.+?)\\s+(?:[—–|-]|·)\\s+(.+)$", U);
    private static final Pattern ORG_CITY_REGION = Pattern.compile("^(.+?),\\s*([A-ZÀ-Ý][\\w.]+)$", U);
    private static final Pattern ITEM_SPLIT = Pattern.compile("\\s*[,;|•·\\x7f]\\s*|\\s{3,}", U);
    private static final Pattern SKILL_GROUP = Pattern.compile("^([^:]{2,40})\\s*:\\s*(.+)$", U);
    private static final Pattern LANG_SPLIT = Pattern.compile("\\s*[|•·;\\x7f]\\s*|\\s{3,}|\\s+(?=[A-ZÀ-Ý][a-zà-ÿ]{2,}\\s*[:(])", U);
    private static final Pattern LANG_COLON = Pattern.compile("^([^:]+?)\\s*:\\s*(.+)$", U);
    private static final Pattern LANG_PAREN = Pattern.compile("^([^(]+?)\\s*\\((.+)\\)$", U);
    private static final Pattern LANG_DASH = Pattern.compile("^(.+?)\\s+[-–—]\\s+(.+)$", U);
    private static final Pattern LANG_LEVEL = Pattern.compile("\\b([ABC][12]|natif|native|courant|fluent|bilingue|notions|scolaire|intermediaire|intermédiaire|maternelle)\\b", I);

    private CvImport() {}

    static String norm(String s) {
        String folded = MARKS.matcher(Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
        return ANY_SPACES.matcher(folded).replaceAll(" ").strip();
    }

    // ---------- point d'entrée ----------

    public static Result parsePdf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pages = new ArrayList<>();
            for (int p = 1; p <= document.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(stripper.getText(document));
            }
            String text = String.join("\n", pages);
            Map<String, Object> cv = parseText(text);
            List<String> warnings = new ArrayList<>();
            if (text.strip().length() < 40) {
                warnings.add("Presque aucun texte trouvé : ce PDF est sans doute une image scannée, il ne peut pas être importé.");
            }
            String photo = photo(document);
            if (photo != null) {
                cv.put("photo", photo);
                cv.put("show_photo", true);
            }
            return new Result(cv, warnings);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseText(String text) {
        Map<String, Object> cv = CvModel.empty();
        Map<String, Object> info = (Map<String, Object>) cv.get("info");
        // découpe en blocs : en-tête (avant le premier titre connu) puis une liste par section
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        blocks.put("header", new ArrayList<>());
        String current = "header";
        List<String> order = new ArrayList<>();
        for (String line : lines(text)) {
            String key = heading(line);
            if (key != null) {
                current = key;
                blocks.computeIfAbsent(key, k -> new ArrayList<>());
                if (!order.contains(key)) {
                    order.add(key);
                }
            } else {
                blocks.get(current).add(line);
            }
        }

        header(info, blocks.get("header"));
        if (!block(blocks, "summary").isEmpty()) {
            info.put("summary", join(block(blocks, "summary")));
        }
        for (String kind : List.of("experience", "projects", "education")) {
            List<Object> entries = new ArrayList<>();
            for (Entry e : splitEntries(block(blocks, kind))) {
                entries.add(entry(e, kind));
            }
            cv.put(kind, entries);
        }
        cv.put("certifications", certifications(block(blocks, "certifications")));
        cv.put("hard_skills", skillGroups(block(blocks, "hard_skills")));
        cv.put("soft_skills", items(block(blocks, "soft_skills")));
        cv.put("interests", items(block(blocks, "interests")));
        cv.put("languages", languages(block(blocks, "languages")));
        // garde l'ordre des sections du CV d'origine
        List<String> sectionOrder = new ArrayList<>();
        order.stream().filter(CvModel.SECTIONS::contains).forEach(sectionOrder::add);
        CvModel.SECTIONS.stream().filter(k -> !order.contains(k)).forEach(sectionOrder::add);
        cv.put("section_order", sectionOrder);
        return cv;
    }

    private static List<String> block(Map<String, List<String>> blocks, String key) {
        return blocks.getOrDefault(key, List.of());
    }

    // ---------- lignes ----------

    private static List<String> lines(String text) {
        List<String> texts = new ArrayList<>();
        List<Boolean> bullets = new ArrayList<>();
        for (String raw : text.replace("\r", "\n").split("\n")) {
            String line = SPACES.matcher(raw).replaceAll(" ").strip();
            if (line.isEmpty()) {
                continue;
            }
            boolean bullet = BULLET_RE.matcher(line).lookingAt() && !RANGE_RE.matcher(line).lookingAt();
            if (bullet) {
                line = BULLET_RE.matcher(line).replaceFirst("").strip();
            }
            if (line.isEmpty()) {
                continue;
            }
            // une phrase coupée en fin de ligne continue en minuscule sur la suivante : on recolle
            if (!texts.isEmpty() && !bullet && Character.isLowerCase(line.charAt(0))) {
                String last = texts.get(texts.size() - 1);
                if (last.length() >= 45 && !last.endsWith(".") && !last.endsWith(":") && !last.endsWith(";")
                        && !NO_JOIN.matcher(line).find() && heading(last) == null) {
                    texts.set(texts.size() - 1, last + " " + line);
                    continue;
                }
            }
            texts.add(line);
            bullets.add(bullet);
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            out.add(bullets.get(i) ? BULLET + texts.get(i) : texts.get(i));
        }
        return out;
    }

    private static String heading(String line) {
        if (line.startsWith(BULLET) || line.length() > 45) {
            return null;
        }
        return HEADING_INDEX.get(stripEnd(norm(line), " :").strip());
    }

    private static boolean isBullet(String line) {
        return line.startsWith(BULLET);
    }

    private static String stripBullet(String line) {
        return isBullet(line) ? line.substring(1) : line;
    }

    private static String join(List<String> lines) {
        return lines.stream().map(CvImport::stripBullet).collect(Collectors.joining(" ")).strip();
    }

    // ---------- en-tête : nom, titre, coordonnées ----------

    private static void setIfEmpty(Map<String, Object> info, String key, String value) {
        if (((String) info.get(key)).isEmpty()) {
            info.put(key, value);
        }
    }

    private static void header(Map<String, Object> info, List<String> lines) {
        List<String> rest = new ArrayList<>();
        for (String raw : lines) {
            String line = stripBullet(raw);
            List<String> leftovers = new ArrayList<>();
            for (String piece : HEADER_SPLIT.split(line)) {
                String part = strip(piece, " ,;");
                if (part.isEmpty()) {
                    continue;
                }
                String low = norm(part);
                Matcher email = EMAIL_RE.matcher(part);
                String withoutPhoneLabel = PHONE_LABEL.matcher(part).replaceFirst("");
                if (email.find()) {
                    setIfEmpty(info, "email", email.group());
                } else if (low.contains("linkedin")) {
                    setIfEmpty(info, "linkedin", url(part));
                } else if (low.contains("github") || low.contains("gitlab")) {
                    setIfEmpty(info, "github", url(part));
                } else if (PHONE_RE.matcher(withoutPhoneLabel).matches() && NON_DIGIT.matcher(part).replaceAll("").length() >= 9) {
                    setIfEmpty(info, "phone", withoutPhoneLabel);
                } else if (URL_RE.matcher(part).matches() && part.contains(".") && !part.contains(" ")) {
                    setIfEmpty(info, "website", part);
                } else if (AVAILABILITY.matcher(low).find()) {
                    setIfEmpty(info, "availability", part);
                } else if (LICENCE.matcher(low).find()) {
                    String extra = (String) info.get("extra");
                    info.put("extra", extra.isEmpty() ? part : extra + ", " + part);
                } else if (POSTCODE.matcher(part).find() || ADDRESS_WORD.matcher(low).find()) {
                    setIfEmpty(info, "city", ADDRESS_LABEL.matcher(part).replaceFirst(""));
                } else {
                    leftovers.add(part);
                }
            }
            if (!leftovers.isEmpty()) {
                rest.add(String.join(" ", leftovers));
            }
        }
        // nom = première ligne courte, titre = la suivante, une ville seule ensuite
        for (String line : rest) {
            String[] words = line.strip().split("\\s+");
            boolean digits = line.chars().anyMatch(Character::isDigit);
            if (((String) info.get("first_name")).isEmpty() && words.length <= 5 && !digits) {
                String[] name = splitName(List.of(words));
                info.put("first_name", name[0]);
                info.put("last_name", name[1]);
            } else if (((String) info.get("title")).isEmpty() && line.length() <= 120) {
                info.put("title", line);
            } else if (((String) info.get("city")).isEmpty() && words.length <= 4 && !digits) {
                info.put("city", line);
            } else {
                String summary = (String) info.get("summary");
                info.put("summary", summary.isEmpty() ? line : summary + " " + line);
            }
        }
    }

    private static boolean isUpper(String s) {
        return s.equals(s.toUpperCase(Locale.ROOT)) && !s.equals(s.toLowerCase(Locale.ROOT));
    }

    /** « DUPONT-MARTIN » -> « Dupont-Martin ». */
    private static String titleCase(String s) {
        StringBuilder out = new StringBuilder();
        boolean start = true;
        for (char c : s.toCharArray()) {
            out.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = !Character.isLetter(c);
        }
        return out.toString();
    }

    private static String[] splitName(List<String> words) {
        if (words.size() == 1) {
            return new String[] {words.get(0), ""};
        }
        // NOM en majuscules = nom de famille, où qu'il soit
        List<String> upper = words.stream().filter(w -> isUpper(w) && w.length() > 1).toList();
        if (!upper.isEmpty() && upper.size() < words.size()) {
            return new String[] {
                    words.stream().filter(w -> !upper.contains(w)).collect(Collectors.joining(" ")),
                    upper.stream().map(CvImport::titleCase).collect(Collectors.joining(" "))};
        }
        return new String[] {String.join(" ", words.subList(0, words.size() - 1)), words.get(words.size() - 1)};
    }

    private static String url(String part) {
        String target = part;
        if (LABELLED.matcher(part).lookingAt() && !part.contains("://")) {
            target = part.substring(part.indexOf(':') + 1);
        }
        Matcher m = URL_RE.matcher(target);
        return m.find() ? m.group() : part;
    }

    // ---------- dates ----------

    private static String toMonth(String value) {
        String v = stripEnd(norm(value), ".");
        if (PRESENT_RE.matcher(v).matches() || PRESENT_RE.matcher(value.strip()).matches()) {
            return null;
        }
        Matcher m = MONTH_YEAR_1.matcher(v);
        if (m.matches()) {
            return String.format("%s-%02d", m.group(2), Integer.parseInt(m.group(1)));
        }
        m = MONTH_YEAR_2.matcher(v);
        if (m.matches()) {
            return String.format("%s-%02d", m.group(1), Integer.parseInt(m.group(2)));
        }
        m = MONTH_NAME_YEAR.matcher(v);
        if (m.matches()) {
            String name = m.group(1);
            Integer month = MONTHS.get(name);
            if (month == null) {
                month = MONTHS.get(name.substring(0, Math.min(4, name.length())));
            }
            if (month == null) {
                month = MONTHS.get(name.substring(0, Math.min(3, name.length())));
            }
            return month != null ? String.format("%s-%02d", m.group(2), month) : m.group(2);
        }
        return YEAR.matcher(v).matches() ? v : value.strip();
    }

    /**
     * Période contenue dans la ligne, sinon null. Une année seule ne compte que si la ligne est courte
     * (sinon « +30 % en 2024 » serait pris pour une date).
     */
    static Range findRange(String line) {
        Matcher m = RANGE_RE.matcher(line);
        while (m.find()) {
            boolean single = m.group("single") != null;
            if (single && (line.length() > 60 || !YEAR_AT_END.matcher(line).find())) {
                continue;
            }
            String rest = strip(line.substring(0, m.start()) + " " + line.substring(m.end()), " ,;:()|—–-");
            if (single && !rest.isEmpty() && rest.split("\\s+").length > 8) {
                continue;
            }
            if (m.group("since") != null) {
                return new Range(toMonth(m.group("since")), "", true, rest);
            }
            if (single) {
                return new Range(toMonth(m.group("single")), "", false, rest);
            }
            String end = m.group("end");
            boolean current = PRESENT_RE.matcher(end.strip()).matches();
            return new Range(toMonth(m.group("start")), current ? "" : toMonth(end), current, rest);
        }
        return null;
    }

    private static boolean hasRest(String line) {
        Range r = findRange(line);
        return r != null && !r.rest().isEmpty();
    }

    // ---------- expériences, projets, formations ----------

    /** Une entrée commence à chaque ligne qui contient une période (ou à la ligne juste avant une date seule). */
    private static List<Entry> splitEntries(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        if (lines.isEmpty()) {
            return entries;
        }
        List<Integer> dates = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!isBullet(lines.get(i)) && findRange(lines.get(i)) != null) {
                dates.add(i);
            }
        }
        // mise en page « intitulé / organisme / lieu puis date » : plusieurs lignes avant la première date
        boolean datesLast = !dates.isEmpty() && dates.get(0) >= 2 && !hasRest(lines.get(dates.get(0)))
                && lines.subList(0, dates.get(0)).stream().noneMatch(CvImport::isBullet);
        // mise en page « Organisme  Lieu » puis « Intitulé  Dates » : la ligne d'avant appartient à l'entrée
        boolean orgFirst = !dates.isEmpty() && dates.get(0) == 1 && hasRest(lines.get(1)) && !isBullet(lines.get(0))
                && !SEP_RE.matcher(findRange(lines.get(1)).rest()).find();
        List<int[]> starts = new ArrayList<>();   // (ligne de l'intitulé, ligne de la date ou -1)
        for (int n = 0; n < dates.size(); n++) {
            int i = dates.get(n);
            int prev = n > 0 ? dates.get(n - 1) : -1;
            int head;
            if (orgFirst && i - 1 > prev && !isBullet(lines.get(i - 1))) {
                head = i - 1;
            } else if (hasRest(lines.get(i))) {            // la date est sur la ligne de l'intitulé
                head = i;
            } else if (datesLast) {                        // tout ce qui suit les puces de l'entrée précédente
                head = n == 0 ? 0 : prev + 1;
                while (head < i && (isBullet(lines.get(head)) || (n > 0 && head == prev + 1 && lines.get(head).length() > 60))) {
                    head++;
                }
                head = Math.min(head, i);
            } else {                                       // date seule juste sous l'intitulé
                head = Math.max(i - 1, starts.isEmpty() ? 0 : starts.get(starts.size() - 1)[1] + 1);
                if (isBullet(lines.get(head))) {
                    head = i;
                }
            }
            starts.add(new int[] {head, i});
        }
        if (starts.isEmpty()) {
            // pas de dates : chaque ligne non-puce suivie de puces est une entrée
            for (int i = 0; i < lines.size(); i++) {
                if (!isBullet(lines.get(i)) && (i == 0 || isBullet(lines.get(i - 1)))) {
                    starts.add(new int[] {i, -1});
                }
            }
            if (starts.isEmpty()) {
                starts.add(new int[] {0, -1});
            }
        }
        for (int n = 0; n < starts.size(); n++) {
            int head = starts.get(n)[0];
            int date = starts.get(n)[1];
            int end = n + 1 < starts.size() ? starts.get(n + 1)[0] : lines.size();
            int start = n == 0 ? 0 : head;
            entries.add(new Entry(new ArrayList<>(lines.subList(start, Math.max(start, end))), head - start, date < 0 ? null : date - start));
        }
        return entries;
    }

    private static int wordCount(String s) {
        String t = s.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private static Map<String, Object> entry(Entry e, String kind) {
        List<String> lines = e.lines();
        Map<String, Object> item = new LinkedHashMap<>();
        List<String> headParts = new ArrayList<>();
        List<String> desc = new ArrayList<>();
        List<String> pre = new ArrayList<>();
        String dateRest = "";
        String orgBelow = null;
        Integer date = e.date();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String plain = stripBullet(line);
            Range range = date != null && i == date ? findRange(plain) : null;
            if (range != null) {
                item.put("start", range.start() == null ? "" : range.start());
                item.put("end", range.end() == null ? "" : range.end());
                item.put("current", range.current());
                dateRest = range.rest();
                if (!dateRest.isEmpty()) {
                    headParts.add(dateRest);
                }
            } else if (date != null && i < date && i >= e.head() && !isBullet(line)) {
                pre.add(plain);
                headParts.add(plain);
            } else if (date != null && i == date + 1 && !dateRest.isEmpty() && pre.isEmpty() && !kind.equals("projects")
                    && !isBullet(line) && !SEP_RE.matcher(dateRest).find() && wordCount(plain) <= 8 && !plain.endsWith(".")) {
                // « Intitulé  Dates » puis « Organisme  Lieu » sur la ligne suivante
                String[] orgLoc = orgLocation(plain);
                orgBelow = orgLoc[0];
                if (!orgLoc[1].isEmpty()) {
                    item.put("location", orgLoc[1]);
                }
            } else if (i < e.head() || (i == e.head() && !isBullet(line))
                    || (date != null && e.head() < i && i < date && !isBullet(line))) {
                headParts.add(plain);
            } else if (!isBullet(line) && desc.isEmpty() && wordCount(plain) <= 4 && !plain.endsWith(".")
                    && (kind.equals("experience") || kind.equals("education")) && empty(item.get("location"))
                    && i <= (date == null ? 0 : date) + 2) {
                item.put("location", plain);   // ligne courte juste sous l'intitulé : le lieu
            } else {
                desc.add(plain);
            }
        }

        String[] split = splitTitle(String.join(" ", headParts));
        String title = split[0];
        String org = split[1];
        if (orgBelow != null && !orgBelow.isEmpty()) {
            title = dateRest;
            org = orgBelow;
        } else if (pre.size() == 1 && !dateRest.isEmpty() && !SEP_RE.matcher(dateRest).find() && !SEP_RE.matcher(pre.get(0)).find()) {
            // « Organisme  Lieu » puis « Intitulé  Dates »
            String[] orgLoc = orgLocation(pre.get(0));
            org = orgLoc[0];
            title = dateRest;
            if (!orgLoc[1].isEmpty() && !kind.equals("projects") && empty(item.get("location"))) {
                item.put("location", orgLoc[1]);
            }
        } else if (headParts.size() >= 2 && org.isEmpty()) {   // intitulé et organisme sur deux lignes séparées
            title = headParts.get(0);
            org = headParts.get(1);
            String extra = String.join(" ", headParts.subList(2, headParts.size()));
            if (!extra.isEmpty() && wordCount(extra) <= 4 && !kind.equals("projects")) {
                if (empty(item.get("location"))) {
                    item.put("location", extra);
                }
            } else if (!extra.isEmpty()) {
                desc.add(0, extra);
            }
        }
        switch (kind) {
            case "projects" -> {
                List<String> techLines = desc.stream().filter(d -> TECH_LINE.matcher(d).lookingAt()).toList();
                List<String> tech = new ArrayList<>();
                for (String d : techLines) {
                    String body = d.substring(d.indexOf(':') + 1);
                    for (String piece : PIPE.split(body)) {
                        if (URL_RE.matcher(piece.strip()).matches() && piece.contains(".")) {
                            item.put("link", piece.strip());
                        } else {
                            for (String t : TECH_SPLIT.split(piece)) {
                                if (!t.isBlank()) {
                                    tech.add(t.strip());
                                }
                            }
                        }
                    }
                }
                desc.removeAll(techLines);
                for (String d : new ArrayList<>(desc)) {
                    if (URL_RE.matcher(d.strip()).matches() && d.contains(".") && empty(item.get("link"))) {
                        item.put("link", d.strip());
                        desc.remove(d);
                    }
                }
                if (tech.isEmpty() && !org.isEmpty() && COMMA.splitAsStream(org).filter(t -> !t.isEmpty()).count() >= 2) {
                    for (String t : org.split(",")) {   // « Nom | Python, Flask, … »
                        if (!t.isBlank()) {
                            tech.add(t.strip());
                        }
                    }
                    org = "";
                }
                item.put("name", title);
                item.put("context", org);
                item.put("tech", tech);
            }
            case "education" -> {
                item.put("degree", title);
                item.put("school", org);
            }
            default -> {
                item.put("title", title);
                item.put("company", org);
            }
        }
        item.put("description", String.join("\n", desc));
        return item;
    }

    private static boolean empty(Object value) {
        return value == null || value.toString().isEmpty();
    }

    /** « Texas A&M University College Station, TX » -> (organisme, lieu) ; « ACME - Toulouse » aussi. */
    private static String[] orgLocation(String raw) {
        String text = raw.strip();
        Matcher m = ORG_DASH_PLACE.matcher(text);
        if (m.matches()) {
            return new String[] {m.group(1).strip(), m.group(2).strip()};
        }
        // « Organisme Ville, XX » : la ville fait 1 ou 2 mots ; on garde la découpe dont l'organisme
        // se termine par un mot typique (University, College, École, SAS…)
        Matcher tail = ORG_CITY_REGION.matcher(text);
        if (tail.matches()) {
            String[] words = tail.group(1).strip().split("\\s+");
            String region = tail.group(2);
            List<String[]> options = new ArrayList<>();
            for (int n : new int[] {2, 1}) {
                if (words.length > n) {
                    String org = String.join(" ", List.of(words).subList(0, words.length - n));
                    List<String> city = List.of(words).subList(words.length - n, words.length);
                    if (city.stream().allMatch(w -> !w.isEmpty() && Character.isUpperCase(w.charAt(0)))) {
                        options.add(new String[] {org, String.join(" ", city)});
                    }
                }
            }
            for (String[] option : options) {
                if (ORG_WORDS.matcher(option[0]).find()) {
                    return new String[] {option[0], option[1] + ", " + region};
                }
            }
            if (!options.isEmpty()) {
                String[] option = options.get(options.size() - 1);
                return new String[] {option[0], option[1] + ", " + region};
            }
        }
        return new String[] {text, ""};
    }

    private static String[] splitTitle(String text) {
        List<String> parts = new ArrayList<>();
        for (String p : SEP_RE.split(text, 2)) {
            String s = strip(p, " ,");
            if (!s.isEmpty()) {
                parts.add(s);
            }
        }
        if (parts.size() == 1 && text.contains(", ")) {
            int at = text.indexOf(", ");
            parts = new ArrayList<>(List.of(text.substring(0, at).strip(), text.substring(at + 2).strip()));
        }
        return new String[] {parts.isEmpty() ? "" : parts.get(0), parts.size() > 1 ? parts.get(1) : ""};
    }

    private static List<Object> certifications(List<String> lines) {
        List<Object> out = new ArrayList<>();
        Map<String, Object> last = null;
        for (String line : lines) {
            String plain = stripBullet(line);
            Range r = findRange(plain);
            if (r != null && r.rest().isEmpty() && last != null) {   // date seule sur sa ligne : appartient à la certif précédente
                last.put("date", r.start() == null ? "" : r.start());
                continue;
            }
            String[] split = splitTitle(r != null ? r.rest() : plain);
            last = new LinkedHashMap<>();
            last.put("name", split[0]);
            last.put("issuer", split[1]);
            last.put("date", r != null && r.start() != null ? r.start() : "");
            out.add(last);
        }
        return out;
    }

    // ---------- listes ----------

    private static List<String> items(List<String> lines) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : lines) {
            for (String piece : ITEM_SPLIT.split(stripBullet(line))) {
                String v = strip(piece, " .");
                if (!v.isEmpty() && seen.add(v.toLowerCase(Locale.ROOT))) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> skillGroups(List<String> lines) {
        List<Object> groups = new ArrayList<>();
        List<String> loose = new ArrayList<>();
        Map<String, Object> last = null;
        for (String line : lines) {
            String plain = stripBullet(line);
            Matcher m = SKILL_GROUP.matcher(plain);
            if (m.matches()) {
                last = new LinkedHashMap<>();
                last.put("category", m.group(1).strip());
                last.put("items", items(List.of(m.group(2))));
                groups.add(last);
            } else if (last != null && !plain.isEmpty() && Character.isLowerCase(plain.charAt(0))) {
                ((List<String>) last.get("items")).addAll(items(List.of(plain)));
            } else {
                loose.addAll(items(List.of(plain)));
            }
        }
        if (!loose.isEmpty()) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("category", "");
            group.put("items", loose);
            groups.add(group);
        }
        return groups;
    }

    private static Map<String, Object> language(String name, String level) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("name", name);
        l.put("level", level);
        return l;
    }

    private static List<Object> languages(List<String> lines) {
        List<Object> out = new ArrayList<>();
        for (String line : lines) {
            // « Français : natif Anglais : B2 » : coupe aussi devant chaque nom de langue suivi de « : » ou « ( »
            for (String piece : LANG_SPLIT.split(stripBullet(line))) {
                String part = strip(piece, " ,.");
                if (part.isEmpty()) {
                    continue;
                }
                Matcher m = LANG_COLON.matcher(part);
                if (!m.matches()) {
                    m = LANG_PAREN.matcher(part);
                }
                if (!m.matches()) {
                    m = LANG_DASH.matcher(part);
                }
                if (m.matches()) {
                    out.add(language(m.group(1).strip(), m.group(2).strip()));
                    continue;
                }
                String[] words = part.split("\\s+");
                if (words.length > 1 && LANG_LEVEL.matcher(part).find()) {
                    out.add(language(words[0], String.join(" ", List.of(words).subList(1, words.length))));
                } else {
                    for (String w : part.split(",")) {
                        if (!w.isBlank()) {
                            out.add(language(w.strip(), ""));
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---------- photo ----------

    /** Plus grande image (à peu près carrée ou portrait) de la première page, recadrée en carré, en JPEG. */
    private static String photo(PDDocument document) {
        try {
            if (document.getNumberOfPages() == 0) {
                return null;
            }
            List<BufferedImage> images = new ArrayList<>();
            collectImages(document.getPage(0).getResources(), images, 0);
            BufferedImage best = null;
            for (BufferedImage im : images) {
                int w = im.getWidth();
                int h = im.getHeight();
                double ratio = (double) w / h;
                if (w < 60 || h < 60 || ratio < 0.5 || ratio > 1.6) {
                    continue;
                }
                if (best == null || (long) w * h > (long) best.getWidth() * best.getHeight()) {
                    best = im;
                }
            }
            if (best == null) {
                return null;
            }
            int side = Math.min(best.getWidth(), best.getHeight());
            int left = (best.getWidth() - side) / 2;
            int top = Math.max(0, (int) ((best.getHeight() - side) / 2.6));
            int size = Math.min(500, side);
            BufferedImage square = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = square.createGraphics();
            g.setColor(Color.WHITE);   // fond blanc sous les images transparentes
            g.fillRect(0, 0, size, size);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(best, 0, 0, size, size, left, top, left + side, top + side, null);
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.88f);
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(square, null, null), param);
            } finally {
                writer.dispose();
            }
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void collectImages(PDResources resources, List<BufferedImage> out, int depth) {
        if (resources == null || depth > 3) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject object = resources.getXObject(name);
                if (object instanceof PDImageXObject image) {
                    out.add(image.getImage());
                } else if (object instanceof PDFormXObject form) {
                    collectImages(form.getResources(), out, depth + 1);
                }
            } catch (IOException | RuntimeException e) {
                // image illisible : on passe à la suivante
            }
        }
    }

    // ---------- chaînes ----------

    /** Comme str.strip(chars) en Python : retire aux deux bouts les caractères donnés. */
    private static String strip(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    private static String stripEnd(String s, String chars) {
        int end = s.length();
        while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }
}
