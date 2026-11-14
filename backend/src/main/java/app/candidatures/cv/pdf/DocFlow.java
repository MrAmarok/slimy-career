package app.candidatures.cv.pdf;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Pose les éléments l'un sous l'autre dans la zone utile des pages A4 et passe à la page suivante quand il n'y a
 * plus de place. Un groupe {@link Keep} n'est pas coupé entre deux pages (sauf s'il est plus haut qu'une page).
 */
public final class DocFlow {

    public static final double PAGE_WIDTH = PDRectangle.A4.getWidth();
    public static final double PAGE_HEIGHT = PDRectangle.A4.getHeight();
    /** Marge intérieure du cadre de texte, en plus des marges de la page. */
    public static final double PADDING = 6;
    private static final double FUZZ = 1e-6;

    /** Éléments à garder ensemble sur la même page. */
    public record Keep(List<Flowable> items) {}

    private final Canvas canvas;
    private final Fonts fonts;
    private final double frameX;
    private final double frameWidth;
    private final double top;
    private final double bottom;
    private final Consumer<Canvas> onFirstPage;

    private double y;
    private boolean atTop;
    private double previousSpaceAfter;
    private int pages;

    public DocFlow(Canvas canvas, double marginX, double marginY, Consumer<Canvas> onFirstPage) {
        this.canvas = canvas;
        this.fonts = canvas.fonts;
        this.frameX = marginX + PADDING;
        this.frameWidth = PAGE_WIDTH - 2 * marginX - 2 * PADDING;
        this.top = PAGE_HEIGHT - marginY - PADDING;
        this.bottom = marginY + PADDING;
        this.onFirstPage = onFirstPage;
    }

    public int pages() {
        return pages;
    }

    private void newPage() {
        canvas.newPage();
        pages++;
        if (pages == 1 && onFirstPage != null) {
            onFirstPage.accept(canvas);
        }
        y = top;
        atTop = true;
        previousSpaceAfter = 0;
    }

    private double spaceBefore(Flowable f) {
        return atTop ? 0 : Math.max(f.spaceBefore() - previousSpaceAfter, 0);
    }

    /** Hauteur d'un groupe posé d'un bloc (sans l'espace avant le premier ni après le dernier). */
    private double groupHeight(List<Flowable> items) {
        double total = 0;
        double after = 0;
        boolean first = true;
        for (Flowable f : items) {
            f.wrap(fonts, frameWidth);
            total += f.height();
            if (!first) {
                total += Math.max(f.spaceBefore() - after, 0);
            }
            first = false;
            after = f.spaceAfter();
            total += after;
        }
        return total - after;
    }

    public void build(List<Object> story) {
        Deque<Object> queue = new ArrayDeque<>(story);
        newPage();
        while (!queue.isEmpty()) {
            Object item = queue.poll();
            if (item instanceof Keep keep) {
                if (keep.items().isEmpty()) {
                    continue;
                }
                double available = y - bottom - spaceBefore(keep.items().get(0));
                if (!atTop && groupHeight(keep.items()) > available + FUZZ) {
                    newPage();
                }
                for (int i = keep.items().size() - 1; i >= 0; i--) {
                    queue.push(keep.items().get(i));
                }
                continue;
            }
            Flowable f = (Flowable) item;
            double before = spaceBefore(f);
            f.wrap(fonts, frameWidth);
            if (y - before - f.height() < bottom - FUZZ) {
                Para[] parts = f instanceof Para p ? p.split(y - before - bottom) : null;
                if (parts != null) {
                    place(parts[0], before);
                    queue.push(parts[1]);
                    newPage();
                    continue;
                }
                if (!atTop) {
                    newPage();
                    queue.push(f);
                    continue;
                }
                // plus haut qu'une page entière et impossible à couper : posé quand même, quitte à déborder
            }
            place(f, before);
        }
    }

    private void place(Flowable f, double before) {
        y -= before + f.height();
        f.draw(canvas, frameX, y);
        y -= f.spaceAfter();
        previousSpaceAfter = f.spaceAfter();
        atTop = false;
    }
}
