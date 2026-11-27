package app.candidatures.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Adresse du visiteur. Derrière le reverse proxy (Caddy), elle est lue dans l'en-tête que le proxy écrit lui-même
 * (APP_CLIENT_IP_HEADER = X-Real-IP) : contrairement à X-Forwarded-For, le visiteur ne peut pas le choisir.
 */
@Component
public class ClientIp {

    private final String header;

    public ClientIp(@Value("${app.client-ip-header}") String header) {
        this.header = header.strip();
    }

    public String of(HttpServletRequest request) {
        if (!header.isEmpty()) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && value.length() <= 64) {
                return value.strip();
            }
        }
        // sans en-tête de confiance : l'adresse de la connexion elle-même, sans tenir compte de X-Forwarded-For
        jakarta.servlet.ServletRequest inner = request;
        while (inner instanceof jakarta.servlet.ServletRequestWrapper wrapper) {
            inner = wrapper.getRequest();
        }
        return inner.getRemoteAddr();
    }
}
