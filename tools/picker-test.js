// Tests for app/src/main/assets/picker.js, run in a real Chromium page.
// Run: node tools/picker-test.js   (needs the Playwright that ships on this machine)
let pw; try { pw = require('playwright'); } catch { pw = require('playwright-core'); }
const { chromium } = pw;
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const PICKER = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/picker.js'), 'utf8');

const FIXTURE = `<!doctype html><html><head><meta name=viewport content="width=device-width"></head><body>
<div id="sidebar-ad" class="box"><p>stable id</p></div>
<div id="ad-83729183" class="box"><span class="label">random id</span></div>
<div class="promo-box"><b>unique class</b></div>
<div class="css-1x2y3z promo"><i>hashed plus real class</i></div>
<div class="Header_title__3xYz9 sc-bdVaJa">only hashed classes</div>
<ul class="list"><li>one</li><li>two</li><li>three</li></ul>
<section><div class="card"><a href="https://example.org/next" id="link">a link</a></div>
<div class="card"><p>second card</p></div></section>
<div class="dcr-1nqgird dcr-natx2v"><em>prefix plus hash</em></div>
<div data-component="most-popular" class="dcr-rw7df1"><h2>labelled section</h2></div>
<div class="sidebar"><div><div><div><span>deep</span></div></div></div></div>
<p><span>elsewhere</span></p>
<div class="dcr-idhxvk"><u>letters-only hash</u></div>
<i class="dcr-a1b2c3"></i><i class="dcr-q9w8e7"></i><i class="dcr-z1x2c3"></i><i class="dcr-p0o9i8"></i>
<div class="news-list"><a class="news-item" href="#1">one</a><a class="news-item" href="#2">two</a></div>
<iframe id="frame" style="width:300px;height:100px;border:0" srcdoc="<button id=b style='width:100%;height:90px'>inside</button><script>document.getElementById('b').onclick=()=>parent.frameClicked=1</script>"></iframe>
<div id="sp_message_container_1533645"><iframe id="sp_message_iframe_1533645" style="width:10px;height:10px;border:0"></iframe></div>
<div style="height:2000px">tall</div>
</body></html>`;

const tests = [];
const test = (name, fn) => tests.push({ name, fn });
const sel = (page, css) => page.evaluate(c => __jhPicker.selectorFor(document.querySelector(c)), css);
// Tap by screen position, like a finger: the picker covers the page while
// picking, so Playwright's element taps would refuse.
const tapOn = async (page, css) => {
  const [x, y] = await page.evaluate(c => {
    const el = document.querySelector(c);
    el.scrollIntoView({ block: 'center' });
    const r = el.getBoundingClientRect();
    return [r.left + Math.min(r.width / 2, 20), r.top + r.height / 2];
  }, css);
  await page.touchscreen.tap(x, y);
};
const matches = (page, s) => page.evaluate(s => [...document.querySelectorAll(s)].length, s);

test('a stable id is used', async page => {
  assert.strictEqual(await sel(page, '#sidebar-ad'), '#sidebar-ad');
});

test('a random-looking id is skipped', async page => {
  const s = await sel(page, '[id="ad-83729183"] .label');
  assert.ok(!s.includes('83729183'), s);
  assert.strictEqual(await matches(page, s), 1);
});

test('a unique class is used', async page => {
  assert.strictEqual(await sel(page, '.promo-box'), 'div.promo-box');
});

test('hashed classes are skipped', async page => {
  assert.strictEqual(await sel(page, '.promo'), 'div.promo');
  const s = await sel(page, '.sc-bdVaJa');
  assert.ok(!/3xYz9|sc-bdVaJa/.test(s), s);
  assert.strictEqual(await matches(page, s), 1);
});

test('prefix-plus-hash classes are skipped', async page => {
  const s = await sel(page, '.dcr-natx2v em');
  assert.ok(!s.includes('dcr-'), s);
  assert.strictEqual(await matches(page, s), 1);
});

test('a family of hashed names is skipped whole', async page => {
  const s = await sel(page, '.dcr-idhxvk u');
  assert.ok(!s.includes('dcr-'), s);
  assert.strictEqual(await matches(page, s), 1);
});

test('a family of ordinary names is kept', async page => {
  const s = await sel(page, '.news-item[href="#2"]');
  assert.ok(s.includes('news-item'), s);
});

test('a name with a changing number uses its fixed start', async page => {
  assert.strictEqual(await sel(page, '[id^="sp_message_iframe_"]'), 'iframe[id^="sp_message_iframe_"]');
});

test('stable data labels are used', async page => {
  assert.strictEqual(await sel(page, '[data-component]'), 'div[data-component="most-popular"]');
});

test('deep elements hang off the nearest named box', async page => {
  assert.strictEqual(await sel(page, '.sidebar span'), 'div.sidebar span');
});

test('nth-of-type only where siblings tie', async page => {
  const s = await sel(page, 'ul.list li:nth-child(2)');
  assert.ok(s.includes('li:nth-of-type(2)'), s);
  assert.ok(!/ul[^ ]*:nth-of-type/.test(s), s);
  assert.strictEqual(await page.evaluate(s => document.querySelector(s).textContent, s), 'two');
});

