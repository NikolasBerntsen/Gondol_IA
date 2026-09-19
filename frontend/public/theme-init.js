// Tema elegido (Claro / Oscuro) antes del primer pintado, para que no parpadee. "Sistema" = sin atributo.
// Misma clave y colores que src/theme/theme.ts; después lo mantiene el ThemeProvider.
// Es un archivo aparte (y no un script inline en index.html) porque la CSP de nginx solo permite scripts de 'self'.
(function () {
  try {
    var theme = window.localStorage.getItem('gondolia.theme');
    if (theme !== 'light' && theme !== 'dark') return;
    document.documentElement.setAttribute('data-theme', theme);
    document.querySelector('meta[name="color-scheme"]').setAttribute('content', theme);
    var metas = document.querySelectorAll('meta[name="theme-color"]');
    for (var i = 0; i < metas.length; i++) {
      metas[i].setAttribute('content', theme === 'dark' ? '#0D1410' : '#F3F5F1');
    }
  } catch (e) {
    // Sin storage (navegación privada): sigue al sistema.
  }
})();
