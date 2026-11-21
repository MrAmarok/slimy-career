package app.candidatures.jobs;

import java.util.List;

/** Offre prête à être enregistrée (trouvée par la recherche ou saisie à la main). */
public record Job(String title, String company, String location, String description, String link, String source,
                  String contract, String datePosted, String country, List<String> skills, List<String> tags,
                  List<String> categories, List<String> zones) {

    private static final java.util.regex.Pattern WEB_LINK = java.util.regex.Pattern.compile("^https?://\\S+$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern MANUAL_LINK = java.util.regex.Pattern.compile("^manuel:[0-9a-f]{1,64}$");

    /** Seules les adresses http(s) sont acceptées comme lien d'offre (pas de « javascript: », « data: »…). */
    public static boolean isWebLink(String link) {
        return link != null && WEB_LINK.matcher(link).matches();
    }

    /** Lien web, ou identifiant interne d'une offre saisie à la main sans lien. */
    public static boolean isStorableLink(String link) {
        return isWebLink(link) || (link != null && MANUAL_LINK.matcher(link).matches());
    }
}
