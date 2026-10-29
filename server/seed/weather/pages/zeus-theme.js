// Zeus layer: loads after fold-theme.js. Zeus hosts this page under its own header and bars, so the
// page drops its status-bar inset and takes Zeus's tokens (warm charcoal, ink, saffron) for its
// surfaces and accents. The Samsung scene keeps its own colours. Called by AeoliaHost.applyZeus.
(() => {
  const css = document.createElement('style');
  css.textContent = `
  :root{--inset-top:0px;--inset-bottom:0px}
  body::before{display:none}
  main>.hero{padding-top:112px!important}
  header{top:0}
  .location-tabs{top:56px!important}
  .search{padding-top:16px}
  .nws-alert[data-severity=Extreme],.nws-alert[data-severity=Severe]{--sev:var(--warn)}
  .place-tab[aria-current=page] .place-name{border-bottom-color:var(--accent)!important}
  .card-tools button[aria-pressed=true]{border-bottom-color:var(--accent)}
  .settings-sheet input{accent-color:var(--accent)!important}
  /* Zeus has no home-screen widgets from this page, and no spoken wake-up forecast. */
  #add-widget-settings,label.toggle:has(.wake-time),label.toggle:has(.wake-time)+p{display:none!important}
  /* fold-theme's row buttons are display:block, which beat [hidden] (Allow exact alarm timing showed with no alarm). */
  .settings-sheet [hidden],.settings-sheet .nano-status:empty{display:none!important}
  `;
  document.head.append(css);

  // Settings live in Zeus's own screen: the gear opens it, and the page tells Zeus what it can list (scenes, cards,
  // achievements) so that screen shows the same choices. Values stay in the bridge's preferences.
  const catalog = () => {
    try {
      const scenes = [...(document.getElementById('scene-choice')?.options || [])].map(o => [o.value, o.textContent]);
      const cards = [...document.querySelectorAll('main>[data-card-id]')].map(c => [c.dataset.cardId, c.querySelector('h2')?.textContent || c.dataset.cardId]);
      const achievements = (window.WeatherAchievements?.list || []).map(a => [a[0], a[1], a[2]]);
      Weather.catalog?.(JSON.stringify({ scenes, cards, achievements }));
    } catch (e) {}
  };
  const gear = document.querySelector('[aria-label="Weather settings"]');
  if (gear && Weather.openSettings) gear.onclick = () => { catalog(); Weather.openSettings(); };
  setTimeout(catalog, 1500);

  const KEYS = { bg: '--bg', bg2: '--bg2', bg3: '--bg3', ink: '--ink', ink2: '--ink2', ink3: '--ink3', accent: '--accent', accentInk: '--accent-ink', alert: '--warn' };
  window.applyZeus = (t) => {
    const r = document.documentElement;
    r.dataset.theme = t.dark ? 'dark' : 'light';
    for (const [k, v] of Object.entries(KEYS)) if (typeof t[k] === 'string' && /^#[0-9A-F]{6}$/i.test(t[k])) r.style.setProperty(v, t[k]);
    r.style.setProperty('--line', 'color-mix(in srgb,var(--ink) 14%,transparent)');
  };
})();
