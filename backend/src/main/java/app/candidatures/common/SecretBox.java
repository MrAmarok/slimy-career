package app.candidatures.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Chiffrement des secrets rangés en base (AES-256-GCM) avec une clé qui ne vit que dans l'environnement du
 * serveur (APP_SECRET_KEY) : une copie de la base seule ne suffit pas à les lire.
 */
@Component
public class SecretBox {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(@Value("${app.secret-key}") String secret) throws GeneralSecurityException {
        this.key = secret.isBlank() ? null
                : new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    private SecretKeySpec key() {
        if (key == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "APP_SECRET_KEY n'est pas défini sur le serveur : cette fonction est indisponible.");
        }
        return key;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] raw = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            return new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Secret illisible : APP_SECRET_KEY a changé depuis son enregistrement.");
        }
    }
}
