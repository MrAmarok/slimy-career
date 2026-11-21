package app.candidatures.cv.pdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import app.candidatures.common.Values;
import app.candidatures.cv.CvModel;
import app.candidatures.cv.pdf.Blocks.Bullet;
import app.candidatures.cv.pdf.Blocks.LeftRight;
import app.candidatures.cv.pdf.Blocks.SectionHeading;
import app.candidatures.cv.pdf.DocFlow.Keep;
import app.candidatures.cv.pdf.Flowable.Rule;
import app.candidatures.cv.pdf.Flowable.Spacer;

/**
 * Génère le PDF d'un CV, pensé pour être bien lu par les logiciels RH (ATS) :
 * - une seule colonne, ordre de lecture = ordre visuel, aucun tableau ni zone de texte flottante ;
 * - vrai texte sélectionnable en police standard, aucune information importante dans une image ;
 * - titres de sections classiques (« Expérience professionnelle », « Formation », « Compétences »…) ;
 * - dates au format MM/AAAA, liens écrits en clair, métadonnées PDF (titre, auteur, mots-clés).
 *
 * Le CV tient sur une page par défaut : si le contenu déborde, la mise en page est resserrée petit à petit
 * (marges et espacements d'abord, puis taille du texte) jusqu'à ce qu'il rentre. Une deuxième page n'est
 * utilisée que si le CV l'autorise (`max_pages` = 2).
 */
public final class CvPdf {

    /** PDF généré et ce qu'il a fallu faire pour le faire tenir. */
    public record Result(byte[] pdf, String filename, int pages, int maxPages, double compact, boolean overflow) {}

    private record Rendered(byte[] pdf, int pages, String filename) {}

    /** Facteurs de mise en page pour un niveau de compaction. */
    private record Fit(double font, double lead, double space, double margin) {}

    private static final double MM = 72 / 25.4;
    private static final double PHOTO_SIZE = 30 * MM;
    private static final double MARGIN_X = 16 * MM;
    private static final double MARGIN_Y = 14 * MM;
    /** Nombre de crans de compaction essayés entre la mise en page normale et la plus serrée. */
    private static final int FIT_STEPS = 16;

