// Jelly Holo's element picker. The app runs this in the page, then calls
// __jhPicker.start(); taps choose an element instead of reaching the page,
// bigger()/smaller() move to the surrounding box and back, selector() gives
// a CSS selector for the choice that keeps working on later visits.
(() => {
  if (window.__jhPicker) return;

  const BOX_ID = '__jh_pick_box';
  const esc = s => CSS.escape(s);

  // Names that sites generate per build or per visit: hashes, long digit
  // runs, CSS-in-JS prefixes. A rule built on them would stop matching.
  const generated = s =>
    !/^[A-Za-z_-][\w-]*$/.test(s) ||
    s.length > 40 ||
    /\d{3,}/.test(s) ||
    /^(css|sc|jsx|emotion|svelte|styled)-/i.test(s) ||
    (/\d/.test(s) && /[a-z]/.test(s) && /[A-Z]/.test(s)) ||
    /__[A-Za-z0-9]*\d[A-Za-z0-9]*$/.test(s) ||
    s.split(/[-_]+/).some(t => t.length >= 5 && /\d/.test(t) && /[a-z]/i.test(t));

  // Sites that hash names do it for a whole family ("dcr-natx2v",
  // "dcr-idhxvk", ...). Once most of a family is clearly generated, the
  // look-alike members ("dcr-idhxvk") are treated as generated too.
  const prefixOf = s => {
    const m = /^([A-Za-z]+)[-_]/.exec(s);
    return m ? m[1] : '';
  };
  let hashedFamilies = null;
  function families() {
    if (hashedFamilies) return hashedFamilies;
    const seen = new Map();
    let n = 0;
    for (const el of document.querySelectorAll('[class]')) {
      if (++n > 5000) break;
      for (const c of el.classList) {
        const p = prefixOf(c);
        if (!p) continue;
        if (!seen.has(p)) seen.set(p, new Map());
        seen.get(p).set(c, generated(c));
      }
    }
    hashedFamilies = new Set();
    for (const [p, names] of seen) {
      const hashed = [...names.values()].filter(Boolean).length;
      if (names.size >= 4 && hashed / names.size >= 0.6) hashedFamilies.add(p);
    }
    return hashedFamilies;
  }
  const usable = c => !generated(c) && !families().has(prefixOf(c));

  const goodId = el => !!el.id && !generated(el.id);
  const classes = el => [...el.classList].filter(usable).slice(0, 3);
  // Labels sites put on sections on purpose, for their own tests and stats.
  const LABELS = ['data-testid', 'data-test-id', 'data-qa', 'data-component', 'data-module', 'data-link-name'];
  const goodValue = v => !!v && v.length <= 40 && !/\d{3,}/.test(v) && !/["\\\n]/.test(v);
  const labelAttr = el => {
    for (const a of LABELS) {
      const v = el.getAttribute(a);
      if (goodValue(v)) return '[' + a + '="' + v + '"]';
    }
    return '';
  };
  const part = el =>
    el.tagName.toLowerCase() + labelAttr(el) + classes(el).map(c => '.' + esc(c)).join('');
  const isNamed = p => p.includes('.') || p.includes('[');

  const unique = (sel, el) => {
    try {
      const found = document.querySelectorAll(sel);
      return found.length === 1 && found[0] === el;
    } catch (e) {
      return false;
    }
  };

  // Walk up from the element until the selector picks it alone. A selector
  // must start at something with a name (class or id), or at body, so a bare
  // "li:nth-of-type(2)" never ends up hiding things across a whole site.
  function selectorFor(el) {
    if (!el || el.nodeType !== 1) return '';
    if (el === document.body) return 'body';
    if (el === document.documentElement) return 'html';
    if (goodId(el) && unique('#' + esc(el.id), el)) return '#' + esc(el.id);
    const chain = [];
    let node = el;
    // Shortest first: the element alone, then hung off a named box above it
    // (any depth), then the full parent-to-child path.
    const tryAt = (p, sep) => {
      if (!isNamed(p)) return '';
      if (chain.length) {
        const short = p + ' ' + chain[chain.length - 1];
        if (unique(short, el)) return short;
      }
      const full = [p, ...chain].join(sep);
      return unique(full, el) ? full : '';
    };
    while (node && node !== document.body && node !== document.documentElement) {
      let p = part(node);
      let found = tryAt(p, ' > ');
      if (found) return found;
      const parent = node.parentElement;
      if (parent) {
        const ties = [...parent.children].filter(c => c.matches(p));
        if (ties.length > 1) {
          const sameTag = [...parent.children].filter(c => c.tagName === node.tagName);
          p += ':nth-of-type(' + (sameTag.indexOf(node) + 1) + ')';
          found = tryAt(p, ' > ');
          if (found) return found;
        }
      }
      chain.unshift(p);
      node = parent;
      if (node && node !== document.body && goodId(node)) {
        const id = '#' + esc(node.id);
        const short = id + ' ' + chain[chain.length - 1];
        if (unique(short, el)) return short;
        const sel = id + ' > ' + chain.join(' > ');
        if (unique(sel, el)) return sel;
      }
    }
    return ['body', ...chain].join(' > ');
  }

  let active = false;
  let current = null;
  let smallerStack = [];
  let box = null;

  function label(el) {
    const r = el.getBoundingClientRect();
    const name = goodId(el) ? '#' + el.id : labelAttr(el) || (classes(el).length ? '.' + classes(el)[0] : '');
    return el.tagName.toLowerCase() + name + ' · ' + Math.round(r.width) + '×' + Math.round(r.height);
  }

  function draw() {
    if (!box) return;
    if (!current) {
      box.style.display = 'none';
      return;
    }
    const r = current.getBoundingClientRect();
    Object.assign(box.style, {
      display: 'block',
      left: r.left + 'px',
      top: r.top + 'px',
      width: r.width + 'px',
      height: r.height + 'px',
    });
  }

  function chose(el) {
    current = el;
    draw();
    const text = el ? label(el) : '';
    try {
      if (window.JellyHolo) window.JellyHolo.onPick(text);
    } catch (e) {}
    return text;
  }

  const swallow = e => e.stopImmediatePropagation();
  function onClick(e) {
    e.preventDefault();
    e.stopImmediatePropagation();
    const el = e.target;
    if (el && el.nodeType === 1 && el.id !== BOX_ID && el !== document.documentElement) {
      smallerStack = [];
      chose(el === document.body ? null : el);
    }
  }
  const SWALLOWED = ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'touchstart', 'touchend', 'dblclick', 'auxclick', 'contextmenu'];

  window.__jhPicker = {
    selectorFor,
    get current() {
      return current;
    },
    start() {
      if (active) return;
      active = true;
      hashedFamilies = null;
      box = document.createElement('div');
      box.id = BOX_ID;
      box.style.cssText =
        'position:fixed;display:none;box-sizing:border-box;pointer-events:none;z-index:2147483647;' +
        'border:2px solid #33B5E5;background:rgba(51,181,229,.25);margin:0;padding:0';
      document.documentElement.appendChild(box);
      window.addEventListener('click', onClick, true);
      SWALLOWED.forEach(t => window.addEventListener(t, swallow, true));
      window.addEventListener('scroll', draw, true);
      window.addEventListener('resize', draw, true);
    },
    bigger() {
      if (!current) return '';
      const parent = current.parentElement;
      if (parent && parent !== document.body && parent !== document.documentElement) {
        smallerStack.push(current);
        return chose(parent);
      }
      return label(current);
    },
    smaller() {
      if (!current) return '';
      return smallerStack.length ? chose(smallerStack.pop()) : label(current);
    },
    selector() {
      return current ? selectorFor(current) : '';
    },
    stop() {
      if (!active) return;
      active = false;
      window.removeEventListener('click', onClick, true);
      SWALLOWED.forEach(t => window.removeEventListener(t, swallow, true));
      window.removeEventListener('scroll', draw, true);
      window.removeEventListener('resize', draw, true);
      if (box) box.remove();
      box = null;
      current = null;
      smallerStack = [];
    },
  };
})();
