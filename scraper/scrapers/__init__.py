from . import google, hellowork, indeed, linkedin

SCRAPERS = {
    "linkedin": linkedin.search,
    "hellowork": hellowork.search,
    "indeed": indeed.search,
    "google": google.search,
}
