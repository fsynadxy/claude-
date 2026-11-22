"""Fill transparent pockets inside the face (eye whites / highlights): composite over white there."""
import numpy as np, json, sys
from PIL import Image, ImageDraw
from scipy import ndimage as ndi
man = json.load(open('out/manifest.json'))
frames = [f'{k}_{i:02d}' for k, n in man.items() for i in range(1, n + 1)]
apply = '--apply' in sys.argv
report, tiles = [], []
for f in frames:
    im = np.array(Image.open(f'out/{f}.png')).astype(np.float64)
    a = im[..., 3]; r, g, b = im[..., 0], im[..., 1], im[..., 2]
    ys = np.where(a.max(1) > 10)[0]; top, bot = ys.min(), ys.max()
    skin = (a > 230) & (r > 238) & (g > 212) & (b > 195) & (r - b > 6) & (r - b < 55)
    skin[int(top + (bot - top) * 0.55):] = False                 # faces are in the upper half
    lab, n = ndi.label(skin)
    if n == 0: continue
    sizes = ndi.sum(skin, lab, range(1, n + 1))
    face = lab == (np.argmax(sizes) + 1)
    if sizes.max() < 1500: continue
    zone = ndi.binary_fill_holes(ndi.binary_dilation(face, iterations=14))
    clear = a < 200
    plab, pn = ndi.label(clear)
    border = set(np.unique(np.concatenate([plab[0], plab[-1], plab[:, 0], plab[:, -1]]))) - {0}
    fill = np.zeros_like(clear)
    for i, s in enumerate(ndi.find_objects(plab), start=1):
        if i in border: continue
        comp = plab[s] == i
        if comp.sum() > 1500: continue
        cy, cx = [int(v.mean()) for v in np.nonzero(comp)]
        if zone[s[0].start + cy, s[1].start + cx]:
            fill[s] |= comp
    if not fill.any(): continue
    region = ndi.binary_dilation(fill, iterations=2) & (a < 255)
    report.append((f, int(fill.sum())))
    if apply:
        al = a[..., None] / 255.0
        im[..., :3] = np.where(region[..., None], im[..., :3] * al + 252 * (1 - al), im[..., :3])
        im[..., 3] = np.where(region, 255, a)
        Image.fromarray(im.round().astype(np.uint8), 'RGBA').save(f'out/{f}.png')
    ys2, xs2 = np.nonzero(fill); cy, cx = int(ys2.mean()), int(xs2.mean())
    vis = Image.open(f'out/{f}.png'); bg = Image.new('RGBA', vis.size, (255, 0, 255, 255)); bg.alpha_composite(vis)
    t = bg.crop((cx - 90, cy - 50, cx + 90, cy + 50)).resize((270, 150)); ImageDraw.Draw(t).text((2, 0), f, fill=(0, 0, 0))
    tiles.append(t)
print(report)
if tiles:
    s = Image.new('RGBA', (6 * 270, ((len(tiles) + 5) // 6) * 150), (0, 0, 0, 255))
    for j, t in enumerate(tiles): s.paste(t, ((j % 6) * 270, (j // 6) * 150))
    s.save('fixeyes_after.png' if apply else 'fixeyes_before.png')
