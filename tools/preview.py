#!/usr/bin/env python3
"""Static rest-pose compositor used while laying the parts out.

Usage: python3 tools/preview.py out.png [--tilt DEG] [--box x0,y0,x1,y1] [--zoom Z] [--ref]
"""
import argparse
import json
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_layout():
    with open(os.path.join(ROOT, 'model', 'layout.json')) as f:
        return json.load(f)


def compose(tilt=0.0, only=None, bg=(255, 255, 255, 255), ss=2):
    lay = load_layout()
    W, H = lay['canvas']
    canvas = Image.new('RGBA', (W * ss, H * ss), bg)
    head = Image.new('RGBA', (W * ss, H * ss), (0, 0, 0, 0))
    parts = sorted(lay['parts'], key=lambda p: p['z'])
    px, py = lay['pivots']['neck']

    def draw(target, p):
        im = Image.open(os.path.join(ROOT, 'model', 'textures', p['tex'] + '.png')).convert('RGBA')
        if p.get('flip'):
            im = im.transpose(Image.FLIP_LEFT_RIGHT)
        sx = p['s'] * p.get('sx', 1.0) * ss
        sy = p['s'] * p.get('sy', 1.0) * ss
        w, h = max(1, round(im.width * sx)), max(1, round(im.height * sy))
        im = im.resize((w, h), Image.LANCZOS)
        if p.get('rot'):
            im = im.rotate(-p['rot'], resample=Image.BICUBIC, expand=True)
            cx, cy = p['x'] * ss + w / 2, p['y'] * ss + h / 2
            pos = (round(cx - im.width / 2), round(cy - im.height / 2))
        else:
            pos = (round(p['x'] * ss), round(p['y'] * ss))
        if p.get('opacity', 1) < 1:
            a = np.array(im); a[..., 3] = (a[..., 3] * p['opacity']).astype(np.uint8); im = Image.fromarray(a)
        layer = Image.new('RGBA', target.size, (0, 0, 0, 0))
        layer.paste(im, pos)
        target.alpha_composite(layer)

    # draw strictly in z order; head-group parts are rotated about the neck
    for p in parts:
        if p.get('hidden') or (only and p['id'] not in only and p.get('group') not in only):
            continue
        if p.get('group') == 'head' and tilt:
            tmp = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
            draw(tmp, p)
            tmp = tmp.rotate(-tilt, resample=Image.BICUBIC, center=(px * ss, py * ss))
            canvas.alpha_composite(tmp)
        else:
            draw(canvas, p)
    return canvas.resize((W, H), Image.LANCZOS) if ss != 1 else canvas


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('out')
    ap.add_argument('--tilt', type=float, default=0)
    ap.add_argument('--box', default=None)
    ap.add_argument('--zoom', type=float, default=1)
    ap.add_argument('--ref', action='store_true')
    ap.add_argument('--only', default=None)
    ap.add_argument('--bg', default='255,255,255')
    a = ap.parse_args()
    bg = tuple(int(v) for v in a.bg.split(',')) + (255,)
    im = compose(a.tilt, a.only.split(',') if a.only else None, bg).convert('RGB')
    if a.ref:
        ref = Image.open(os.path.join(ROOT, 'assets', 'source', '00_reference.png')).convert('RGB')
        both = Image.new('RGB', (im.width * 2 + 8, im.height), (0, 0, 0))
        both.paste(ref, (0, 0)); both.paste(im, (im.width + 8, 0))
        im = both
    if a.box:
        im = im.crop(tuple(int(v) for v in a.box.split(',')))
    if a.zoom != 1:
        im = im.resize((round(im.width * a.zoom), round(im.height * a.zoom)), Image.LANCZOS)
    im.save(a.out)
