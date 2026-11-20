package app.candidatures.common;

import java.io.IOException;

/** Corps de requête plus gros que la taille autorisée (détecté pendant sa lecture). */
public class BodyTooLargeException extends IOException {

    public BodyTooLargeException() {
        super("corps de requête trop volumineux");
    }
}
