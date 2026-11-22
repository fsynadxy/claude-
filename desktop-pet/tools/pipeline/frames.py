import json, numpy as np
from PIL import Image
from scipy import ndimage as ndi
import cut

CW, CH, BASE, UNIT = 640, 800, 780, 640   # canvas, feet baseline, standing height in px
# anim: src, layout (c,r), cells used, anchor, scale ref
#   ref: ('self', cell) -> that cell's bbox height is a standing height
#        ('borrow', anim, factor) -> reuse another anim's px-scale
CFG = {
 'walk':   dict(src='walk8', grid=(4, 2), cells=[0, 1, 2, 3, 4, 5, 6, 7], anchor='feet', ref=('self', 0, 1.0)),
 'drag':   dict(src='drag', grid=(1, 1), cells=[0], anchor='air', ref=('self', 0, 1.0)),
 'fall':   dict(src='fall', grid=(1, 1), cells=[0], anchor='air', ref=('borrow', 'poke', 1.0)),
 'land':   dict(src='land4', grid=(2, 2), cells=[0, 1, 2, 3], anchor='feet', ref=('borrow', 'wave', 1.0)),
 'sit':    dict(src='sit', grid=(2, 1), cells=[0, 1], anchor='feet', ref=('fixed', 0.429)),
 'sleep':  dict(src='sleep', grid=(2, 1), cells=[0, 1], anchor='feet', ref=('fixed', 0.429)),
 'poke':   dict(src='poke', grid=(1, 1), cells=[0], anchor='feet', ref=('self', 0, 1.0)),
 'pat':    dict(src='pat4', grid=(2, 2), cells=[0, 1, 2, 3], anchor='feet', ref=('self', 1, 1.0)),
 'read':   dict(src='read4', grid=(2, 2), cells=[0, 1, 2, 3], anchor='feet', ref=('self', 0, 1.0)),
 'tea':    dict(src='tea4', grid=(2, 2), cells=[0, 1, 2, 3], anchor='feet', ref=('self', 0, 1.0)),
 'yawn':   dict(src='yawn4', grid=(2, 2), cells=[0, 1, 2, 3], anchor='feet', ref=('self', 0, 1.0)),
 'curtsy': dict(src='curtsy4', grid=(2, 2), cells=[0, 1, 2, 3], anchor='feet', ref=('self', 0, 1.0)),
 'wave':   dict(src='wave4', grid=(2, 2), cells=[0, 1, 2, 3], anchor='feet', ref=('self', 0, 1.0)),
 'think':  dict(src='think', grid=(1, 1), cells=[0], anchor='feet', ref=('self', 0, 1.0)),
 'happy':  dict(src='happy4', grid=(2, 2), cells=[0, 1, 2, 3], anchor='feet_rel', ref=('borrow', 'wave', 1.0)),
 'angry':  dict(src='angry', grid=(1, 1), cells=[0], anchor='feet', ref=('self', 0, 1.0)),
 'shy':    dict(src='shy', grid=(1, 1), cells=[0], anchor='feet', ref=('self', 0, 1.0)),
 'sad':    dict(src='sad', grid=(1, 1), cells=[0], anchor='feet', ref=('self', 0, 1.0)),
 'dizzy':  dict(src='dizzy4', grid=(2, 2), cells=[0, 1, 2, 3], anchor='feet', ref=('self', 1, 1.0)),
 'bored':  dict(src='bored', grid=(1, 1), cells=[0], anchor='feet', ref=('borrow', 'poke', 1.0)),
 'hair':   dict(src='hair', grid=(1, 1), cells=[0], anchor='feet', ref=('self', 0, 1.0)),
 'climb':  dict(src='climb4', grid=(4, 1), cells=[0, 1, 2, 3], anchor='left', ref=('fixed', 0.429), wall=True, perframe_h=636),
 'fly':    dict(src='fly4', grid=(2, 2), cells=[0, 1, 2, 3], anchor='air', ref=('borrow', 'wave', 1.0)),
 'hang':   dict(src='hang', grid=(2, 1), cells=[0, 1], anchor='top', ref=('fixed', 0.429)),
}
ORDER = ['poke', 'read', 'wave'] + [k for k in CFG if k not in ('poke', 'read', 'wave')]