test('every element gets a selector matching only itself', async page => {
  const bad = await page.evaluate(() => {
    const out = [];
    for (const el of document.body.querySelectorAll('*')) {
      const s = __jhPicker.selectorFor(el);
      const found = [...document.querySelectorAll(s)];
      if (found.length !== 1 || found[0] !== el) out.push(s);
    }
    return out;
  });
  assert.deepStrictEqual(bad, []);
});

test('bigger and smaller walk the nesting, bigger stops at body', async page => {
  await page.evaluate(() => __jhPicker.start());
  await tapOn(page, '.promo-box b');
  assert.strictEqual(await page.evaluate(() => __jhPicker.current.tagName), 'B');
  await page.evaluate(() => __jhPicker.bigger());
  assert.strictEqual(await page.evaluate(() => __jhPicker.selector()), 'div.promo-box');
  for (let i = 0; i < 5; i++) await page.evaluate(() => __jhPicker.bigger());
  assert.strictEqual(await page.evaluate(() => __jhPicker.current.parentElement.tagName), 'BODY');
  await page.evaluate(() => __jhPicker.smaller());
  await page.evaluate(() => __jhPicker.smaller());
  assert.strictEqual(await page.evaluate(() => __jhPicker.current.tagName), 'B');
});

test('the label names the element and its size', async page => {
  await page.evaluate(() => __jhPicker.start());
  await tapOn(page, '.promo-box b');
  const label = await page.evaluate(() => __jhPicker.bigger());
  assert.match(label, /^div\.promo-box · \d+×\d+$/);
});

test('a tap on a link does not open it while picking', async page => {
  await page.evaluate(() => { window.clicked = 0; document.getElementById('link').addEventListener('click', () => window.clicked++); });
  await page.evaluate(() => __jhPicker.start());
  await tapOn(page, '#link');
  await page.waitForTimeout(300);
  assert.strictEqual(page.url(), 'about:blank');
  assert.strictEqual(await page.evaluate(() => window.clicked), 0);
  assert.strictEqual(await page.evaluate(() => __jhPicker.current.id), 'link');
});

test('the highlight follows the pick and goes away on stop', async page => {
  await page.evaluate(() => __jhPicker.start());
  await tapOn(page, '.promo-box b');
  const box = await page.evaluate(() => {
    const o = document.getElementById('__jh_pick_box').getBoundingClientRect();
    const e = __jhPicker.current.getBoundingClientRect();
    return [Math.round(o.left - e.left), Math.round(o.top - e.top), Math.round(o.width - e.width)];
  });
  assert.deepStrictEqual(box, [0, 0, 0]);
  await page.evaluate(() => __jhPicker.stop());
  assert.strictEqual(await page.evaluate(() => document.getElementById('__jh_pick_box')), null);
});

test('a tap over an embedded frame picks the whole frame', async page => {
  await page.evaluate(() => { window.frameClicked = 0; document.getElementById('frame').scrollIntoView(); });
  await page.waitForTimeout(300);
  await page.evaluate(() => __jhPicker.start());
  const r = await page.evaluate(() => { const b = document.getElementById('frame').getBoundingClientRect(); return [b.left + 50, b.top + 40]; });
  await page.touchscreen.tap(r[0], r[1]);
  await page.waitForTimeout(300);
  assert.strictEqual(await page.evaluate(() => __jhPicker.current && __jhPicker.current.id), 'frame');
  assert.strictEqual(await page.evaluate(() => window.frameClicked), 0);
  assert.strictEqual(await page.evaluate(() => __jhPicker.selector()), '#frame');
});

test('stop gives taps back to the page', async page => {
  await page.evaluate(() => { window.clicked = 0; document.getElementById('link').addEventListener('click', e => { e.preventDefault(); window.clicked++; }); });
  await page.evaluate(() => { __jhPicker.start(); __jhPicker.stop(); });
  await tapOn(page, '#link');
  assert.strictEqual(await page.evaluate(() => window.clicked), 1);
  assert.strictEqual(await page.evaluate(() => __jhPicker.selector()), '');
});

test('starting twice is harmless', async page => {
  await page.evaluate(PICKER);
  await page.evaluate(() => { __jhPicker.start(); __jhPicker.start(); });
  assert.strictEqual(await page.evaluate(() => document.querySelectorAll('#__jh_pick_box').length), 1);
});

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ hasTouch: true, isMobile: true, viewport: { width: 400, height: 800 } });
  let failed = 0;
  for (const t of tests) {
    const page = await context.newPage();
    try {
      await page.setContent(FIXTURE);
      await page.evaluate(PICKER);
      await Promise.race([t.fn(page), new Promise((_, no) => setTimeout(() => no(new Error('timed out after 10 s')), 10000))]);
      console.log('ok   ', t.name);
    } catch (e) {
      failed++;
      console.log('FAIL ', t.name, '\n      ', String(e.message).split('\n')[0]);
    }
    await page.close();
  }
  await browser.close();
  console.log(`${tests.length - failed}/${tests.length} passed`);
  process.exit(failed ? 1 : 0);
})();
