package app.candidatures.cv.pdf;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

/** Dessin sur la page courante. Origine en bas à gauche, unités en points (comme le format PDF). */
public final class Canvas {

    private static final float KAPPA = 0.5522848f; // approximation d'un quart de cercle par une courbe de Bézier

    final PDDocument document;
    final Fonts fonts;
    private PDPage page;
    private PDPageContentStream stream;

    Canvas(PDDocument document, Fonts fonts) {
        this.document = document;
        this.fonts = fonts;
    }

    void newPage() {
        try {
            close();
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void close() throws IOException {
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }

    private void fill(Rgb color) throws IOException {
        stream.setNonStrokingColor((float) color.red(), (float) color.green(), (float) color.blue());
    }

    public void text(double x, double baseline, String text, String fontName, double size, Rgb color) {
        text(x, baseline, text, fontName, size, color, 0);
    }

    /** `wordSpacing` : espace ajoutée (ou retirée si négative) à chaque caractère espace. */
    public void text(double x, double baseline, String text, String fontName, double size, Rgb color, double wordSpacing) {
        if (text.isEmpty()) {
            return;
        }
        try {
            stream.beginText();
            stream.setWordSpacing((float) wordSpacing);
            stream.setFont(fonts.get(fontName), (float) size);
            fill(color);
            stream.newLineAtOffset((float) x, (float) baseline);
            try {
                stream.showText(text);
            } catch (IllegalArgumentException e) {   // caractère hors encodage passé entre les mailles
                stream.showText(Clean.encodableOnly(text));
            }
            stream.endText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void textRight(double xRight, double baseline, String text, String fontName, double size, Rgb color) {
        text(xRight - fonts.width(text, fontName, size), baseline, text, fontName, size, color);
    }

    public void rect(double x, double y, double w, double h, Rgb color) {
        try {
            fill(color);
            stream.addRect((float) x, (float) y, (float) w, (float) h);
            stream.fill();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void roundRect(double x, double y, double w, double h, double radius, Rgb color) {
        try {
            float x0 = (float) x, y0 = (float) y, x1 = (float) (x + w), y1 = (float) (y + h);
            float r = (float) Math.min(radius, Math.min(w, h) / 2);
            float k = r * (1 - KAPPA);
            fill(color);
            stream.moveTo(x0 + r, y0);
            stream.lineTo(x1 - r, y0);
            stream.curveTo(x1 - k, y0, x1, y0 + k, x1, y0 + r);
            stream.lineTo(x1, y1 - r);
            stream.curveTo(x1, y1 - k, x1 - k, y1, x1 - r, y1);
            stream.lineTo(x0 + r, y1);
            stream.curveTo(x0 + k, y1, x0, y1 - k, x0, y1 - r);
            stream.lineTo(x0, y0 + r);
            stream.curveTo(x0, y0 + k, x0 + k, y0, x0 + r, y0);
            stream.closePath();
            stream.fill();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void circlePath(double cx, double cy, double radius) throws IOException {
        float x = (float) cx, y = (float) cy, r = (float) radius, k = r * KAPPA;
        stream.moveTo(x + r, y);
        stream.curveTo(x + r, y + k, x + k, y + r, x, y + r);
        stream.curveTo(x - k, y + r, x - r, y + k, x - r, y);
        stream.curveTo(x - r, y - k, x - k, y - r, x, y - r);
        stream.curveTo(x + k, y - r, x + r, y - k, x + r, y);
        stream.closePath();
    }

    public void circle(double cx, double cy, double radius, Rgb color) {
        try {
            fill(color);
            circlePath(cx, cy, radius);
            stream.fill();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void line(double x1, double y1, double x2, double y2, double width, Rgb color) {
        try {
            stream.setStrokingColor((float) color.red(), (float) color.green(), (float) color.blue());
            stream.setLineWidth((float) width);
            stream.moveTo((float) x1, (float) y1);
            stream.lineTo((float) x2, (float) y2);
            stream.stroke();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Image recadrée au centre dans un disque de diamètre `size` dont le coin bas-gauche est (x, y). */
    public void imageInCircle(PDImageXObject image, double x, double y, double size) {
        try {
            stream.saveGraphicsState();
            circlePath(x + size / 2, y + size / 2, size / 2);
            stream.clip();
            double scale = size / Math.min(image.getWidth(), image.getHeight());
            double w = image.getWidth() * scale;
            double h = image.getHeight() * scale;
            stream.drawImage(image, (float) (x - (w - size) / 2), (float) (y - (h - size) / 2), (float) w, (float) h);
            stream.restoreGraphicsState();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Zone cliquable (lien) posée sur du texte déjà écrit. */
    public void link(double x, double y, double w, double h, String url) {
        try {
            PDAnnotationLink link = new PDAnnotationLink();
            PDBorderStyleDictionary border = new PDBorderStyleDictionary();
            border.setWidth(0);
            link.setBorderStyle(border);
            link.setRectangle(new PDRectangle((float) x, (float) y, (float) w, (float) h));
            PDActionURI action = new PDActionURI();
            action.setURI(url);
            link.setAction(action);
            page.getAnnotations().add(link);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
