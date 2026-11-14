package app.candidatures.cv.pdf;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

/**
 * Polices standard du PDF (Helvetica et Times, encodage WinAnsi) : présentes dans tous les lecteurs, donc rien
 * n'est embarqué et le texte reste du vrai texte. Un jeu de polices par document généré.
 */
public final class Fonts {

    private static final Map<String, FontName> NAMES = Map.of(
            "Helvetica", FontName.HELVETICA, "Helvetica-Bold", FontName.HELVETICA_BOLD,
            "Helvetica-Oblique", FontName.HELVETICA_OBLIQUE, "Helvetica-BoldOblique", FontName.HELVETICA_BOLD_OBLIQUE,
            "Times-Roman", FontName.TIMES_ROMAN, "Times-Bold", FontName.TIMES_BOLD,
            "Times-Italic", FontName.TIMES_ITALIC, "Times-BoldItalic", FontName.TIMES_BOLD_ITALIC);

    private final Map<String, PDType1Font> fonts = new HashMap<>();

    public PDType1Font get(String name) {
        return fonts.computeIfAbsent(name, n -> new PDType1Font(NAMES.getOrDefault(n, FontName.HELVETICA)));
    }

    /** Variante grasse / italique de la police d'un style. */
    public static String variant(String base, boolean bold, boolean italic) {
        boolean times = base.startsWith("Times");
        bold |= base.contains("Bold");
        italic |= base.contains("Italic") || base.contains("Oblique");
        if (times) {
            return bold && italic ? "Times-BoldItalic" : bold ? "Times-Bold" : italic ? "Times-Italic" : "Times-Roman";
        }
        return bold && italic ? "Helvetica-BoldOblique" : bold ? "Helvetica-Bold" : italic ? "Helvetica-Oblique" : "Helvetica";
    }

    public double width(String text, String fontName, double size) {
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return get(fontName).getStringWidth(text) / 1000.0 * size;
        } catch (IOException | IllegalArgumentException e) {
            try {
                return get(fontName).getStringWidth(Clean.encodableOnly(text)) / 1000.0 * size;
            } catch (IOException | IllegalArgumentException again) {
                return 0;
            }
        }
    }
}
