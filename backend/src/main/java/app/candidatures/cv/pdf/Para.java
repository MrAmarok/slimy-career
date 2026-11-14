package app.candidatures.cv.pdf;

import java.util.ArrayList;
import java.util.List;

import app.candidatures.cv.pdf.RichText.Piece;

/** Paragraphe de texte enrichi, coupé en lignes à la largeur disponible. */
public final class Para extends Flowable {

    /** Suite de caractères d'un même style sur une ligne. */
    private record Run(String text, String fontName, Rgb color, String href) {}

    /** `spaceAdjust` : correction (négative) appliquée à chaque espace d'une ligne légèrement trop longue. */
    private record Line(List<Run> runs, double width, double spaceAdjust) {}

    /** Une ligne peut dépasser un peu si resserrer ses espaces de 5 % suffit à la faire tenir. */
    private static final double SPACE_SHRINKAGE = 0.05;

    /** Mot = morceaux collés sans espace entre eux (éventuellement de styles différents). */
    private static final class Word {
        final List<Run> runs = new ArrayList<>();
        double width;
    }

    final ParaStyle style;
    private final List<Piece> pieces;
    private List<Line> lines;
    private double wrappedFor = -1;
    private boolean continuation;

    public Para(String markup, ParaStyle style) {
        this.style = style;
        this.pieces = RichText.parse(markup, style);
    }

    private Para(ParaStyle style, List<Line> lines, double width) {
        this.style = style;
        this.pieces = List.of();
        this.lines = lines;
        this.wrappedFor = width;
        this.width = width;
        this.height = lines.size() * style.leading;
        this.continuation = true;
    }

    @Override
    public double spaceBefore() {
        return continuation ? 0 : style.spaceBefore;
    }

    @Override
    public void wrap(Fonts fonts, double availWidth) {
        if (wrappedFor != availWidth) {
            lines = breakLines(fonts, availWidth - style.leftIndent - style.rightIndent);
            wrappedFor = availWidth;
        }
        width = availWidth;
        height = lines.size() * style.leading;
    }

    private List<Line> breakLines(Fonts fonts, double maxWidth) {
        // 1. mots (et retours à la ligne forcés, représentés par null)
        List<Word> words = new ArrayList<>();
        Word current = null;
        for (Piece piece : pieces) {
            if (piece.lineBreak()) {
                current = null;
                words.add(null);
                continue;
            }
            String text = piece.text();
            int start = -1;
            for (int i = 0; i <= text.length(); i++) {
                boolean space = i == text.length() || Character.isWhitespace(text.charAt(i));
                if (!space && start < 0) {
                    start = i;
                }
                if (space && start >= 0) {
                    if (current == null) {
                        current = new Word();
                        words.add(current);
                    }
                    String part = text.substring(start, i);
                    current.runs.add(new Run(part, piece.fontName(), piece.color(), piece.href()));
                    current.width += fonts.width(part, piece.fontName(), style.fontSize);
                    start = -1;
                }
                if (space && i < text.length()) {
                    current = null;   // une espace termine le mot ; sinon il continue dans le morceau suivant
                }
            }
        }
        // 2. lignes : on remplit tant que ça rentre
        List<Line> out = new ArrayList<>();
        List<Word> line = new ArrayList<>();
        double lineWidth = 0;
        double spaces = 0;   // largeur cumulée des espaces de la ligne
        for (Word word : words) {
            if (word == null) {
                out.add(toLine(fonts, line, maxWidth));
                line = new ArrayList<>();
                lineWidth = 0;
                spaces = 0;
                continue;
            }
            double space = line.isEmpty() ? 0 : fonts.width(" ", word.runs.get(0).fontName(), style.fontSize);
            if (!line.isEmpty() && lineWidth + space + word.width > maxWidth + SPACE_SHRINKAGE * (spaces + space) + 1e-8) {
                out.add(toLine(fonts, line, maxWidth));
                line = new ArrayList<>();
                lineWidth = 0;
                spaces = 0;
                space = 0;
            }
            line.add(word);
            lineWidth += space + word.width;
            spaces += space;
        }
        if (!line.isEmpty()) {
            out.add(toLine(fonts, line, maxWidth));
        }
        return out;
    }

    /** Regroupe les mots d'une ligne en suites de même style, espaces comprises (vraies espaces dans le PDF). */
    private Line toLine(Fonts fonts, List<Word> words, double maxWidth) {
        List<Run> runs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Run last = null;
        for (int w = 0; w < words.size(); w++) {
            if (w > 0) {
                text.append(' ');
            }
            for (Run run : words.get(w).runs) {
                if (last != null && !(last.fontName().equals(run.fontName()) && last.color().equals(run.color())
                        && java.util.Objects.equals(last.href(), run.href()))) {
                    runs.add(new Run(text.toString(), last.fontName(), last.color(), last.href()));
                    text.setLength(0);
                }
                text.append(run.text());
                last = run;
            }
        }
        if (last != null) {
            runs.add(new Run(text.toString(), last.fontName(), last.color(), last.href()));
        }
        double width = 0;
        for (Run run : runs) {
            width += fonts.width(run.text(), run.fontName(), style.fontSize);
        }
        double adjust = width > maxWidth && words.size() > 1 ? (maxWidth - width) / (words.size() - 1) : 0;
        return new Line(runs, Math.min(width, Math.max(maxWidth, 0)), adjust);
    }

    @Override
    public void draw(Canvas canvas, double x, double y) {
        double baseline = y + height - style.fontSize;   // première ligne de base : une taille de police sous le haut
        double inner = width - style.leftIndent - style.rightIndent;
        for (Line line : lines) {
            double cx = x + style.leftIndent + (style.centered ? Math.max(0, (inner - line.width()) / 2) : 0);
            for (Run run : line.runs()) {
                double w = canvas.fonts.width(run.text(), run.fontName(), style.fontSize)
                        + line.spaceAdjust() * run.text().chars().filter(c -> c == ' ').count();
                canvas.text(cx, baseline, run.text(), run.fontName(), style.fontSize, run.color(), line.spaceAdjust());
                if (run.href() != null && !run.href().isEmpty()) {
                    double visible = canvas.fonts.width(run.text().stripTrailing(), run.fontName(), style.fontSize);
                    canvas.link(cx, baseline - 0.22 * style.fontSize, visible, style.fontSize * 1.05, run.href());
                }
                cx += w;
            }
            baseline -= style.leading;
        }
    }

    /**
     * Coupe le paragraphe pour qu'une première partie tienne dans `availHeight`.
     * Retourne null si aucune ligne ne rentre ou si tout rentre déjà.
     */
    Para[] split(double availHeight) {
        int fit = (int) Math.floor((availHeight + 1e-6) / style.leading);
        if (fit <= 0 || fit >= lines.size()) {
            return null;
        }
        Para head = new Para(style, new ArrayList<>(lines.subList(0, fit)), width);
        head.continuation = continuation;
        Para tail = new Para(style, new ArrayList<>(lines.subList(fit, lines.size())), width);
        return new Para[] {head, tail};
    }
}
