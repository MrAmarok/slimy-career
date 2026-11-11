package app.candidatures.jobs;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Ville et pays à partir des formats de localisation des différents sites. */
public final class Geo {

    /** nom ou code -> nom affiché */
    private static final Map<String, String> COUNTRIES = Map.ofEntries(
            Map.entry("france", "France"), Map.entry("fr", "France"),
            Map.entry("belgique", "Belgique"), Map.entry("belgium", "Belgique"), Map.entry("be", "Belgique"),
            Map.entry("suisse", "Suisse"), Map.entry("switzerland", "Suisse"), Map.entry("ch", "Suisse"),
            Map.entry("luxembourg", "Luxembourg"), Map.entry("lu", "Luxembourg"),
            Map.entry("monaco", "Monaco"), Map.entry("mc", "Monaco"),
            Map.entry("canada", "Canada"), Map.entry("ca", "Canada"),
            Map.entry("allemagne", "Allemagne"), Map.entry("germany", "Allemagne"), Map.entry("deutschland", "Allemagne"), Map.entry("de", "Allemagne"),
            Map.entry("espagne", "Espagne"), Map.entry("spain", "Espagne"), Map.entry("es", "Espagne"),
            Map.entry("italie", "Italie"), Map.entry("italy", "Italie"), Map.entry("it", "Italie"),
            Map.entry("royaume-uni", "Royaume-Uni"), Map.entry("united kingdom", "Royaume-Uni"), Map.entry("uk", "Royaume-Uni"), Map.entry("gb", "Royaume-Uni"),
            Map.entry("pays-bas", "Pays-Bas"), Map.entry("netherlands", "Pays-Bas"), Map.entry("nl", "Pays-Bas"),
            Map.entry("irlande", "Irlande"), Map.entry("ireland", "Irlande"), Map.entry("ie", "Irlande"),
            Map.entry("portugal", "Portugal"), Map.entry("pt", "Portugal"),
            Map.entry("maroc", "Maroc"), Map.entry("morocco", "Maroc"), Map.entry("ma", "Maroc"),
            Map.entry("tunisie", "Tunisie"), Map.entry("tunisia", "Tunisie"), Map.entry("tn", "Tunisie"),
            Map.entry("états-unis", "États-Unis"), Map.entry("united states", "États-Unis"), Map.entry("usa", "États-Unis"), Map.entry("us", "États-Unis"));

    private static final Pattern FRENCH_DEPARTMENT = Pattern.compile("\\s-\\s(\\d{2}|2[AB]|97\\d)$");   // « Lyon - 69 » (HelloWork)
    private static final Pattern DEPARTMENT_SUFFIX = Pattern.compile("\\s+-\\s+\\d+[AB]?$");
    private static final Pattern DISTRICT_SUFFIX = Pattern.compile("\\s+\\d{1,2}(er|e|ème)$");
    private static final Pattern SUBURBS_SUFFIX = Pattern.compile("\\s+et périphérie$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private Geo() {}

    public static String normalizeCountry(String name) {
        return COUNTRIES.get((name == null ? "" : name).strip().toLowerCase(Locale.ROOT));
    }

    /** Pays si la localisation l'indique (« Lyon, ARA, France », « Lyon - 69 »), sinon `fallback`. */
    public static String countryOf(String location, String fallback) {
        if (location != null && !location.isEmpty()) {
            String[] parts = location.split(",", -1);
            if (parts.length > 1) {
                String c = normalizeCountry(parts[parts.length - 1]);
                if (c != null) {
                    return c;
                }
            }
            String c = normalizeCountry(location);
            if (c != null) {
                return c;
            }
            if (FRENCH_DEPARTMENT.matcher(location).find()) {
                return "France";
            }
        }
        return fallback;
    }

    /** « Toulouse, Occitanie, France » / « Lyon - 69 » / « Paris 8e » / « Paris et périphérie » -> ville. */
    public static String cityOf(String location) {
        if (location == null || location.isEmpty()) {
            return null;
        }
        String city = location.split(",", -1)[0];
        if (normalizeCountry(city) != null) {
            return null;
        }
        city = DEPARTMENT_SUFFIX.matcher(city).replaceFirst("");
        city = DISTRICT_SUFFIX.matcher(city).replaceFirst("");
        city = SUBURBS_SUFFIX.matcher(city).replaceFirst("");
        city = city.strip();
        return city.isEmpty() ? null : city;
    }
}