def load_cells(name, cfg):
    rgb = np.array(Image.open(f'raw/{cfg["src"]}.jpg').convert('RGB')).astype(np.float64)
    fg, a = cut.cutout(rgb)
    H, W = a.shape
    c, r = cfg['grid']
    # second pass: keep enclosed white areas inside each character's face zone (eye whites)
    protect = np.zeros((H, W), bool)
    lab0, comps0 = cut.components(a, min_px=int(H * W * 0.002))
    for cid, size, sl in comps0:
        y0, y1, x0, x1 = sl[0].start, sl[0].stop, sl[1].start, sl[1].stop
        h, w = y1 - y0, x1 - x0
        protect[y0 + int(h * 0.17): y0 + int(h * 0.38), x0 + int(w * 0.33): x0 + int(w * 0.67)] = True
    fg, a = cut.cutout(rgb, protect=protect)
    lab, comps = cut.components(a, min_px=int(H * W * 0.00004))
    cells = {i: np.zeros((H, W), bool) for i in range(c * r)}
    for cid, size, s in comps:
        cy = (s[0].start + s[0].stop) / 2; cx = (s[1].start + s[1].stop) / 2
        idx = min(int(cy / (H / r)), r - 1) * c + min(int(cx / (W / c)), c - 1)
        cells[idx][s] |= lab[s] == cid
    out = []
    for idx in cfg['cells']:
        m = cells[idx]
        x0c = (idx % c) * W / c
        ys, xs = np.where(m)
        bb = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
        al = np.where(m, a, 0)
        if cfg.get('wall'):
            # drawn wall line: leftmost long thin vertical stroke -> crop it and everything left of it
            sub = m[:, bb[0]:bb[0] + 80]
            colfill = sub.sum(0)
            tall = np.where(colfill > 0.55 * (bb[3] - bb[1]))[0]
            if len(tall):
                cutx = bb[0] + tall.max() + 3
                al[:, :cutx] = 0
                m = al > 0.05
                ys, xs = np.where(m); bb = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
        out.append(dict(fg=fg, a=al, bb=bb, cell_x0=x0c, cell_w=W / c, cell_y0=(idx // c) * H / r))
    return out

scales, frames_meta, manifest = {}, {}, {}
for name in ORDER:
    cfg = CFG[name]
    cells = load_cells(name, cfg)
    kind, *rest = cfg['ref']
    if kind == 'self':
        i, f = rest
        bb = cells[i]['bb']; scales[name] = UNIT / (bb[3] - bb[1]) * f
    elif kind == 'fixed':
        scales[name] = rest[0]
    else:
        other, f = rest; scales[name] = scales[other] * f
    s = scales[name]
    # common horizontal placement: cell-relative, then center the union of frames
    rel = []
    for fr in cells:
        x0, y0, x1, y1 = fr['bb']
        rel.append((x0 - fr['cell_x0'], y0 - fr['cell_y0'], x1 - fr['cell_x0'], y1 - fr['cell_y0']))
    ux0 = min(r[0] for r in rel); ux1 = max(r[2] for r in rel)
    uy0 = min(r[1] for r in rel); uy1 = max(r[3] for r in rel)
    files = []
    for k, (fr, rr) in enumerate(zip(cells, rel)):
        x0, y0, x1, y1 = fr['bb']
        if cfg.get('perframe_h'):
            s = cfg['perframe_h'] / (y1 - y0)
        crop_rgb = fr['fg'][y0:y1, x0:x1]; crop_a = fr['a'][y0:y1, x0:x1]
        nw, nh = max(1, round((x1 - x0) * s)), max(1, round((y1 - y0) * s))
        pre = np.dstack([crop_rgb * crop_a[..., None], crop_a * 255])
        ch = [np.array(Image.fromarray(pre[..., j].astype(np.float32), 'F').resize((nw, nh), Image.LANCZOS)) for j in range(4)]
        A = np.clip(ch[3], 0, 255) / 255.0
        RGB = np.where(A[..., None] > 1e-4, np.dstack(ch[:3]) / np.maximum(A[..., None], 1e-4), 0)
        img = Image.fromarray(np.dstack([np.clip(RGB, 0, 255), A * 255]).round().astype(np.uint8), 'RGBA')
        anchor = cfg['anchor']
        if anchor == 'left':
            px = round((rr[0] - ux0) * s)
        else:
            px = round(CW / 2 - (ux1 - ux0) * s / 2 + (rr[0] - ux0) * s)
        if anchor == 'feet':
            py = BASE - nh
        elif anchor == 'feet_rel':      # keep cell-relative heights (bounce); union bottom on baseline
            py = round(BASE - (uy1 - uy0) * s + (rr[1] - uy0) * s)
        elif anchor == 'top':
            py = round(40 + (rr[1] - uy0) * s)
        else:  # air: centered vertically with the standing figure's center
            py = round(BASE - UNIT / 2 - nh / 2)
        canvas = Image.new('RGBA', (CW, CH), (0, 0, 0, 0))
        if px < 0 or px + nw > CW or py < 0:
            print(f'  ! {name}[{k}] exceeds canvas: px={px} py={py} size={nw}x{nh}')
        canvas.alpha_composite(img, (max(px, 0) if px >= 0 else 0, max(py, 0)) ) if px >= 0 and py >= 0 else canvas.paste(img, (px, py), img)
        fn = f'{name}_{k + 1:02d}.png'
        canvas.save('out/' + fn); files.append(fn)
    manifest[name] = len(files)
    print(f'{name}: scale {s:.3f}, frames {len(files)}')

# idle family from the pixel-exact base (character height 1306 in 1024x1536, feet at 1459)
s = UNIT / 1306
for src, dst in [('idle', 'idle_01'), ('blink_half', 'blink_01'), ('blink_closed', 'blink_02'), ('talk', 'talk_01'), ('talk_half', 'talk_02')]:
    im = Image.open(f'out/{src}_full.png')
    im = im.resize((round(1024 * s), round(1536 * s)), Image.LANCZOS)
    canvas = Image.new('RGBA', (CW, CH), (0, 0, 0, 0))
    canvas.alpha_composite(im, (round(CW / 2 - im.width / 2), round(BASE - 1459 * s)))
    canvas.save(f'out/{dst}.png')
manifest.update(idle=1, blink=2, talk=2)
json.dump(manifest, open('out/manifest.json', 'w'), indent=1)
