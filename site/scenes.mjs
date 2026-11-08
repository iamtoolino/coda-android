// Accent themes for the homepage. The values are the worked examples in design/tokens.json
// (color.examples): accent, accentText and page, computed by the app's own rules.
// They contain no library material, so the committed page can cycle them on its own.
export const themes = {
  vivid: {label: 'magenta', accent: '#8B3D71', text: '#E995C9', page: '#3C1E33'},
  busy: {label: 'blue', accent: '#2F59AD', text: '#8CB4FF', page: '#18294B'},
  pastel: {label: 'green', accent: '#3E9C5B', text: '#78CA8C', page: '#1E442A'},
  typographic: {label: 'amber', accent: '#9B4C13', text: '#F19D6C', page: '#43240E'},
};

export const views = {
  nowplaying: 'Now Playing',
  album: 'album',
  queue: 'queue',
  storage: 'Music storage',
};

// Without screenshots, the placeholders cycle through every token theme.
export const placeholderScenes = Object.keys(themes).map(theme => ({...themes[theme]}));

const HEX = /^#[0-9A-F]{6}$/i;

// assets/shots/manifest.json: one scene per cover. Each scene carries the accent the app picked for that cover
// (read from the screenshot) with accentText and page derived by the token rules; scripts/check-homepage.py
// checks the derivation and the contrast. "cover" only describes colours, for the alt text.
// {"scenes": [{"cover": "magenta neon", "accent": "#8B3C72", "text": "#E895CA", "page": "#3C1D34",
//              "nowplaying": "np-magenta.webp", "album": "album-magenta.webp"}, ...],
//  "static": {"queue": {"file": "queue.webp", "cover": "violet"}, "storage": {"file": "storage.webp"}},
//  "width": 720, "height": 1560}
export function validManifest(data) {
  if (!data || !Array.isArray(data.scenes)) return null;
  const scenes = data.scenes.filter(scene => scene && ['accent', 'text', 'page'].every(key => HEX.test(scene[key]))
    && typeof scene.nowplaying === 'string' && typeof scene.album === 'string');
  if (scenes.length === 0) return null;
  const fixed = {};
  for (const [view, entry] of Object.entries(data.static || {})) {
    if (views[view] && typeof entry?.file === 'string') fixed[view] = entry;
  }
  return {scenes, fixed, width: Number(data.width) || 720, height: Number(data.height) || 1560};
}

export function sceneIndex(random, count) {
  return Math.min(count - 1, Math.floor(random * count));
}

export function shotUrl(file) {
  return `assets/shots/${encodeURIComponent(file)}`;
}

export function shotAlt(view, cover) {
  return typeof cover === 'string' && cover ? `Coda ${views[view]} screen with a ${cover} cover.` : `Coda ${views[view]} screen.`;
}
