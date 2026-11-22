const { chromium } = require('playwright');
const { spawn } = require('child_process');
(async () => {
  const dir = process.argv[2], out = process.argv[3], fps = 30;
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await page.goto('http://127.0.0.1:8765/clau-promo.html?export');
  await page.evaluate(() => window.__promo.ready);
  const dur = await page.evaluate(() => window.__promo.duration);
  const cues = [['v0', 1400], ['v1', 15800], ['v2', 26300], ['v3', 37100], ['v4', 46500], ['v5', 59400]];
  const args = ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'mjpeg', '-i', '-'];
  cues.forEach(([k]) => args.push('-i', `${dir}/audio/${k}.wav`));
  const delays = cues.map(([k, ms], i) => `[${i + 1}:a]adelay=${ms}|${ms},aresample=44100[a${i}]`).join(';');
  const mix = cues.map((_, i) => `[a${i}]`).join('') + `amix=inputs=${cues.length}:normalize=0,apad[aout]`;
  args.push('-filter_complex', `${delays};${mix}`, '-map', '0:v', '-map', '[aout]',
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '20', '-preset', 'medium', '-r', String(fps),
    '-c:a', 'aac', '-b:a', '128k', '-shortest', '-movflags', '+faststart', out);
  const ff = spawn('ffmpeg', args, { stdio: ['pipe', 'inherit', 'inherit'] });
  const n = Math.round(dur * fps);
  for (let i = 0; i <= n; i++) {
    const data = await page.evaluate(t => { window.__promo.render(t); return document.getElementById('c').toDataURL('image/jpeg', 0.93); }, i / fps);
    const buf = Buffer.from(data.split(',')[1], 'base64');
    if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
    if (i % 300 === 0) console.log(`frame ${i}/${n}`);
  }
  ff.stdin.end();
  await new Promise(r => ff.on('close', r));
  await b.close();
  console.log('done');
})();
