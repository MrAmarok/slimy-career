package app.candidatures.jobs;

import java.util.List;

/** Offre prête à être enregistrée (trouvée par la recherche ou saisie à la main). */
public record Job(String title, String company, String location, String description, String link, String source,
                  String contract, String datePosted, String country, List<String> skills, List<String> tags,
                  List<String> categories, List<String> zones) {}
