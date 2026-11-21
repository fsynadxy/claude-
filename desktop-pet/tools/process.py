import numpy as np
from scipy import ndimage as ndi
from PIL import Image

SRC = 'original.png'
im = np.array(Image.open(SRC).convert('RGB')).astype(np.float64)
H, W, _ = im.shape
mn = im.min(2)

def disk(r):
    y, x = np.ogrid[-r:r+1, -r:r+1]
    return (x*x + y*y) <= r*r

# 1) outside region: free space connected to border, outline sealed by dilation
dark = mn < 180
barrier = ndi.binary_dilation(dark, disk(2))
lab, n = ndi.label(~barrier)
border_ids = np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))
outside0 = np.isin(lab, border_ids[border_ids > 0])

# 2) enclosed background pockets between hair strands (keep eye whites in face box)
FACE = (330, 620, 280, 520)  # x0,x1,y0,y1 in source coords
cand = (mn >= 248) & ~outside0
clab, cn = ndi.label(cand)
holes = np.zeros_like(cand)
for i, s in enumerate(ndi.find_objects(clab), start=1):
    comp = clab[s] == i
    size = comp.sum()
    if size < 30:
        continue
    cy = (s[0].start + s[0].stop) / 2; cx = (s[1].start + s[1].stop) / 2
    if FACE[0] <= cx <= FACE[1] and FACE[2] <= cy <= FACE[3]:
        continue
    full = clab == i
    ring = ndi.binary_dilation(full, disk(4)) & ~full
    if (mn[ring] < 180).mean() >= 0.6:
        holes |= full

bg = outside0 | holes
# soft transition zone: grow background into the outline a little
zone = ndi.binary_dilation(bg, disk(4))

# 3) alpha: opaque inside; in the zone, fade near-white to transparent
T = 70.0
a_zone = np.clip((255.0 - mn) / T, 0, 1)
alpha = np.where(zone, a_zone, 1.0)
alpha[bg & (mn >= 250)] = 0.0

# 4) keep the main character only (drops decorative marks & specks)
solid = alpha > 0.02
slab, sn = ndi.label(solid, structure=np.ones((3, 3)))
sizes = ndi.sum(solid, slab, range(1, sn + 1))
main = slab == (np.argmax(sizes) + 1)
# fill: anything enclosed by main (and not a hole) stays part of character
alpha_main = np.where(main, alpha, 0.0)

# marks = other big components (for fx export)
marks = []
for i, s in enumerate(ndi.find_objects(slab), start=1):
    if i == np.argmax(sizes) + 1 or sizes[i-1] < 300:
        continue
    marks.append(s)

# 5) un-blend white from semi-transparent pixels (removes white halo)
a3 = alpha_main[..., None]
with np.errstate(divide='ignore', invalid='ignore'):
    fg = np.where(a3 > 0, (im - (1 - a3) * 255.0) / np.maximum(a3, 1e-6), 0)
fg = np.clip(fg, 0, 255)
rgba = np.dstack([fg, alpha_main * 255.0]).round().astype(np.uint8)
Image.fromarray(rgba, 'RGBA').save('cutout_fullres.png')

def export_fx(s, name, pad=12):
    y0, y1 = max(s[0].start - pad, 0), min(s[0].stop + pad, H)
    x0, x1 = max(s[1].start - pad, 0), min(s[1].stop + pad, W)
    sub_a = np.where(slab[y0:y1, x0:x1] > 0, alpha[y0:y1, x0:x1], 0)
    sub_a = np.where(np.isin(slab[y0:y1, x0:x1], [slab[s][slab[s] > 0][0]]), sub_a, 0)
    sa3 = sub_a[..., None]
    with np.errstate(divide='ignore', invalid='ignore'):
        f = np.where(sa3 > 0, (im[y0:y1, x0:x1] - (1 - sa3) * 255.0) / np.maximum(sa3, 1e-6), 0)
    out = np.dstack([np.clip(f, 0, 255), sub_a * 255.0]).round().astype(np.uint8)
    Image.fromarray(out, 'RGBA').save(name)

# group marks: right flower vs the two notice dashes (top-left)
dash_slices = [s for s in marks if s[1].stop < 300]
flower_slices = [s for s in marks if s[1].start > 800]
print('marks found:', [(m[1].start, m[1].stop, m[0].start, m[0].stop) for m in marks])
if flower_slices:
    export_fx(flower_slices[0], 'fx_flower_src.png')
if dash_slices:
    # merge both dashes into one image
    y0 = min(s[0].start for s in dash_slices) - 12; y1 = max(s[0].stop for s in dash_slices) + 12
    x0 = min(s[1].start for s in dash_slices) - 12; x1 = max(s[1].stop for s in dash_slices) + 12
    ids = [slab[s][slab[s] > 0][0] for s in dash_slices]
    sub_a = np.where(np.isin(slab[y0:y1, x0:x1], ids), alpha[y0:y1, x0:x1], 0)
    sa3 = sub_a[..., None]
    with np.errstate(divide='ignore', invalid='ignore'):
        f = np.where(sa3 > 0, (im[y0:y1, x0:x1] - (1 - sa3) * 255.0) / np.maximum(sa3, 1e-6), 0)
    Image.fromarray(np.dstack([np.clip(f, 0, 255), sub_a * 255.0]).round().astype(np.uint8), 'RGBA').save('fx_notice_src.png')

ys, xs = np.where(alpha_main > 0.02)
print('character bbox x', xs.min(), xs.max(), 'y', ys.min(), ys.max())
print('holes px', int(holes.sum()))
