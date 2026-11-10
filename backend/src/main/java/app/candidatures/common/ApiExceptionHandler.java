package app.candidatures.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Toutes les erreurs de l'API sont du texte brut : un message lisible, affiché tel quel par l'interface. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static ResponseEntity<String> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8)).body(message);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<String> api(ApiException e) {
        return text(e.status(), e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, ClassCastException.class})
    ResponseEntity<String> unreadable(Exception e) {
        return text(HttpStatus.BAD_REQUEST, "Requête invalide.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<String> tooLarge(MaxUploadSizeExceededException e) {
        return text(HttpStatus.CONTENT_TOO_LARGE, "Fichier trop volumineux (20 Mo maximum).");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> other(Exception e) throws Exception {
        // les erreurs propres à Spring (404, méthode refusée, accès interdit…) gardent leur traitement habituel
        if (e instanceof org.springframework.web.ErrorResponse || e instanceof org.springframework.security.core.AuthenticationException
                || e instanceof org.springframework.security.access.AccessDeniedException) {
            throw e;
        }
        log.error("Erreur inattendue", e);
        return text(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne du serveur.");
    }
}
