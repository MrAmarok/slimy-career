package app.candidatures.cv.pdf;

import java.text.Normalizer;
import java.util.Map;

import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;

/** Nettoyage du texte avant écriture dans le PDF. */
public final class Clean {

    private static final Map<Integer, String> REPLACE = Map.ofEntries(
            Map.entry((int) '→', "->"), Map.entry((int) '←', "<-"), Map.entry((int) '⇒', "=>"), Map.entry((int) '↔', "<->"),
            Map.entry((int) '≥', ">="), Map.entry((int) '≤', "<="), Map.entry((int) '≠', "!="), Map.entry((int) '✓', ""),
            Map.entry((int) '✔', ""), Map.entry((int) '★', "*"), Map.entry((int) '☆', "*"), Map.entry((int) '▪', "-"),
            Map.entry((int) '■', "-"), Map.entry((int) '●', "-"), Map.entry((int) '◦', "-"), Map.entry(0x00a0, " "),
            Map.entry(0x202f, " "));

    private Clean() {}

    /** Vrai si le caractère existe dans l'encodage des polices standard (WinAnsi, proche de Windows-1252). */
    public static boolean encodable(int codePoint) {
        if (codePoint < 0x20 || (codePoint >= 0x7f && codePoint < 0xa0)) {
            return false;
        }
        String name = GlyphList.getAdobeGlyphList().codePointToName(codePoint);
        return !".notdef".equals(name) && WinAnsiEncoding.INSTANCE.contains(name);
    }

    public static String encodableOnly(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().filter(Clean::encodable).forEach(out::appendCodePoint);
        return out.toString();
    }

    /**
     * Texte écrivable avec la police standard : les caractères exotiques sont remplacés par leur équivalent
     * simple ou retirés, pour éviter les carrés noirs à l'export. Les retours à la ligne sont conservés.
     */
    public static String clean(Object value) {
        String text = value == null ? "" : value.toString().replace("\t", " ").replace("\r", "");
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            String replacement = REPLACE.get(cp);
            if (replacement != null) {
                out.append(replacement);
            } else if (cp == '\n' || encodable(cp)) {
                out.appendCodePoint(cp);
            } else {
                Normalizer.normalize(Character.toString(cp), Normalizer.Form.NFKD).codePoints()
                        .filter(c -> c >= 0x20 && c < 0x7f).forEach(out::appendCodePoint);
            }
        });
        return out.toString().strip();
    }

    /** Échappe le texte pour le mini-balisage des paragraphes (voir RichText). */
    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
