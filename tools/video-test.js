// Tests for app/src/main/assets/video.js, run in a real Chromium page.
// Run: node tools/video-test.js   (needs Playwright and ffmpeg on this machine)
let pw; try { pw = require('playwright'); } catch { pw = require('playwright-core'); }
const { chromium } = pw;
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const assert = require('assert');
const { execFileSync } = require('child_process');

const VIDEO_JS = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/video.js'), 'utf8');

// A 30 s test clip, made fresh so no binary lives in the repo
const clip = path.join(os.tmpdir(), 'jh-video-test.webm');
if (!fs.existsSync(clip)) {
  execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc=size=640x360:rate=25',
    '-t', '30', '-c:v', 'libvpx', '-b:v', '200k', '-an', clip]);
}
const CLIP = fs.readFileSync(clip);

const PAGE = `<!doctype html><meta name=viewport content="width=device-width">
<body style="margin:0"><div id=player><video id=v src="/clip.webm" muted playsinline style="width:360px;height:203px"></video>
<button id=fsbtn style="width:120px;height:40px" onclick="document.getElementById('player').requestFullscreen()">site full screen</button></div>
<video id=tiny src="/clip.webm" muted playsinline style="width:64px;height:64px"></video>
<div style="height:1500px"></div></body>`;

// Plain HTTP with byte ranges, so the browser can seek
const server = http.createServer((req, res) => {
  if (req.url.startsWith('/clip.webm')) {
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    if (m) {
      const start = +m[1], end = m[2] ? +m[2] : CLIP.length - 1;
      res.writeHead(206, { 'Content-Type': 'video/webm', 'Accept-Ranges': 'bytes',
        'Content-Range': `bytes ${start}-${end}/${CLIP.length}`, 'Content-Length': end - start + 1 });
      res.end(CLIP.subarray(start, end + 1));
    } else {
      res.writeHead(200, { 'Content-Type': 'video/webm', 'Accept-Ranges': 'bytes', 'Content-Length': CLIP.length });
      res.end(CLIP);
    }
    return;
  }
  res.writeHead(200, { 'Content-Type': 'text/html' });
  res.end(PAGE);
});

// Stand-in for the app's channel: what the page posts lands in window.__posts
const CHANNEL = `window.__posts = []; window.jhVideo = { postMessage: m => window.__posts.push(JSON.parse(m)), onmessage: null };`;
const send = (page, cmd) => page.evaluate(c => window.jhVideo.onmessage({ data: JSON.stringify(c) }), cmd);
const lastState = page => page.evaluate(() => window.__posts.filter(p => p.type === 'state').pop());
const play = (page, id = 'v') => page.evaluate(id => document.getElementById(id).play(), id);
const tapOn = async (page, css) => {
  const [x, y] = await page.evaluate(c => { const r = document.querySelector(c).getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; }, css);
  await page.touchscreen.tap(x, y);
};

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('a playing video file is reported with its address', async page => {
  await play(page);
  await page.waitForTimeout(800);
  const s = await lastState(page);
  assert.ok(s, 'no state posted');
  assert.strictEqual(s.playing, true);
  assert.strictEqual(s.kind, 'file');
  assert.ok(s.src.endsWith('/clip.webm'), s.src);
  assert.ok(Math.abs(s.duration - 30) < 1, String(s.duration));
  assert.ok(s.w > 300 && s.h > 150, `${s.w}x${s.h}`);
});

test('streamed video is told apart from files', async page => {
  const kinds = await page.evaluate(() => {
    const k = src => __jhVideo.kindOf(src);
    return [k('blob:https://x.org/abc'), k('https://x.org/live/master.m3u8'), k('https://x.org/a.m3u8?token=1'),
      k('https://x.org/clip.mp4'), k(''), k('data:video/mp4;base64,AAAA')];
  });
  assert.deepStrictEqual(kinds, ['stream', 'stream', 'stream', 'file', 'none', 'none']);
});

test('tiny videos are ignored', async page => {
  await play(page, 'tiny');
  await page.waitForTimeout(800);
  assert.strictEqual(await lastState(page), undefined);
});

test('nothing is posted while no video plays', async page => {
  await page.waitForTimeout(1200);
  assert.strictEqual(await page.evaluate(() => window.__posts.length), 0);
});

test('seek moves by ten seconds and stays inside the video', async page => {
  await play(page);
  await page.waitForTimeout(300);
  await page.evaluate(() => { document.getElementById('v').currentTime = 5; });
  await send(page, { cmd: 'seekBy', s: 10 });
  assert.ok(Math.abs(await page.evaluate(() => document.getElementById('v').currentTime) - 15) < 0.6);
  await send(page, { cmd: 'seekBy', s: -60 });
  assert.ok(await page.evaluate(() => document.getElementById('v').currentTime) < 0.6);
  await send(page, { cmd: 'seekTo', t: 999 });
  assert.ok(await page.evaluate(() => document.getElementById('v').currentTime) <= 30);
});

