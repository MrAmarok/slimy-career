package app.candidatures.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TotpTest {

    /** Secret « 12345678901234567890 » des exemples de la RFC 6238. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238Vectors() {
        assertThat(Totp.code(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
    }

    @Test
    void acceptsCurrentCodeOnceAndRejectsOthers() {
        String secret = Totp.newSecret();
        long now = Totp.currentStep();
        String code = Totp.code(secret, now);
        assertThat(Totp.verify(secret, code, 0)).isEqualTo(now);
        assertThat(Totp.verify(secret, " " + code.substring(0, 3) + " " + code.substring(3), 0)).isEqualTo(now);
        assertThat(Totp.verify(secret, code, now)).isEqualTo(-1);              // déjà utilisé
        assertThat(Totp.verify(secret, Totp.code(secret, now - 5), 0)).isEqualTo(-1);   // trop ancien
        assertThat(Totp.verify(secret, "abcdef", 0)).isEqualTo(-1);
        assertThat(Totp.verify(Totp.newSecret(), code, 0)).isEqualTo(-1);
    }

    @Test
    void uriIsReadableByAuthenticatorApps() {
        assertThat(Totp.uri("Candidatures", "léa", "ABC")).isEqualTo(
                "otpauth://totp/Candidatures%3Al%C3%A9a?secret=ABC&issuer=Candidatures&algorithm=SHA1&digits=6&period=30");
    }
}
