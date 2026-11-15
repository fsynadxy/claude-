// Renders the soundtrack offline to a 16-bit WAV, exactly as the page plays it.
//   node tools/render.js [out.wav] [sampleRate]
const fs = require('fs');
const Score = require('../src/score.js'), DSP = require('../src/dsp.js'), Script = require('../src/script.js');
const out = process.argv[2] || 'lo-o-ove.wav', sr = +(process.argv[3] || 44100);
const { L, R } = DSP.render(Score.build(Script.ticks()), Score.DUR, p => process.stdout.write('\rrendering ' + Math.round(p * 100) + '%'), { sr });
const pcm = Buffer.alloc(L.length * 4), q = x => Math.round(Math.max(-1, Math.min(1, x)) * 32767);
for (let i = 0; i < L.length; i++) { pcm.writeInt16LE(q(L[i]), i * 4); pcm.writeInt16LE(q(R[i]), i * 4 + 2); }
const h = Buffer.alloc(44);
h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVEfmt ', 8); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(2, 22);
h.writeUInt32LE(sr, 24); h.writeUInt32LE(sr * 4, 28); h.writeUInt16LE(4, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
fs.writeFileSync(out, Buffer.concat([h, pcm]));
console.log('\nwrote ' + out);
