import {placeholderScenes, validManifest, sceneIndex, shotUrl, shotAlt} from './scenes.mjs';

const root = document.documentElement;
const sceneFrames = [...document.querySelectorAll('[data-scene]')];
const allFrames = [...document.querySelectorAll('.phone[data-view]')];
const controls = [...document.querySelectorAll('[data-motion]')];
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
const FIRST_CHANGE = 3500;
const HOLD = 6500;
const FADE = 850;

let manifest = null;
let scenes = placeholderScenes;
let index = 0;
let paused = false;
let visible = true;
let ready = false;
let firstChange = true;
let timer;
let request = 0;

// Screenshots live in assets/shots/ (approved for the public page by the PO, 2026-11-08).
// If they cannot load, the page keeps its drawn placeholders; nothing else changes.
async function loadManifest() {
  // Opt-in: only a page marked <html data-shots> looks for screenshots, so a deploy without them requests nothing extra.
  if (!root.hasAttribute('data-shots')) return null;
  try {
    const response = await fetch('assets/shots/manifest.json', {cache: 'no-cache'});
    if (!response.ok) return null;
    return validManifest(await response.json());
  } catch { return null; }
}

function applyTheme(theme) {
  root.style.setProperty('--accent', theme.accent);
  root.style.setProperty('--accent-text', theme.text);
  root.style.setProperty('--accent-page', theme.page);
}

function makeImage(file, view, cover) {
  const image = new Image(manifest.width, manifest.height);
  image.src = shotUrl(file);
  image.alt = shotAlt(view, cover);
  image.decoding = 'async';
  return image;
}

// Wait for the file, not for decode(): decode() can stay pending in background tabs and headless captures.
function loaded(image) {
  if (image.complete && image.naturalWidth) return Promise.resolve();
  return new Promise((resolve, reject) => {
    image.addEventListener('load', resolve, {once: true});
    image.addEventListener('error', reject, {once: true});
  });
}

async function placeImages(entries, animate) {
  await Promise.all(entries.map(async ({frame, image}) => {
    const screen = frame.querySelector('.screen');
    const previous = [...screen.querySelectorAll('img')];
    frame.classList.add('has-shot');
    frame.removeAttribute('role');
    frame.removeAttribute('aria-label');
    image.style.opacity = '0';
    screen.append(image);
    if (animate && !reducedMotion.matches && previous.length) {
      const fade = image.animate([{opacity: 0}, {opacity: 1}], {duration: FADE, easing: 'ease-in-out', fill: 'forwards'});
      await fade.finished.catch(() => {});
      image.style.opacity = '1';
      fade.cancel();
    } else image.style.opacity = '1';
    previous.forEach(old => old.remove());
  }));
}

async function showScene(nextIndex, animate = true) {
  const ticket = ++request;
  const scene = scenes[nextIndex];
  let prepared = [];
  if (manifest) {
    try {
      // Decode every view of the scene first, so the phones and the tint always change together.
      prepared = await Promise.all(sceneFrames.map(async frame => {
        const view = frame.dataset.view;
        const image = makeImage(scene[view], view, scene.cover);
        await loaded(image);
        return {frame, image};
      }));
    } catch { return false; } // Keep the current scene if an image cannot load.
  }
  if (ticket !== request) return false;
  index = nextIndex;
  applyTheme(scene);
  await placeImages(prepared, animate);
  return true;
}

async function showStaticShots() {
  const entries = [];
  for (const frame of allFrames) {
    const view = frame.dataset.view;
    const entry = manifest.fixed[view];
    if (!entry || frame.hasAttribute('data-scene')) continue;
    const image = makeImage(entry.file, view, entry.cover);
    try { await loaded(image); entries.push({frame, image}); } catch { /* keep the placeholder */ }
  }
  await placeImages(entries, false);
}

function updateControls() {
  controls.forEach(button => {
    button.hidden = scenes.length < 2;
    const icon = paused ? '<path d="M2 1l8 5-8 5z"/>' : '<path d="M2 1h2.5v10H2zM7.5 1H10v10H7.5z"/>';
    button.innerHTML = `<svg viewBox="0 0 12 12" aria-hidden="true">${icon}</svg>${paused ? 'Play preview' : 'Pause preview'}`;
  });
}

function schedule() {
  clearTimeout(timer);
  if (!ready || paused || !visible || document.hidden || scenes.length < 2) {
    if (ready) request++; // A pending decode must not start a new change after pausing.
    return;
  }
  timer = setTimeout(async () => {
    firstChange = false;
    await showScene((index + 1) % scenes.length);
    schedule();
  }, firstChange ? FIRST_CHANGE : HOLD);
}

controls.forEach(button => button.addEventListener('click', () => {
  paused = !paused;
  updateControls();
  schedule();
}));

const visibleFrames = new Set();
const observer = new IntersectionObserver(entries => {
  entries.forEach(entry => entry.isIntersecting ? visibleFrames.add(entry.target) : visibleFrames.delete(entry.target));
  const nextVisible = visibleFrames.size > 0;
  if (nextVisible !== visible) { visible = nextVisible; schedule(); }
}, {threshold: .12});
sceneFrames.forEach(frame => observer.observe(frame));
document.addEventListener('visibilitychange', schedule);

manifest = await loadManifest();
if (manifest) scenes = manifest.scenes;
const random = Number(root.dataset.random);
index = sceneIndex(Number.isFinite(random) ? random : Math.random(), scenes.length);
ready = await showScene(index, false);
if (!ready && manifest) {
  // Screenshots are broken: fall back to the drawn placeholders.
  manifest = null;
  scenes = placeholderScenes;
  index = sceneIndex(Math.random(), scenes.length);
  ready = await showScene(index, false);
}
if (manifest) await showStaticShots();
void document.body.offsetWidth; // Commit the first theme while transitions are off, so it does not fade in from the default.
root.classList.remove('scene-pending');
if (ready) {
  updateControls();
  schedule();
}

