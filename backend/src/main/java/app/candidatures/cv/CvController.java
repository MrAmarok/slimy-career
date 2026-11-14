package app.candidatures.cv;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import app.candidatures.auth.AppUser;
import app.candidatures.common.ApiException;
import app.candidatures.common.Values;
import app.candidatures.cv.pdf.CvPdf;
import app.candidatures.settings.SettingsService;

@RestController
@RequestMapping("/api")
public class CvController {

    private final CvRepository cvs;
    private final CvLang lang;
    private final SettingsService settings;

    CvController(CvRepository cvs, CvLang lang, SettingsService settings) {
        this.cvs = cvs;
        this.lang = lang;
        this.settings = settings;
    }

    private static Map<String, Object> dataOf(Map<String, Object> body) {
        if (body.containsKey("data") && Values.map(body.get("data")) == null) {
            throw ApiException.badRequest("« data » doit être un objet.");
        }
        return Values.map(body.get("data"));
    }

    private static Map<String, Object> requiredData(Map<String, Object> body) {
        Map<String, Object> data = Values.map(body.get("data"));
        if (data == null) {
            throw ApiException.badRequest("« data » attendu.");
        }
        return data;
    }

    private static String nameOf(Object value) {
        String name = Values.trimmed(value);
        return name.isEmpty() ? "Mon CV" : name;
    }

    @GetMapping("/cvs")
    List<Map<String, Object>> list(@AuthenticationPrincipal AppUser user) {
        return cvs.list(user.id());
    }

    /** Nouveau CV : vide, ou copie d'un CV existant (from_id) pour l'adapter à une offre. */
    @PostMapping("/cvs")
    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> body) {
        Map<String, Object> content = CvModel.empty();
        Long fromId = Values.asLong(body.get("from_id"));
        if (fromId != null) {
            content = (Map<String, Object>) cvs.find(user.id(), fromId).orElseThrow(ApiException::notFound).get("data");
        }
        Map<String, Object> given = dataOf(body);
        if (given != null) {
            content = given;
        }
        long id = cvs.create(user.id(), nameOf(body.get("name")), content, Values.asLong(body.get("job_id")));
        return ResponseEntity.status(HttpStatus.CREATED).body(cvs.find(user.id(), id).orElseThrow());
    }

    /** Crée un nouveau CV à partir d'un PDF existant (texte extrait et rangé dans les sections). */
    @PostMapping("/cvs/import")
    ResponseEntity<Map<String, Object>> importPdf(@AuthenticationPrincipal AppUser user,
                                                  @RequestParam(name = "file", required = false) MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Aucun fichier.");
        }
        byte[] raw = file.getBytes();
        if (raw.length < 4 || raw[0] != '%' || raw[1] != 'P' || raw[2] != 'D' || raw[3] != 'F') {
            throw ApiException.badRequest("Ce fichier n'est pas un PDF.");
        }
        CvImport.Result parsed;
        try {
            parsed = CvImport.parsePdf(raw);
        } catch (IOException | RuntimeException e) {
            throw ApiException.badRequest("PDF illisible : " + e.getMessage());
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replaceAll("^.*[/\\\\]", "");
        String stem = filename.replaceAll("\\.[^.]*$", "");
        long id = cvs.create(user.id(), "Import — " + (stem.isEmpty() ? "CV" : stem), parsed.cv(), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cv", cvs.find(user.id(), id).orElseThrow());
        out.put("warnings", parsed.warnings());
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @GetMapping("/cvs/{id}")
    Map<String, Object> get(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        return cvs.find(user.id(), id).orElseThrow(ApiException::notFound);
    }

    @PutMapping("/cvs/{id}")
    @Transactional
    Map<String, Object> update(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> data = dataOf(body);
        if (!body.containsKey("name") && data == null && !body.containsKey("job_id")) {
            throw ApiException.badRequest("Rien à modifier.");
        }
        boolean found = true;
        if (body.containsKey("name")) {
            found &= cvs.rename(user.id(), id, nameOf(body.get("name")));
        }
        if (data != null) {
            found &= cvs.setData(user.id(), id, data);
        }
        if (body.containsKey("job_id")) {
            found &= cvs.setJob(user.id(), id, Values.asLong(body.get("job_id")));
        }
        if (!found) {
            throw ApiException.notFound();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated_at", cvs.updatedAt(user.id(), id));
        return out;
    }

    @DeleteMapping("/cvs/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        cvs.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    /** PDF du CV envoyé (pas besoin qu'il soit enregistré : sert aussi à l'aperçu en direct). */
    @PostMapping("/cv/pdf")
    ResponseEntity<byte[]> pdf(@RequestBody Map<String, Object> data, @RequestParam(name = "download", required = false) String download) {
        CvPdf.Result result = CvPdf.build(data);
        String disposition = download != null && !download.isEmpty() ? "attachment" : "inline";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", disposition + "; filename=\"" + result.filename() + "\"")
                .header("X-Filename", result.filename())
                .header("X-Pages", String.valueOf(result.pages()))
                .header("X-Compact", String.format(java.util.Locale.ROOT, "%.2f", result.compact()))
                .header("X-Overflow", result.overflow() ? "1" : "0")
                .header("Cache-Control", "no-store")
                .body(result.pdf());
    }

    /** Texte extrait du PDF généré : ce qu'un logiciel de recrutement va lire. */
    @PostMapping("/cv/text")
    Map<String, Object> text(@RequestBody Map<String, Object> data) {
        return Map.of("text", CvPdf.extractText(CvPdf.build(data).pdf()));
    }

    /** Indique si la traduction est prête (clé DeepL présente), sans renvoyer la clé. */
    @GetMapping("/cv/lang")
    Map<String, Object> langStatus(@AuthenticationPrincipal AppUser user) {
        return Map.of("deepl", !settings.deeplKey(user.id()).isEmpty());
    }

    /** Fautes d'orthographe et de grammaire du CV envoyé (LanguageTool). */
    @PostMapping("/cv/check")
    Map<String, Object> check(@RequestBody Map<String, Object> body) {
        Map<String, Object> data = requiredData(body);
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("issues", lang.check(data, Values.strings(body.get("ignore"))));
            out.put("language", CvLang.languageOf(data));
            return out;
        } catch (CvLang.LangException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /** Copie du CV envoyé, traduite en anglais (DeepL). Le CV d'origine n'est pas modifié. */
    @PostMapping("/cv/translate")
    Map<String, Object> translate(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> body) {
        Map<String, Object> data = requiredData(body);
        String variant = Values.str(body.get("variant"));
        try {
            CvLang.Translation t = lang.translate(data, variant.isEmpty() ? "en-GB" : variant, settings.deeplKey(user.id()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("data", t.cv());
            out.put("chars", t.chars());
            return out;
        } catch (CvLang.LangException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }
}
