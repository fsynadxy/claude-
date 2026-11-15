/* LO-O-OVE — worker. Renders the song in time order and streams it to the page in overlapping segments. */
self.onmessage = e => {
  const { sr, ticks } = e.data, OV = 256;
  const r = DSP.create(Score.build(ticks), Score.DUR, { sr }), ends = DSP.chunks(Score.DUR);
  let s = 0;
  ends.forEach((tEnd, i) => {
    const end = r.renderTo(tEnd), last = i === ends.length - 1, L = r.L.slice(s, end), R = r.R.slice(s, end);
    self.postMessage({ start: s, L, R, last, progress: tEnd / Score.DUR }, [L.buffer, R.buffer]);
    s = end - OV;
  });
  self.close();
};
