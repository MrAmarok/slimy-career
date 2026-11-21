package app.candidatures.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** QR code en image SVG (adresse « data: » utilisable directement dans une balise img). */
final class Qr {

    private Qr() {}

    static String svgDataUri(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, Map.of(EncodeHintType.MARGIN, 2));
            int size = matrix.getWidth();
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (matrix.get(x, y)) {
                        path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                    }
                }
            }
            String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size + "\" shape-rendering=\"crispEdges\">"
                    + "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"" + path + "\"/></svg>";
            return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        } catch (WriterException e) {
            throw new IllegalStateException(e);
        }
    }
}
