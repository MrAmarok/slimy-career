// Applique le thème avant l'affichage (évite un flash clair) : choix enregistré, sinon réglage du système.
(function () {
  var theme;
  try { theme = localStorage.getItem('theme'); } catch (e) { /* stockage indisponible */ }
  if (theme !== 'light' && theme !== 'dark') {
    theme = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  document.documentElement.dataset.theme = theme;
})();
