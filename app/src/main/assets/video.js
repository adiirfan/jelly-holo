// Jelly Holo's video player, page side. Runs at document start in every
// frame. It tells the app about the video being watched (through the
// jhVideo channel) and carries out the app's commands, so one player (the
// app's controls) drives every site's video.
(() => {
  if (window.__jhVideo) return;

  const MIN_W = 200;
  const MIN_H = 100;
  const EVERY_MS = 500;

  let active = null;
  let armed = false;
  let armTimer = null;
  let lastPost = 0;
  let pending = null;

  const channel = () => window.jhVideo;

  /** 'file' for a plain address, 'stream' for pieces (MediaSource, HLS, DASH), 'none' otherwise. */
  function kindOf(src) {
    if (!src || src.startsWith('data:')) return 'none';
    if (src.startsWith('blob:')) return 'stream';
    if (/\.(m3u8|mpd)([?#]|$)/i.test(src)) return 'stream';
    if (/^https?:/i.test(src)) return 'file';
    return 'none';
  }

  const fsElement = () => document.fullscreenElement || document.webkitFullscreenElement || null;

  function big(v) {
    const fs = fsElement();
    if (fs && (fs === v || fs.contains(v))) return true;
    const r = v.getBoundingClientRect();
    return r.width >= MIN_W && r.height >= MIN_H;
  }

  function state(v) {
    const r = v.getBoundingClientRect();
    const fs = fsElement();
    const src = v.currentSrc || v.src || '';
    const kind = kindOf(src);
    return {
      type: 'state',
      playing: !v.paused && !v.ended,
      time: v.currentTime || 0,
      duration: isFinite(v.duration) ? v.duration : -1,
      rate: v.playbackRate,
      kind,
      src: kind === 'file' ? src : '',
      w: Math.round(r.width),
      h: Math.round(r.height),
      x: Math.round(r.left),
      y: Math.round(r.top),
      vw: v.videoWidth || 0,
      vh: v.videoHeight || 0,
      viewW: window.innerWidth,
      top: window.top === window,
      fullscreen: !!fs && (fs === v || fs.contains(v)),
      gesture: !!(navigator.userActivation && navigator.userActivation.isActive),
      muted: v.muted,
      title: document.title || '',
    };
  }

  function post(force) {
    const ch = channel();
    if (!active || !ch) return;
    const now = Date.now();
    if (!force && now - lastPost < EVERY_MS) {
      // Keep the latest for when the wait is over
      if (!pending) pending = setTimeout(() => { pending = null; post(true); }, EVERY_MS - (now - lastPost));
      return;
    }
    if (pending) { clearTimeout(pending); pending = null; }
    lastPost = now;
    try { ch.postMessage(JSON.stringify(state(active))); } catch (e) {}
  }

  const isVideo = e => e.target && e.target.tagName === 'VIDEO';

  document.addEventListener('play', e => {
    if (!isVideo(e) || !big(e.target)) return;
    active = e.target;
    post(true);
  }, true);
  for (const type of ['pause', 'ended', 'ratechange', 'seeked', 'durationchange', 'loadedmetadata']) {
    document.addEventListener(type, e => { if (e.target === active) post(true); }, true);
  }
  document.addEventListener('timeupdate', e => {
    if (e.target === active && !active.paused) post(false);
  }, true);

  // When a site puts its own player in full screen, the video is stretched
  // over the whole screen above the site's buttons and menus, so only the
  // app's player shows. (Switching full screen to the bare video would need
  // a second tap: the first one is used up.)
  const COVER = 'data-jh-player';
  let coverStyle = null;
  function cover(v) {
    if (!coverStyle) {
      coverStyle = document.createElement('style');
      coverStyle.textContent = '[' + COVER + ']{position:fixed!important;left:0!important;top:0!important;' +
        'right:auto!important;bottom:auto!important;width:100vw!important;height:100vh!important;' +
        'max-width:none!important;max-height:none!important;min-width:0!important;min-height:0!important;' +
        'margin:0!important;padding:0!important;transform:none!important;z-index:2147483647!important;' +
        'background:#000!important;object-fit:contain!important;visibility:visible!important;opacity:1!important}';
    }
    (document.head || document.documentElement).appendChild(coverStyle);
    v.setAttribute(COVER, '');
  }

  function restore(v) {
    v.removeAttribute(COVER);
    if (v.__jhControls !== undefined) {
      v.controls = v.__jhControls;
      delete v.__jhControls;
    }
  }

  function onFullscreenChange() {
    if (!active) return;
    const fs = fsElement();
    if (!fs) {
      restore(active);
    } else if (fs.contains(active)) {
      if (active.__jhControls === undefined) active.__jhControls = active.controls;
      active.controls = false;
      if (fs !== active) cover(active);
    }
    post(true);
  }
  document.addEventListener('fullscreenchange', onFullscreenChange, true);
  document.addEventListener('webkitfullscreenchange', onFullscreenChange, true);

  function enter() {
    const v = active;
    if (!v) return;
    if (v.__jhControls === undefined) v.__jhControls = v.controls;
    v.controls = false;
    const req = v.requestFullscreen ? v.requestFullscreen({ navigationUI: 'hide' }) : v.webkitRequestFullscreen();
    if (req && req.catch) req.catch(() => post(true));
  }

  // One tap, sent by the app, that the page must not see: it opens full screen.
  const SWALLOWED = ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'touchstart', 'touchend'];
  const swallow = e => { if (armed) e.stopImmediatePropagation(); };
  SWALLOWED.forEach(t => window.addEventListener(t, swallow, true));
  window.addEventListener('click', e => {
    if (!armed) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    disarm();
    enter();
  }, true);

  function disarm() {
    armed = false;
    if (armTimer) { clearTimeout(armTimer); armTimer = null; }
  }

  function handle(m) {
    const v = active;
    switch (m.cmd) {
      case 'arm':
        armed = true;
        if (armTimer) clearTimeout(armTimer);
        armTimer = setTimeout(disarm, 5000);
        break;
      case 'enter': enter(); break;
      case 'exit':
        if (fsElement()) (document.exitFullscreen ? document.exitFullscreen() : document.webkitExitFullscreen());
        break;
      case 'toggle':
        if (v) { if (v.paused || v.ended) v.play().catch(() => {}); else v.pause(); }
        break;
      case 'seekBy':
        if (v) seek(v, v.currentTime + m.s);
        break;
      case 'seekTo':
        if (v) seek(v, m.t);
        break;
      case 'rate':
        if (v) v.playbackRate = m.v;
        break;
      case 'state':
        post(true);
        break;
    }
  }

  function seek(v, t) {
    const end = isFinite(v.duration) ? v.duration : t;
    v.currentTime = Math.max(0, Math.min(t, end));
  }

  function listen() {
    const ch = channel();
    if (!ch) return;
    ch.onmessage = e => { try { handle(JSON.parse(e.data)); } catch (err) {} };
  }
  listen();

  window.__jhVideo = { kindOf, handle, get active() { return active; } };
})();
