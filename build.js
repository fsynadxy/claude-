// Inlines src/ into one self-contained HTML page.
//   node build.js            -> dist/lo-o-ove.html            (open it directly in a browser)
//   node build.js --fragment [out.html] -> head + body content only, for hosts that wrap the page in their own skeleton
const fs = require('fs'), path = require('path');
const src = f => fs.readFileSync(path.join(__dirname, 'src', f), 'utf8');
const parts = { score: 'score.js', dsp: 'dsp.js', worker: 'worker.js', script: 'script.js', stage: 'stage.js', app: 'app.js' };
let page = src('page.html');
for (const [k, f] of Object.entries(parts)) {
  const code = src(f);
  if (/<\/script/i.test(code)) throw new Error(f + ' contains a closing script tag');
  page = page.replace('/*@' + k + '*/', () => code);
}
const [head, body] = page.split('<!--@body-->');
fs.mkdirSync(path.join(__dirname, 'dist'), { recursive: true });
const fi = process.argv.indexOf('--fragment');
if (fi >= 0) {
  const out = process.argv[fi + 1] || path.join(__dirname, 'dist', 'lo-o-ove.fragment.html');
  fs.writeFileSync(out, head + body);
  console.log(out, (head + body).length, 'bytes');
} else {
  const html = '<!doctype html>\n<html lang="zh-CN">\n<head>\n<meta charset="utf-8">\n'
    + '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
    + head.trim() + '\n</head>\n<body>\n' + body.trim() + '\n</body>\n</html>\n';
  fs.writeFileSync(path.join(__dirname, 'dist', 'lo-o-ove.html'), html);
  console.log('dist/lo-o-ove.html', html.length, 'bytes');
}
