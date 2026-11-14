package app.candidatures.cv.pdf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mini-balisage des paragraphes : {@code <b>}, {@code <i>}, {@code <font color="#rrggbb">}, {@code <a href="…">},
 * {@code <br/>} ; le texte lui-même est échappé (&amp;amp; &amp;lt; &amp;gt;).
 */
final class RichText {

    /** Morceau de texte d'un seul style ; `lineBreak` marque un retour à la ligne forcé. */
    record Piece(String text, String fontName, Rgb color, String href, boolean lineBreak) {}

    private static final Pattern TAG = Pattern.compile("<(/?)(b|i|font|a|br)\\b([^>]*?)/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("(color|href)\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private RichText() {}

    static List<Piece> parse(String markup, ParaStyle style) {
        List<Piece> out = new ArrayList<>();
        int bold = 0;
        int italic = 0;
        Deque<Rgb> colors = new ArrayDeque<>();
        Deque<String> links = new ArrayDeque<>();
        Matcher m = TAG.matcher(markup);
        int pos = 0;
        while (true) {
            boolean found = m.find();
            String text = unescape(markup.substring(pos, found ? m.start() : markup.length()));
            if (!text.isEmpty()) {
                out.add(new Piece(text, Fonts.variant(style.fontName, bold > 0, italic > 0),
                        colors.isEmpty() ? style.textColor : colors.peek(), links.peek(), false));
            }
            if (!found) {
                break;
            }
            pos = m.end();
            boolean closing = !m.group(1).isEmpty();
            switch (m.group(2).toLowerCase()) {
                case "b" -> bold = Math.max(0, bold + (closing ? -1 : 1));
                case "i" -> italic = Math.max(0, italic + (closing ? -1 : 1));
                case "br" -> out.add(new Piece("", style.fontName, style.textColor, null, true));
                case "font" -> {
                    if (closing) {
                        colors.poll();
                    } else {
                        colors.push(attribute(m.group(3), "color", style.textColor));
                    }
                }
                case "a" -> {
                    if (closing) {
                        links.poll();
                    } else {
                        Matcher a = ATTR.matcher(m.group(3));
                        String href = "";
                        while (a.find()) {
                            if (a.group(1).equalsIgnoreCase("href")) {
                                href = unescape(a.group(2));
                            }
                        }
                        links.push(href);
                    }
                }
                default -> { }
            }
        }
        return out;
    }

    private static Rgb attribute(String attributes, String name, Rgb fallback) {
        Matcher a = ATTR.matcher(attributes);
        while (a.find()) {
            if (a.group(1).equalsIgnoreCase(name)) {
                try {
                    return Rgb.hex(a.group(2));
                } catch (IllegalArgumentException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }
}
