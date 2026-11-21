package app.candidatures.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Codes à usage unique basés sur l'heure (TOTP, RFC 6238) : 6 chiffres, 30 secondes, HMAC-SHA1.
 * Ce sont les réglages attendus par Google Authenticator et les applications équivalentes.
 */
final class Totp {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;
    /** Tranches acceptées avant et après l'heure du serveur (horloge du téléphone légèrement décalée). */
    private static final int WINDOW = 1;

    private Totp() {}

    static String newSecret() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return base32(bytes);
    }

    static long currentStep() {
        return System.currentTimeMillis() / 1000 / PERIOD_SECONDS;
    }

    /**
     * Tranche de temps pour laquelle le code est valable, ou -1 si le code est faux.
     * Seules les tranches plus récentes que `lastStep` comptent : un code déjà utilisé est refusé.
     */
    static long verify(String secret, String code, long lastStep) {
        String digits = code == null ? "" : code.replaceAll("\\s", "");
        if (!digits.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        long now = currentStep();
        for (long step = now - WINDOW; step <= now + WINDOW; step++) {
            if (step > lastStep && MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII),
                    digits.getBytes(StandardCharsets.US_ASCII))) {
                return step;
            }
        }
        return -1;
    }

    static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Adresse lue par l'application d'authentification (contenu du QR code). */
    static String uri(String issuer, String account, String secret) {
        String label = URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20")
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    private static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    private static byte[] decode(String secret) {
        ByteBuffer out = ByteBuffer.allocate(secret.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : secret.toCharArray()) {
            int value = BASE32.indexOf(Character.toUpperCase(c));
            if (value < 0) {
                continue;
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        byte[] bytes = new byte[out.position()];
        out.flip().get(bytes);
        return bytes;
    }
}
