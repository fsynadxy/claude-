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

HEAD_SCALE = 0.41        # hair-top-to-chin comes out ~384 px; the reference measures ~378
CHIN = (542.0, 437.0)                       # model-space position of the chin tip (head-space 512, 1035)
HEAD_ORIGIN = (CHIN[0] - 512 * HEAD_SCALE, CHIN[1] - 1035 * HEAD_SCALE)
EYE_REL = 1.04          # eye sheet -> head space
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
# The crown of the back hair is not centred in its texture: it sits at content
# x = 572. Line it up with the dome of the fringe, a touch below its top edge.
HAIR_CX = 498                               # head-space x of the hair dome (fringe is combed off-centre)
BACK_REL = 1.9
head('hair_back', HAIR_CX - 572 * BACK_REL, 100, BACK_REL, 10)

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

# ---- eyes -------------------------------------------------------------------
# The eye frame (white + lashes) stays close to the sprite sheet: slightly
# wider and a touch flatter, barely tilted. The gaze comes from the iris: big
# enough that the upper lid hides its top quarter, resting on the lower lid.
import numpy as _np
from PIL import Image as _Image

EYE_BOTTOM = 756       # head-space y of the lower lid
EYE_DX = 158           # head-space distance of each eye centre from the face axis
WHITE_SX = 1.2
WHITE_SY = 0.9
IRIS_REL = 1.06
SLANT = 2.0            # degrees the outer corner is raised
BROW_TILT = 9.0        # degrees the inner brow end is raised from the sprite's stern angle
FACE_CX = 512
eyes = {}


def _iris_axes(tex):
    """x of the iris body's centre and of the pupil, in content px."""
    im = _np.array(_Image.open(os.path.join(ROOT, 'model', 'textures', tex + '.png')))
    pad = P[tex]['pad']
    im = im[pad:-pad, pad:-pad]
    h, w = im.shape[:2]
    row = _np.nonzero(im[int(h * 0.7), :, 3] > 128)[0]
    body = (row.min() + row.max()) / 2.0
    band = im[int(h * 0.38):int(h * 0.58), int(w * 0.3):int(w * 0.7), :3].astype(float).sum(-1).mean(0)
    k = _np.convolve(band, _np.ones(7) / 7, mode='valid')     # 'same' would zero-pad and fake a dark edge
    pupil = int(w * 0.3) + 3 + float(_np.argmin(k))
    return body, pupil


def eye(side):
    L = side == 'l'            # character's left = viewer's right
    w_white = cw(f'eye_{side}_white') * WHITE_SX
    h_white = ch(f'eye_{side}_white') * WHITE_SY
    cx = FACE_CX + (EYE_DX if L else -EYE_DX)
    ox = cx - w_white * EYE_REL / 2
    oy = EYE_BOTTOM - h_white * EYE_REL

    def put(name, lx, ly, z, rel=1.0, sx=1.0, sy=1.0, mirror=True, **kw):
        """lx, ly: eye-sheet px from the white's top-left, written for the
        viewer's-right eye and mirrored for the other one."""
        w = cw(name) * rel * sx
        if not L and mirror:
            lx = w_white - lx - w
        extra = {}
        if sx != 1.0:
            extra['sx'] = sx
        if sy != 1.0:
            extra['sy'] = sy
        head(name, ox + lx * EYE_REL, oy + ly * EYE_REL, EYE_REL * rel, z, **extra, **kw)

    p = f'eye_{side}_'
    body, pupil = _iris_axes(p + 'iris')
    iris_h = ch(p + 'iris') * IRIS_REL
    inner = -2 if L else 2                                   # both irises sit a hair toward the nose
    iris_x = w_white / 2 - body * IRIS_REL + inner           # true x: iris sprites are not mirrored
    iris_y = h_white - iris_h + 3
    put(p + 'white', 0, 0, 110, sx=WHITE_SX, sy=WHITE_SY)
    put(p + 'iris', iris_x, iris_y, 112, rel=IRIS_REL, mirror=False)
    # one small upright catch-light at the top of the pupil, same side in both eyes
    hl_rel, hl_sx = 0.3, 0.65
    put(p + 'hl', iris_x + pupil * IRIS_REL - cw(p + 'hl') * hl_rel * hl_sx / 2 + 3, 12, 114,
        rel=hl_rel, sx=hl_sx, mirror=False)
    put(p + 'lower', w_white / 2 + 6, h_white - 12, 116, sx=0.62, opacity=0.32)
    put(p + 'lash', -7, -14, 118, sx=1.2, sy=0.95)
    put(p + 'crease', w_white / 2 - cw(p + 'crease') * 2.0 / 2 + 10, -30, 119, sx=2.0, sy=0.75, opacity=0.5)
    put(p + 'closed', 4, h_white - ch(p + 'closed') * 1.35 - 2, 120, sx=1.15, sy=1.35, hidden=True)
    put('brow_' + side, 22, -76, 122, sx=1.1, opacity=0.85)
    eyes[side] = {
        'pivot': [round(hx(cx), 2), round(hy(oy + h_white * EYE_REL / 2), 2)],
        'slant': -SLANT if L else SLANT,
        'browTilt': BROW_TILT,
    }


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
head('hair_front', 512 - cw('hair_front') * 1.14 / 2 - 26, 98, 1.14, 150)
head('acc_earring', 826 - 21 * 1.23, 838, 1.23, 160)
# flower: centre just inside the hair silhouette so it reads as pinned on
FLOWER_REL = 1.58
head('acc_hair_flower', 862 - 85 * FLOWER_REL, 366 - 95 * FLOWER_REL, FLOWER_REL, 170)

layout = {
    'canvas': [1024, 1536],
    'head': {'scale': HEAD_SCALE, 'origin': list(HEAD_ORIGIN)},
    'pivots': {
        'neck': [round(hx(522), 2), round(hy(1075), 2)],
        'hip': [540, 1000],
        'shoulderY': 560,
        'faceCenter': [round(hx(512), 2), round(hy(690), 2)],
    },
    'eyes': eyes,
    'parts': parts,
}
json.dump(layout, open(os.path.join(ROOT, 'model', 'layout.json'), 'w'), indent=1)
print(len(parts), 'parts laid out')
