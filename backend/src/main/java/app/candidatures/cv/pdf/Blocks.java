package app.candidatures.cv.pdf;

/** Éléments propres à la mise en page du CV. */
public final class Blocks {

    private Blocks() {}

    /**
     * Une ligne « texte à gauche … dates à droite » sans tableau : le texte de gauche est écrit avant les dates,
     * donc un logiciel lit « Poste — Entreprise 01/2024 – 06/2024 » dans le bon ordre. En style moderne, la date
     * est posée sur une pastille colorée (simple forme dessinée).
     */
    public static final class LeftRight extends Flowable {

        private static final double PAD = 4;

        private final Para left;
        private final String right;
        private final ParaStyle rightStyle;
        private final Rgb tagBackground;
        private double rightWidth;

        public LeftRight(Para left, String right, ParaStyle rightStyle, Rgb tagBackground) {
            this.left = left;
            this.right = Clean.clean(right);
            this.rightStyle = rightStyle;
            this.tagBackground = tagBackground;
        }

        @Override
        public void wrap(Fonts fonts, double availWidth) {
            rightWidth = right.isEmpty() ? 0 : fonts.width(right, rightStyle.fontName, rightStyle.fontSize);
            if (!right.isEmpty() && tagBackground != null) {
                rightWidth += 2 * PAD;
            }
            width = availWidth;
            left.wrap(fonts, availWidth - rightWidth - (right.isEmpty() ? 0 : 8));
            height = Math.max(left.height(), left.style.leading);   // une date seule (sans intitulé) garde une ligne de haut
        }

        @Override
        public void draw(Canvas c, double x, double y) {
            left.draw(c, x, y + height - left.height());
            if (right.isEmpty()) {
                return;
            }
            double fs = rightStyle.fontSize;
            double base = y + height - left.style.fontSize;
            double xr = x + width;
            if (tagBackground != null) {
                c.roundRect(x + width - rightWidth, base - 2.6, rightWidth, fs + 4.2, 3, tagBackground);
                xr -= PAD;
            }
            c.textRight(xr, base, right, rightStyle.fontName, fs, rightStyle.textColor);
        }
    }

    /** Titre de section du style moderne : barre de couleur, texte, puis filet fin jusqu'à la marge. */
    public static final class SectionHeading extends Flowable {

        private final String text;
        private final ParaStyle style;
        private final Rgb lineColor;
        private final double space;
        private double textWidth;

        public SectionHeading(String text, ParaStyle style, Rgb lineColor, double space) {
            this.text = text;
            this.style = style;
            this.lineColor = lineColor;
            this.space = space;
        }

        @Override
        public void wrap(Fonts fonts, double availWidth) {
            width = availWidth;
            height = style.fontSize + 9 * space;
            textWidth = fonts.width(text, style.fontName, style.fontSize);
        }

        @Override
        public double spaceBefore() {
            return style.spaceBefore;
        }

        @Override
        public void draw(Canvas c, double x, double y) {
            double base = y + 6 * space;
            c.roundRect(x, base - 1.5, 3.2, style.fontSize + 2, 1.4, style.textColor);
            c.text(x + 9, base, text, style.fontName, style.fontSize, style.textColor);
            double end = x + 9 + textWidth + 8;
            double lineY = base + style.fontSize * 0.35;
            if (end < x + width) {
                c.line(end, lineY, x + width, lineY, 0.6, lineColor);
            }
        }
    }

    /**
     * Ligne à puce : la puce est un petit disque dessiné, pas un caractère, pour que le texte lu par les
     * logiciels RH ne contienne pas de symbole parasite.
     */
    public static final class Bullet extends Flowable {

        private final Para para;

        public Bullet(Para para) {
            this.para = para;
        }

        @Override
        public void wrap(Fonts fonts, double availWidth) {
            para.wrap(fonts, availWidth);
            width = availWidth;
            height = para.height();
        }

        @Override
        public double spaceBefore() {
            return para.spaceBefore();
        }

        @Override
        public void draw(Canvas c, double x, double y) {
            ParaStyle st = para.style;
            Rgb dot = st.dotColor != null ? st.dotColor : st.textColor;
            c.circle(x + st.bulletIndent + 1.6, y + height - st.fontSize * 0.68, st.dotColor != null ? 1.35 : 1.25, dot);
            para.draw(c, x, y);
        }
    }
}
