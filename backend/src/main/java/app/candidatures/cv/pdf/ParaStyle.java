package app.candidatures.cv.pdf;

/** Style d'un paragraphe (police, taille, interligne, couleur, retraits). Tailles en points. */
public final class ParaStyle {

    public String fontName = "Helvetica";
    public double fontSize = 10;
    public double leading = 12;
    public Rgb textColor = new Rgb(0, 0, 0);
    public boolean centered;
    public double leftIndent;
    public double rightIndent;
    public double bulletIndent;
    public double spaceBefore;
    /** Couleur de la puce dessinée devant le paragraphe (sinon : couleur du texte). */
    public Rgb dotColor;

    public ParaStyle copy() {
        ParaStyle s = new ParaStyle();
        s.fontName = fontName;
        s.fontSize = fontSize;
        s.leading = leading;
        s.textColor = textColor;
        s.centered = centered;
        s.leftIndent = leftIndent;
        s.rightIndent = rightIndent;
        s.bulletIndent = bulletIndent;
        s.spaceBefore = spaceBefore;
        s.dotColor = dotColor;
        return s;
    }

    public ParaStyle font(String name) {
        fontName = name;
        return this;
    }

    public ParaStyle size(double size, double leading) {
        this.fontSize = size;
        this.leading = leading;
        return this;
    }

    public ParaStyle size(double size) {
        this.fontSize = size;
        return this;
    }

    public ParaStyle color(Rgb color) {
        textColor = color;
        return this;
    }

    public ParaStyle before(double space) {
        spaceBefore = space;
        return this;
    }
}
