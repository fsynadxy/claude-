#!/usr/bin/env python3
"""Rest-pose layout: where every texture sits in model space.

Model space is the 1024x1536 frame of the reference illustration.
Head parts are authored in "head space" (pixel grid of 04_head.png) and mapped
into model space with HEAD_SCALE / HEAD_ORIGIN.

Writes model/layout.json (consumed by tools/preview.py and the runtime).
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
P = json.load(open(os.path.join(ROOT, 'model', 'parts.json')))

HEAD_SCALE = 0.405
HEAD_ORIGIN = (334.6, 17.8)
EYE_REL = 1.0           # eye sheet -> head space
MOUTH_REL = 0.47        # mouth sheet -> head space

parts = []


def add(pid, x, y, s, z, group, tex=None, **kw):
    """x, y = model-space position of the texture's content top-left."""
    tex = tex or pid
    pad = P[tex]['pad']
    e = {'id': pid, 'tex': tex, 'x': round(x - pad * s * kw.get('sx', 1), 3),
         'y': round(y - pad * s * kw.get('sy', 1), 3), 's': round(s, 5), 'z': z, 'group': group}
    e.update(kw)
    parts.append(e)
    return e


def cw(tex):
    return P[tex]['w'] - 2 * P[tex]['pad']


def ch(tex):
    return P[tex]['h'] - 2 * P[tex]['pad']


def hx(x):
    return HEAD_ORIGIN[0] + HEAD_SCALE * x


def hy(y):
    return HEAD_ORIGIN[1] + HEAD_SCALE * y


def head(pid, x, y, rel, z, **kw):
    """content top-left at head-space (x, y), scale `rel` relative to head sheet."""
    return add(pid, hx(x), hy(y), HEAD_SCALE * rel, z, 'head', **kw)


def head_c(pid, cx, cy, rel, z, **kw):
    tex = kw.get('tex', pid)
    return head(pid, cx - cw(tex) * rel * kw.get('sx', 1) / 2, cy - ch(tex) * rel * kw.get('sy', 1) / 2, rel, z, **kw)


# ---------------------------------------------------------------- hair (back)
add('hair_back', 542 - cw('hair_back') / 2 * 0.79 - 6, 50, 0.79, 10, 'head')

# ---------------------------------------------------------------- body
add('arm_r', 112, 610, 0.9, 20, 'body')
add('arm_l', 540 + (540 - 112) - cw('arm_l') * 0.9, 610, 0.9, 21, 'body')
add('neck', hx(218), hy(777), HEAD_SCALE, 30, 'body')
add('skirt', 540 - cw('skirt') * 0.86 / 2, 968, 0.86, 40, 'body')
add('dress', 130, 449, 0.8, 50, 'body')
add('acc_sash', 584, 984, 0.8, 60, 'body')
add('acc_chain', 404, 978, 0.79, 62, 'body')
add('acc_chest_bow', 540 - 179 * 0.63, 540 - 90 * 0.63, 0.63, 70, 'body')

# ---------------------------------------------------------------- head
head('face', 181, 272, 1.0, 100)
head_c('blush_r', 330, 800, 1.0, 105, tex='blush', opacity=0.0)
head_c('blush_l', 694, 800, 1.0, 105, tex='blush', opacity=0.0)

EYE_Y = 672            # head-space y of the eye-white top edge
EYE_DX = 142           # head-space distance of eye centre from the face axis
FACE_CX = 512


def eye(side):
    L = side == 'l'            # character's left = viewer's right
    w_white = cw(f'eye_{side}_white')
    cx = FACE_CX + (EYE_DX if L else -EYE_DX)
    ox = cx - w_white * EYE_REL / 2
    oy = EYE_Y

    def put(name, lx, ly, z, rel=1.0, **kw):
        tex = name
        w = cw(tex) * rel
        if not L:
            lx = w_white - lx - w
        head(name, ox + lx * EYE_REL, oy + ly * EYE_REL, EYE_REL * rel, z, **kw)

    p = f'eye_{side}_'
    put(p + 'white', 0, 0, 110)
    put(p + 'iris', 20 if L else 16, 0, 112)
    put(p + 'hl', 40 if L else 80, 10, 114, rel=0.62)
    put(p + 'lower', 24, 74, 116)
    put(p + 'lash', -16, -15, 118)
    put(p + 'crease', 16, -34, 119)
    put(p + 'closed', -4, 52, 120, hidden=True)
    put('brow_' + side, 14, -70, 122)


eye('l')
eye('r')

head_c('nose', 512, 808, 0.74, 124)
MOUTH = (512, 902)
for m, dy in [('mouth_closed', 0), ('mouth_smile', 2), ('mouth_sad', -2), ('mouth_grin', 4),
              ('mouth_open_s', 6), ('mouth_open_m', 14), ('mouth_open_l', 20),
              ('mouth_smile_open', 10), ('mouth_o', 8), ('mouth_tongue_out', 14)]:
    head_c(m, MOUTH[0], MOUTH[1] + dy, MOUTH_REL, 126, hidden=(m != 'mouth_closed'))

head('hair_side_r', 250 - 203 * 1.22, 505, 1.22, 140)
head('hair_side_l', 774 - 52 * 1.22, 505, 1.22, 141)
head('hair_front', 512 - cw('hair_front') * 1.14 / 2 - 34, 98, 1.14, 150)
add('acc_earring', hx(826) - 21 * 0.5, hy(838), 0.5, 160, 'head')
add('acc_hair_flower', 722 - 85 * 0.64, 172 - 95 * 0.64, 0.64, 170, 'head')

layout = {
    'canvas': [1024, 1536],
    'head': {'scale': HEAD_SCALE, 'origin': list(HEAD_ORIGIN)},
    'pivots': {
        'neck': [round(hx(522), 2), round(hy(1075), 2)],
        'hip': [540, 1000],
        'faceCenter': [round(hx(512), 2), round(hy(690), 2)],
    },
    'parts': parts,
}
json.dump(layout, open(os.path.join(ROOT, 'model', 'layout.json'), 'w'), indent=1)
print(len(parts), 'parts laid out')
