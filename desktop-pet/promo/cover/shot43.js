const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1440, height: 1080 }, deviceScaleFactor: 1 });
  await p.goto('http://127.0.0.1:8765/cover43.html');
  await p.evaluate(() => document.fonts.ready);
  await p.waitForTimeout(800);
  await p.locator('.c').screenshot({ path: process.argv[2], type: 'jpeg', quality: 92 });
  await b.close();
})();
