package app.candidatures.cv.pdf;

/**
 * Élément posé l'un sous l'autre dans la page (paragraphe, titre, espace…). `wrap` calcule sa hauteur pour une
 * largeur donnée, `draw` le dessine avec (x, y) = son coin bas-gauche.
 */
public abstract class Flowable {

    protected double width;
    protected double height;

    public abstract void wrap(Fonts fonts, double availWidth);

    public abstract void draw(Canvas canvas, double x, double y);

    public double height() {
        return height;
    }

    public double spaceBefore() {
        return 0;
    }

    public double spaceAfter() {
        return 0;
    }

    /** Espace vertical vide. */
    public static final class Spacer extends Flowable {

        public Spacer(double height) {
            this.height = height;
        }

        @Override
        public void wrap(Fonts fonts, double availWidth) {
            width = availWidth;
        }

        @Override
        public void draw(Canvas canvas, double x, double y) { }
    }

    /** Filet horizontal sur toute la largeur. */
    public static final class Rule extends Flowable {

        private final Rgb color;
        private final double before;
        private final double after;

        public Rule(double thickness, Rgb color, double spaceBefore, double spaceAfter) {
            this.height = thickness;
            this.color = color;
            this.before = spaceBefore;
            this.after = spaceAfter;
        }

        @Override
        public void wrap(Fonts fonts, double availWidth) {
            width = availWidth;
        }

        @Override
        public void draw(Canvas canvas, double x, double y) {
            canvas.line(x, y, x + width, y, height, color);
        }

        @Override
        public double spaceBefore() {
            return before;
        }

        @Override
        public double spaceAfter() {
            return after;
        }
    }
}
