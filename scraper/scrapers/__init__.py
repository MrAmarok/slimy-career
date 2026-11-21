from . import hellowork, indeed, linkedin

# Google Jobs passe par l'API SerpAPI, qui demande une clé : il est interrogé par le serveur lui-même,
# pour qu'aucune clé ne soit jamais confiée à ces scripts.
SCRAPERS = {
    "linkedin": linkedin.search,
    "hellowork": hellowork.search,
    "indeed": indeed.search,
}