test('speed and play/pause commands work', async page => {
  await play(page);
  await page.waitForTimeout(300);
  await send(page, { cmd: 'rate', v: 1.5 });
  assert.strictEqual(await page.evaluate(() => document.getElementById('v').playbackRate), 1.5);
  await send(page, { cmd: 'toggle' });
  assert.strictEqual(await page.evaluate(() => document.getElementById('v').paused), true);
  await send(page, { cmd: 'toggle' });
  await page.waitForTimeout(200);
  assert.strictEqual(await page.evaluate(() => document.getElementById('v').paused), false);
});

test('an armed tap opens full screen and does not reach the page', async page => {
  await page.evaluate(() => { window.clicks = 0; document.addEventListener('click', () => window.clicks++); });
  await play(page);
  await page.waitForTimeout(300);
  await send(page, { cmd: 'arm' });
  await tapOn(page, '#v');
  await page.waitForTimeout(500);
  assert.strictEqual(await page.evaluate(() => document.fullscreenElement && document.fullscreenElement.id), 'v');
  assert.strictEqual(await page.evaluate(() => window.clicks), 0);
  assert.strictEqual((await lastState(page)).fullscreen, true);
});

test('taps reach the page when not armed', async page => {
  await page.evaluate(() => { window.clicks = 0; document.addEventListener('click', () => window.clicks++); });
  await tapOn(page, '#v');
  assert.strictEqual(await page.evaluate(() => window.clicks), 1);
  assert.strictEqual(await page.evaluate(() => document.fullscreenElement), null);
});

test('the exit command leaves full screen', async page => {
  await play(page);
  await page.waitForTimeout(300);
  await send(page, { cmd: 'arm' });
  await tapOn(page, '#v');
  await page.waitForTimeout(400);
  await send(page, { cmd: 'exit' });
  await page.waitForTimeout(400);
  assert.strictEqual(await page.evaluate(() => document.fullscreenElement), null);
  assert.strictEqual((await lastState(page)).fullscreen, false);
});

test("the site's own full screen shows only the video, covering the site's buttons", async page => {
  await page.evaluate(() => { document.getElementById('v').controls = true; });
  await play(page);
  await page.waitForTimeout(300);
  const btn = await page.evaluate(() => { const r = document.getElementById('fsbtn').getBoundingClientRect(); return [r.left + 10, r.top + 10]; });
  await tapOn(page, '#fsbtn');
  await page.waitForTimeout(800);
  const r = await page.evaluate(([bx, by]) => {
    const v = document.getElementById('v').getBoundingClientRect();
    const fb = document.getElementById('fsbtn').getBoundingClientRect();
    const top = document.elementFromPoint(fb.left + 5, fb.top + 5);
    return { full: v.left === 0 && v.top === 0 && v.width === innerWidth && v.height === innerHeight,
             covered: top && top.id, controls: document.getElementById('v').controls };
  }, btn);
  assert.deepStrictEqual(r, { full: true, covered: 'v', controls: false });
  assert.strictEqual((await lastState(page)).fullscreen, true);
  await send(page, { cmd: 'exit' });
  await page.waitForTimeout(400);
  const after = await page.evaluate(() => [document.getElementById('v').controls, Math.round(document.getElementById('v').getBoundingClientRect().width)]);
  assert.deepStrictEqual(after, [true, 360], 'the page was not put back as it was');
});

test('state reports are limited to about two a second', async page => {
  await play(page);
  await page.waitForTimeout(3000);
  const n = await page.evaluate(() => window.__posts.filter(p => p.type === 'state').length);
  assert.ok(n >= 3 && n <= 9, `${n} reports in 3 s`);
});

test('pausing is reported straight away', async page => {
  await play(page);
  await page.waitForTimeout(700);
  await page.evaluate(() => document.getElementById('v').pause());
  await page.waitForTimeout(100);
  assert.strictEqual((await lastState(page)).playing, false);
});

test('loading the script twice is harmless', async page => {
  await page.evaluate(VIDEO_JS);
  await play(page);
  await page.waitForTimeout(800);
  const n = await page.evaluate(() => window.__posts.filter(p => p.type === 'state').length);
  assert.ok(n <= 3, `${n} reports in 0.8 s: listeners added twice?`);
});

(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}/`;
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const context = await browser.newContext({ hasTouch: true, isMobile: true, viewport: { width: 400, height: 800 } });
  let failed = 0;
  for (const t of tests) {
    const page = await context.newPage();
    try {
      await page.addInitScript(CHANNEL);
      await page.addInitScript(VIDEO_JS);
      await page.goto(base);
      await page.waitForFunction(() => document.getElementById('v').readyState >= 1, null, { timeout: 5000 });
      await Promise.race([t.fn(page), new Promise((_, no) => setTimeout(() => no(new Error('timed out after 15 s')), 15000))]);
      console.log('ok   ', t.name);
    } catch (e) {
      failed++;
      console.log('FAIL ', t.name, '\n      ', String(e.message).split('\n')[0]);
    }
    await page.close();
  }
  await browser.close();
  server.close();
  console.log(`${tests.length - failed}/${tests.length} passed`);
  process.exit(failed ? 1 : 0);
})();