    private static final Map<String, Map<String, String>> HEADINGS = Map.of(
            "fr", Map.ofEntries(Map.entry("summary", "Profil"), Map.entry("experience", "Expérience professionnelle"),
                    Map.entry("projects", "Projets"), Map.entry("education", "Formation"),
                    Map.entry("hard_skills", "Compétences techniques"), Map.entry("soft_skills", "Compétences comportementales"),
                    Map.entry("languages", "Langues"), Map.entry("certifications", "Certifications"),
                    Map.entry("interests", "Centres d'intérêt"), Map.entry("present", "Aujourd'hui"),
                    Map.entry("tech", "Technologies"), Map.entry("cv", "CV")),
            "en", Map.ofEntries(Map.entry("summary", "Summary"), Map.entry("experience", "Professional Experience"),
                    Map.entry("projects", "Projects"), Map.entry("education", "Education"),
                    Map.entry("hard_skills", "Technical Skills"), Map.entry("soft_skills", "Soft Skills"),
                    Map.entry("languages", "Languages"), Map.entry("certifications", "Certifications"),
                    Map.entry("interests", "Interests"), Map.entry("present", "Present"),
                    Map.entry("tech", "Technologies"), Map.entry("cv", "Resume")));

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})(?:-\\d{2})?");
    private static final Pattern BULLET_PREFIX = Pattern.compile("^\\s*(?:[-•–·]|\\*(?!\\*))\\s*");
    private static final Pattern HAS_SCHEME = Pattern.compile("^[a-z]+:.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SAFE_SCHEME = Pattern.compile("^(https?|mailto):", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_PREFIX = Pattern.compile("^https?://(www\\.)?");
    private static final Pattern PHOTO = Pattern.compile("^data:image/(png|jpe?g|webp);base64,(.+)$", Pattern.DOTALL);

    private CvPdf() {}

    /**
     * Facteurs de mise en page pour une compaction t, de 0 (normale) à 1 (la plus serrée encore lisible).
     * On rogne d'abord ce qui ne se voit presque pas (marges, espaces, interligne), puis la taille du texte
     * (jusqu'à -20 %, soit environ 7,7 pt : en dessous le CV devient pénible à lire).
     */
    private static Fit fit(double t) {
        double early = Math.min(1, t / 0.4);
        double late = Math.max(0, (t - 0.25) / 0.75);
        return new Fit(1 - 0.2 * late, 1 - 0.08 * early, 1 - 0.6 * early, 1 - 0.4 * early);
    }

    // ---------- point d'entrée ----------

    /** Le CV est resserré juste ce qu'il faut pour tenir dans `max_pages` (1 par défaut, 2 si autorisé). */
    public static Result build(Map<String, Object> data) {
        Map<String, Object> cv = CvModel.withDefaults(data);
        int maxPages = "2".equals(String.valueOf(cv.get("max_pages"))) ? 2 : 1;
        PDImageXObjectSource photo = Values.truthy(cv.get("show_photo")) ? photo(cv) : null;
        int level = 0;
        Rendered best = render(cv, photo, 0);
        if (best.pages() > maxPages) {
            level = FIT_STEPS;
            best = render(cv, photo, 1);
            if (best.pages() <= maxPages) {   // ça rentre au plus serré : chercher la compaction la plus légère qui suffit
                int low = 0;
                while (level - low > 1) {
                    int mid = (low + level) / 2;
                    Rendered attempt = render(cv, photo, (double) mid / FIT_STEPS);
                    if (attempt.pages() <= maxPages) {
                        level = mid;
                        best = attempt;
                    } else {
                        low = mid;
                    }
                }
            }
        }
        return new Result(best.pdf(), best.filename(), best.pages(), maxPages, (double) level / FIT_STEPS, best.pages() > maxPages);
    }

    /** Texte tel qu'un logiciel RH le lit dans le PDF (pour vérifier le rendu). */
    public static String extractText(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pages = new ArrayList<>();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(stripper.getText(doc).strip());
            }
            return String.join("\n\n", pages);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- texte ----------

    static String clean(Object value) {
        return Clean.clean(value);
    }

    /** Échappe le texte et transforme **gras** en gras. */
    static String markup(Object value) {
        return BOLD.matcher(Clean.escape(clean(value))).replaceAll("<b>$1</b>");
    }

    static String fmtDate(Object value) {
        String v = clean(value);
        Matcher m = ISO_DATE.matcher(v);
        return m.matches() ? m.group(2) + "/" + m.group(1) : v;
    }

    private static String dateRange(Map<String, Object> item, Map<String, String> words) {
        String start = fmtDate(item.get("start"));
        String end = Values.truthy(item.get("current")) ? words.get("present") : fmtDate(item.get("end"));
        if (!start.isEmpty() && !end.isEmpty()) {
            return start.equals(end) ? start : start + " – " + end;
        }
        return start.isEmpty() ? end : start;
    }

    static List<String> bulletLines(Object text) {
        List<String> lines = new ArrayList<>();
        for (String line : clean(text).split("\n")) {
            line = BULLET_PREFIX.matcher(line).replaceFirst("").strip();   // « **gras** » en début de ligne n'est pas une puce
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    static List<String> asList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object v : list) {
                String s = clean(v);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } else {
            for (String v : clean(value).split(",")) {
                if (!v.isBlank()) {
                    out.add(v.strip());
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> items(Map<String, Object> cv, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (cv.get(key) instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = Values.map(o);
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    // ---------- styles ----------

    /** Styles de paragraphe et options d'un modèle (« moderne », « tech », « classique ») pour une couleur donnée. */
    private static final class Theme {
        ParaStyle name, title, contact, body, heading, entry, date, sub, bullet, skill;
        boolean modern;
        String template;
        String orgColor;
        boolean orgItalic;
        boolean skillBullets = true;
        boolean centerHeader;
        String sep = "|";
        Rgb rule, dateBg, line, band;
        double space;
    }

    private static Theme theme(Rgb accent, String template, Fit fit) {
        boolean modern = template.equals("moderne");
        Rgb text = Rgb.hex("#1f2328");
        Rgb muted = Rgb.hex("#555b63");
        Rgb band = accent;
        if (accent.isLight()) {   // couleur trop claire pour du texte sur fond blanc : version assombrie
            accent = Rgb.mix(accent, text, 0.6);
        }
        ParaStyle base = new ParaStyle().font("Helvetica").size(9.6, 12.6).color(text);
        Theme st = new Theme();
        st.name = base.copy().font("Helvetica-Bold").size(21, 25);
        st.title = base.copy().font("Helvetica-Bold").size(12, 16).color(accent);
        st.contact = base.copy().size(9, 12.5).color(muted);
        st.body = base.copy();
        st.heading = base.copy().font("Helvetica-Bold").size(11, 14).color(accent).before(9);
        st.entry = base.copy().size(10, 13);
        st.date = base.copy().size(9).color(muted);
        st.sub = base.copy().font("Helvetica-Oblique").size(9, 12).color(muted);
        st.bullet = base.copy().before(1.2);
        st.bullet.leftIndent = 11;
        st.bullet.bulletIndent = 2;
        st.modern = modern;
        st.template = template;
        if (template.equals("tech")) {
            // inspiré des CV tech « une page » (type Jake's Resume) : police à empattements, en-tête centré,
            // titres en capitales soulignés d'un filet, entreprise en italique, compétences en lignes « Catégorie : … »
            Rgb ink = Rgb.hex("#111111");
            st.name = st.name.copy().font("Times-Bold").size(25, 29).color(ink);
            st.title = st.title.copy().font("Times-Italic").size(12, 15).color(accent);
            st.contact = st.contact.copy().font("Times-Roman").color(ink).size(10, 13);
            st.body = st.body.copy().font("Times-Roman").color(ink).size(10.5, 13);
            st.heading = st.heading.copy().font("Times-Roman").size(12.5, 15).color(accent).before(8);
            st.entry = st.entry.copy().font("Times-Roman").color(ink).size(11, 13.5);
            st.date = st.date.copy().font("Times-Roman").color(ink).size(10.5);
            st.sub = st.sub.copy().font("Times-Italic").size(10, 12.5).color(ink);
            st.bullet = st.bullet.copy().font("Times-Roman").color(ink).size(10.5, 12.8).before(0.6);
            st.bullet.leftIndent = 14;
            st.bullet.bulletIndent = 5;
            st.bullet.dotColor = ink;
            st.skill = st.body.copy().before(1);
            st.orgItalic = true;
            st.skillBullets = false;
            st.centerHeader = true;
            st.rule = ink;
        }
        if (modern) {
            // en-tête sur bandeau de couleur : texte clair (ou foncé si la couleur choisie est claire)
            Rgb onBand = band.isLight() ? text : Rgb.WHITE;
            st.name = st.name.copy().size(24, 28).color(onBand);
            st.title = st.title.copy().color(Rgb.mix(onBand, band, 0.18)).before(1);
            st.contact = st.contact.copy().color(Rgb.mix(onBand, band, 0.1));
            st.contact.leading = 13;
            st.sep = "<font color=\"" + Rgb.mix(onBand, band, 0.5).toHex() + "\">|</font>";
            st.heading = st.heading.copy().size(11.5).before(11);
            Rgb darkAccent = Rgb.mix(accent, text, 0.25);
            st.orgColor = darkAccent.toHex();
            st.date = st.date.copy().font("Helvetica-Bold").size(8.3).color(darkAccent);
            st.dateBg = Rgb.mix(accent, Rgb.WHITE, 0.88);
            st.line = Rgb.mix(accent, Rgb.WHITE, 0.7);
            st.bullet.dotColor = accent;
            st.band = band;
        }
        for (ParaStyle s : new ParaStyle[] {st.name, st.title, st.contact, st.body, st.heading, st.entry, st.date, st.sub, st.bullet, st.skill}) {
            if (s != null) {
                s.fontSize *= fit.font();
                s.leading *= fit.font() * fit.lead();
                s.spaceBefore *= fit.space();
                s.leftIndent *= fit.font();
                s.bulletIndent *= fit.font();
            }
        }
        st.space = fit.space();
        return st;
    }

    // ---------- mise en page ----------

    /** Met le CV en page avec la compaction t (voir fit). */
    private static Rendered render(Map<String, Object> cv, PDImageXObjectSource photoSource, double t) {
        Fit fit = fit(t);
        double marginX = MARGIN_X * fit.margin();
        double marginY = MARGIN_Y * fit.margin();
        double photoSize = PHOTO_SIZE * fit.font();
        Map<String, Object> info = CvModel.emptyInfo();
        Map<String, Object> given = Values.map(cv.get("info"));
        if (given != null) {
            info.putAll(given);
        }
        Map<String, String> words = HEADINGS.getOrDefault(String.valueOf(cv.get("lang")), HEADINGS.get("fr"));
        Rgb accent;
        try {
            accent = Rgb.hex(Values.truthy(cv.get("accent")) ? Values.str(cv.get("accent")) : CvModel.DEFAULT_ACCENT);
        } catch (IllegalArgumentException e) {
            accent = Rgb.hex(CvModel.DEFAULT_ACCENT);
        }
        String template = CvModel.STYLES.contains(cv.get("style")) ? (String) cv.get("style") : "moderne";
        boolean modern = template.equals("moderne");
        Theme st = theme(accent, template, fit);

        String fullName = List.of(clean(info.get("first_name")), clean(info.get("last_name"))).stream()
                .filter(s -> !s.isEmpty()).collect(Collectors.joining(" "));
        if (fullName.isEmpty()) {
            fullName = "CV";
        }
        boolean hasPhoto = photoSource != null;
        double pageWidth = DocFlow.PAGE_WIDTH - 2 * marginX;
        double rightPad = hasPhoto ? photoSize + 6 * MM : 0;
        if (st.centerHeader && !hasPhoto) {
            st.name = st.name.copy();
            st.title = st.title.copy();
            st.contact = st.contact.copy();
            st.name.centered = st.title.centered = st.contact.centered = true;
        }

        try (PDDocument document = new PDDocument()) {
            Fonts fonts = new Fonts();
            Canvas canvas = new Canvas(document, fonts);
            List<Object> story = new ArrayList<>();

            // ---- en-tête : nom, poste visé, coordonnées (dans le corps du document, pas en en-tête de page) ----
            List<Para> head = new ArrayList<>();
            ParaStyle nameStyle = st.name.copy();
            nameStyle.rightIndent = rightPad;
            head.add(new Para(markup(fullName), nameStyle));
            if (Values.truthy(info.get("title"))) {
                ParaStyle titleStyle = st.title.copy();
                titleStyle.rightIndent = rightPad;
                head.add(new Para(markup(info.get("title")), titleStyle));
            }
            String sep = " " + st.sep + " ";
            List<String> lines = new ArrayList<>(List.of(
                    joined(sep, List.of(info.get("email"), info.get("phone"), info.get("city")), false),
                    joined(sep, List.of(info.get("linkedin"), info.get("github"), info.get("website")), true),
                    joined(sep, List.of(info.get("availability"), info.get("extra")), false)));
            if (st.centerHeader) {   // coordonnées + liens sur une ligne (comme les CV tech), disponibilité en dessous
                String first = lines.subList(0, 2).stream().filter(l -> !l.isEmpty()).collect(Collectors.joining(sep));
                lines = List.of(first, lines.get(2));
            }
            ParaStyle contactStyle = st.contact.copy();
            contactStyle.rightIndent = rightPad;
            for (String line : lines) {
                if (!line.isEmpty()) {
                    head.add(new Para(line, contactStyle));
                }
            }
            story.addAll(head);
            double headHeight = 0;
            for (Para p : head) {
                p.wrap(fonts, pageWidth);
                headHeight += p.height() + p.spaceBefore();
            }
            double contentHeight = Math.max(headHeight, hasPhoto ? photoSize : 0);
            double bandPad = 7 * MM * fit.margin();
            if (contentHeight > headHeight) {
                story.add(new Spacer(contentHeight - headHeight));
            }
            if (modern) {
                story.add(new Spacer(bandPad + 1 * MM * fit.space()));   // sortir du bandeau
            } else if (template.equals("tech")) {
                story.add(new Spacer(1 * MM * fit.space()));
            }

            if (!clean(info.get("summary")).isEmpty()) {
                List<Flowable> summary = List.of(new Para(markup(info.get("summary")).replace("\n", "<br/>"), st.body));
                story.addAll(section(words.get("summary"), List.of(summary), st));
            }

            List<String> order = new ArrayList<>();
            if (cv.get("section_order") instanceof List<?> wanted) {
                for (Object s : wanted) {
                    if (s instanceof String key && CvModel.SECTIONS.contains(key) && !order.contains(key)) {
                        order.add(key);
                    }
                }
            }
            for (String key : CvModel.SECTIONS) {
                if (!order.contains(key)) {
                    order.add(key);
                }
            }
            for (String key : order) {
                List<List<Flowable>> entries = entries(key, cv, words, st);
                if (!entries.isEmpty()) {
                    story.addAll(section(words.get(key), entries, st));
                }
            }

            List<String> keywords = new ArrayList<>();
            for (Map<String, Object> group : items(cv, "hard_skills")) {
                keywords.addAll(asList(group.get("items")));
            }
            PDDocumentInformation meta = document.getDocumentInformation();
            String jobTitle = clean(info.get("title"));
            meta.setTitle(fullName + " - " + (jobTitle.isEmpty() ? words.get("cv") : jobTitle));
            meta.setAuthor(fullName);
            meta.setSubject(jobTitle);
            meta.setKeywords(String.join(", ", keywords));
            meta.setCreator("Candidatures");

            PDImageXObject photo = hasPhoto ? photoSource.embed(document) : null;
            Rgb bandColor = st.band;
            DocFlow flow = new DocFlow(canvas, marginX, marginY, c -> {
                if (modern) {   // bandeau de couleur derrière l'en-tête (forme dessinée : invisible pour les ATS)
                    c.rect(0, DocFlow.PAGE_HEIGHT - marginY - contentHeight - bandPad, DocFlow.PAGE_WIDTH,
                            marginY + contentHeight + bandPad + 1, bandColor);
                }
                if (photo != null) {
                    double x = DocFlow.PAGE_WIDTH - marginX - photoSize;
                    double y = DocFlow.PAGE_HEIGHT - marginY - contentHeight / 2 - photoSize / 2;
                    if (modern) {
                        c.circle(x + photoSize / 2, y + photoSize / 2, photoSize / 2 + 1.8, Rgb.WHITE);
                    }
                    c.imageInCircle(photo, x, y, photoSize);
                }
            });
            flow.build(story);
            canvas.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            String ascii = Normalizer.normalize("CV_" + fullName, Normalizer.Form.NFKD).replaceAll("[^\\x00-\\x7f]", "");
            String filename = ascii.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
            return new Rendered(out.toByteArray(), flow.pages(), (filename.isEmpty() ? "CV" : filename) + ".pdf");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String joined(String sep, List<Object> values, boolean links) {
        List<String> parts = new ArrayList<>();
        for (Object v : values) {
            if (!clean(v).isEmpty()) {
                parts.add(links ? link(v) : markup(v));
            }
        }
        return String.join(sep, parts);
    }

    private static String link(Object value) {
        String url = clean(value);
        if (HAS_SCHEME.matcher(url).matches() && !SAFE_SCHEME.matcher(url).lookingAt()) {
            return Clean.escape(url);   // « javascript: », « file: »… : écrit en clair, sans lien cliquable
        }
        String href = HAS_SCHEME.matcher(url).matches() ? url : "https://" + url;
        // l'adresse est écrite en entier : un logiciel qui ne lit que le texte la récupère quand même
        String shown = HTTP_PREFIX.matcher(url).replaceFirst("");
        return "<a href=\"" + Clean.escape(href).replace("\"", "&quot;") + "\">" + Clean.escape(shown) + "</a>";
    }

    /** Titre + trait + entrées ; le titre reste toujours collé à la première entrée. */
    private static List<Object> section(String title, List<List<Flowable>> entries, Theme st) {
        double sp = st.space;
        List<Flowable> first = new ArrayList<>();
        if (st.modern) {
            first.add(new SectionHeading(clean(title).toUpperCase(Locale.ROOT), st.heading, st.line, sp));
            first.add(new Spacer(3 * sp));
        } else if (st.template.equals("tech")) {
            first.add(new Para(Clean.escape(clean(title).toUpperCase(Locale.ROOT)), st.heading));
            first.add(new Rule(0.6, st.rule, 0, 3 * sp));
        } else {
            first.add(new Para(Clean.escape(title.toUpperCase(Locale.ROOT)), st.heading));
            first.add(new Rule(0.7, st.heading.textColor, 1, 4 * sp));
        }
        first.addAll(entries.get(0));
        List<Object> out = new ArrayList<>();
        out.add(new Keep(first));
        for (List<Flowable> entry : entries.subList(1, entries.size())) {
            out.add(new Keep(entry));
        }
        return out;
    }

    private static List<Flowable> entry(Theme st, Object title, Object org, String dates, String sub, List<String> bullets) {
        String left = clean(title).isEmpty() ? "" : "<b>" + markup(title) + "</b>";
        if (!clean(org).isEmpty()) {
            String orgText = st.orgColor != null ? "<font color=\"" + st.orgColor + "\">" + markup(org) + "</font>" : markup(org);
            if (st.orgItalic) {
                orgText = "<i>" + orgText + "</i>";
            }
            left += (left.isEmpty() ? "" : " — ") + orgText;
        }
        List<Flowable> out = new ArrayList<>();
        if (!left.isEmpty() || !dates.isEmpty()) {
            out.add(new LeftRight(new Para(left, st.entry), dates, st.date, st.dateBg));
        }
        if (!sub.isEmpty()) {
            out.add(new Para(sub, st.sub));
        }
        for (String b : bullets) {
            out.add(new Bullet(new Para(markup(b), st.bullet)));
        }
        if (!out.isEmpty()) {
            out.add(new Spacer((st.template.equals("tech") ? 4 : 5) * st.space));
        }
        return out;
    }

    private static List<List<Flowable>> entries(String key, Map<String, Object> cv, Map<String, String> words, Theme st) {
        List<List<Flowable>> out = new ArrayList<>();
        switch (key) {
            case "experience" -> {
                for (Map<String, Object> e : items(cv, key)) {
                    out.add(entry(st, e.get("title"), e.get("company"), dateRange(e, words), markup(e.get("location")),
                            bulletLines(e.get("description"))));
                }
            }
            case "projects" -> {
                for (Map<String, Object> p : items(cv, key)) {
                    List<String> parts = new ArrayList<>();
                    List<String> tech = asList(p.get("tech"));
                    if (!tech.isEmpty()) {
                        parts.add(words.get("tech") + " : " + markup(String.join(", ", tech)));
                    }
                    if (!clean(p.get("link")).isEmpty()) {
                        parts.add(link(p.get("link")));
                    }
                    out.add(entry(st, p.get("name"), p.get("context"), dateRange(p, words), String.join(" | ", parts),
                            bulletLines(p.get("description"))));
                }
            }
            case "education" -> {
                for (Map<String, Object> e : items(cv, key)) {
                    out.add(entry(st, e.get("degree"), e.get("school"), dateRange(e, words), markup(e.get("location")),
                            bulletLines(e.get("description"))));
                }
            }
            case "certifications" -> {
                for (Map<String, Object> c : items(cv, key)) {
                    out.add(entry(st, c.get("name"), c.get("issuer"), fmtDate(c.get("date")), "", List.of()));
                }
            }
            case "hard_skills" -> {
                List<Flowable> rows = new ArrayList<>();
                for (Map<String, Object> group : items(cv, key)) {
                    List<String> skills = asList(group.get("items"));
                    if (skills.isEmpty()) {
                        continue;
                    }
                    String category = clean(group.get("category"));
                    String label = st.orgColor != null ? "<font color=\"" + st.orgColor + "\">" + Clean.escape(category) + "</font>"
                            : Clean.escape(category);
                    String text = (category.isEmpty() ? "" : "<b>" + label + " :</b> ") + markup(String.join(", ", skills));
                    rows.add(st.skillBullets ? new Bullet(new Para(text, st.bullet)) : new Para(text, st.skill));
                }
                if (!rows.isEmpty()) {
                    rows.add(new Spacer(3 * st.space));
                    out.add(rows);
                }
            }
            case "languages" -> {
                List<String> langs = new ArrayList<>();
                for (Map<String, Object> l : items(cv, key)) {
                    if (!clean(l.get("name")).isEmpty()) {
                        langs.add("<b>" + markup(l.get("name")) + "</b>"
                                + (clean(l.get("level")).isEmpty() ? "" : " : " + markup(l.get("level"))));
                    }
                }
                if (!langs.isEmpty()) {
                    out.add(List.of(new Para(String.join(" " + st.sep + " ", langs), st.body), new Spacer(3 * st.space)));
                }
            }
            case "soft_skills", "interests" -> {
                List<String> values = asList(cv.get(key) == null ? List.of() : cv.get(key));
                if (!values.isEmpty()) {
                    out.add(List.of(new Para(markup(String.join(", ", values)), st.body), new Spacer(3 * st.space)));
                }
            }
            default -> { }
        }
        out.removeIf(List::isEmpty);
        return out;
    }

    // ---------- photo ----------

    /** Photo décodée une seule fois, puis insérée dans chaque document essayé. */
    private interface PDImageXObjectSource {
        PDImageXObject embed(PDDocument document) throws IOException;
    }

    private static PDImageXObjectSource photo(Map<String, Object> cv) {
        Matcher m = PHOTO.matcher(Values.str(cv.get("photo")));
        if (!m.matches()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(m.group(2));
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
                return null;
            }
            boolean jpeg = m.group(1).toLowerCase(Locale.ROOT).startsWith("jp") && image.getColorModel().getNumComponents() == 3;
            // un JPEG est repris tel quel (pas de recompression) ; PNG et WebP sont réencodés sans perte
            return jpeg ? doc -> JPEGFactory.createFromByteArray(doc, bytes) : doc -> LosslessFactory.createFromImage(doc, image);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
