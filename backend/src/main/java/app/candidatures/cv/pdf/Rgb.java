package app.candidatures.cv.pdf;

/** Couleur (composantes de 0 à 1). */
public record Rgb(double red, double green, double blue) {

    public static final Rgb WHITE = new Rgb(1, 1, 1);

    /** « #1f4e79 » -> couleur ; lève IllegalArgumentException si le texte n'est pas une couleur. */
    public static Rgb hex(String value) {
        String v = value == null ? "" : value.strip();
        if (v.startsWith("#")) {
            v = v.substring(1);
        } else if (v.toLowerCase().startsWith("0x")) {
            v = v.substring(2);
        }
        if (!v.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("couleur invalide : " + value);
        }
        int n = Integer.parseInt(v, 16);
        return new Rgb(((n >> 16) & 0xff) / 255.0, ((n >> 8) & 0xff) / 255.0, (n & 0xff) / 255.0);
    }

    /** Couleur entre a (t = 0) et b (t = 1). */
    public static Rgb mix(Rgb a, Rgb b, double t) {
        return new Rgb(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t);
    }

    public boolean isLight() {
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue > 0.6;
    }

    public String toHex() {
        return String.format("#%02x%02x%02x", channel(red), channel(green), channel(blue));
    }

    private static int channel(double c) {
        return Math.max(0, Math.min(255, (int) Math.round(c * 255)));
    }
}
