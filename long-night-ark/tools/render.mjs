#!/usr/bin/env node
// Renders the film frame by frame in headless Chromium, then muxes with the soundtrack.
//   node tools/render.mjs                 full film -> out/long-night-ark.mp4
//   node tools/render.mjs --still 12.5 31  stills at those seconds -> out/stills/
//   options: --from s --to s --workers n --crf n --keep
//            --frames-only  render (part of) the film into out/frames/ and stop — existing frames are kept
//            --encode-only  encode whatever is in out/frames/
import { createRequire } from 'node:module';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const { chromium } = createRequire(import.meta.url)('playwright');   // resolves a globally installed Playwright too
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);
const opt = (name, def) => { const i = argv.indexOf('--' + name); return i < 0 ? def : argv[i + 1]; };
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.jpg': 'image/jpeg', '.png': 'image/png', '.ttf': 'font/ttf', '.wav': 'audio/wav' };

const server = http.createServer((req, res) => {
  const f = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
  if (!f.startsWith(ROOT) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': MIME[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(res);
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const url = `http://127.0.0.1:${server.address().port}/src/render.html`;

const browser = await chromium.launch({ args: ['--disable-web-security', '--force-color-profile=srgb'] });
async function openPage() {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  page.on('pageerror', e => console.error('page error:', e.message));
  page.on('console', m => { if (m.type() === 'error') console.error('console:', m.text()); });
  await page.goto(url); await page.waitForFunction('window.filmReady === true', null, { timeout: 120000 });
  return page;
}
const save = (file, dataUrl) => fs.writeFileSync(file, Buffer.from(dataUrl.slice(dataUrl.indexOf(',') + 1), 'base64'));

if (argv.includes('--still')) {
  const times = argv.slice(argv.indexOf('--still') + 1).filter(a => !a.startsWith('--')).map(Number);
  const dir = path.join(ROOT, 'out', 'stills'); fs.mkdirSync(dir, { recursive: true });
  const page = await openPage();
  for (const t of times) { const f = path.join(dir, `t${t.toFixed(2).padStart(6, '0')}.jpg`); save(f, await page.evaluate(([i]) => window.renderFrame(i, 0.9), [Math.round(t * 30)])); console.log(f); }
} else {
  const info = await (await openPage()).evaluate(() => ({ fps: Film.FPS, dur: Film.DUR }));
  const from = Math.round(+opt('from', 0) * info.fps), to = Math.round(+opt('to', info.dur) * info.fps), workers = +opt('workers', 4);
  const dir = path.join(ROOT, 'out', 'frames'), framesOnly = argv.includes('--frames-only'), encodeOnly = argv.includes('--encode-only');
  if (!framesOnly && !encodeOnly) fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  let next = from, done = 0; const t0 = Date.now();
  if (!encodeOnly) await Promise.all(Array.from({ length: workers }, async () => {
    const page = await openPage();
    for (;;) { const i = next++; if (i >= to) break;
      save(path.join(dir, `f${String(i).padStart(6, '0')}.jpg`), await page.evaluate(([n]) => window.renderFrame(n, 0.96), [i]));
      if (++done % 150 === 0) console.log(`${done}/${to - from} frames  ${(done / ((Date.now() - t0) / 1000)).toFixed(1)} fps`); }
  }));
  if (framesOnly) { console.log(`rendered frames ${from}..${to - 1}`); await browser.close(); server.close(); process.exit(0); }
  const out = path.join(ROOT, 'out', opt('out', 'long-night-ark.mp4'));
  const ff = ['-y', '-framerate', String(info.fps), '-start_number', String(from), '-i', path.join(dir, 'f%06d.jpg'), '-ss', String(from / info.fps), '-t', String((to - from) / info.fps), '-i', path.join(ROOT, 'assets/audio/score.wav'),
    '-c:v', 'libx264', '-preset', 'slow', '-crf', String(opt('crf', 23)), '-pix_fmt', 'yuv420p', '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709',
    '-c:a', 'aac', '-b:a', '256k', '-af', `afade=t=out:st=${info.dur - 3 - from / info.fps}:d=3`, '-shortest', '-movflags', '+faststart', out];
  const r = spawnSync('ffmpeg', ff, { stdio: ['ignore', 'ignore', 'inherit'] });
  if (r.status !== 0) process.exitCode = 1; else console.log('wrote', out);
  if (!argv.includes('--keep') && !encodeOnly) fs.rmSync(dir, { recursive: true, force: true });
}
await browser.close(); server.close();
