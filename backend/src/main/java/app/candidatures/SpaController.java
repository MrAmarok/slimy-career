package app.candidatures;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Les pages de l'interface Angular sont des routes côté navigateur : le serveur renvoie toujours index.html. */
@Controller
class SpaController {

    @GetMapping({"/login", "/cv", "/compte"})
    String page() {
        return "forward:/index.html";
    }
}
