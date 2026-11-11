#!/usr/bin/env python3
"""Cut the green-screen source sheets into individual model textures.

Usage: python3 tools/build_parts.py
Reads  assets/source/*.png
Writes model/textures/<part>.png (straight alpha, cropped) and model/parts.json
"""
import json
import os
import sys

import numpy as np
from PIL import Image
from scipy import ndimage as ndi

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from keying import key_green, bleed  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'assets', 'source')
OUT = os.path.join(ROOT, 'model', 'textures')
PAD = 6

_cache = {}


def sheet(name):
    if name not in _cache:
        rgb = np.array(Image.open(os.path.join(SRC, name + '.png')).convert('RGB'))
        _cache[name] = key_green(rgb)
    return _cache[name]


def cut(name, box, flip=False, whole=False):
    """Crop `box` (x0, y0, x1, y1) out of a keyed sheet, tightened to content.

    Unless `whole` is set only the largest blob inside the box is kept, so
    slivers of neighbouring sprites never leak into a part.
    """
    img = sheet(name)
    x0, y0, x1, y1 = box
    part = img[y0:y1, x0:x1].copy()
    a = part[..., 3] > 8
    if not whole:
        lab, n = ndi.label(ndi.binary_dilation(a, iterations=3))
        if n > 1:
            sizes = ndi.sum(a, lab, range(1, n + 1))
            keep = lab == (1 + int(np.argmax(sizes)))
            part[..., 3] = np.where(keep, part[..., 3], 0)
            a = part[..., 3] > 8
    ys, xs = np.nonzero(a)
    part = part[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    origin = (x0 + xs.min(), y0 + ys.min())
    if flip:
        part = part[:, ::-1]
    part = np.pad(part, ((PAD, PAD), (PAD, PAD), (0, 0)))
    return part, origin


def split_head():
    """Separate the face (rotates) from the neck/shoulders (stays with body)."""
    img = sheet('04_head')
    h, w = img.shape[:2]
    a = img[..., 3] > 8
    rgb = img[..., :3].astype(np.int32)
    lum = rgb.sum(-1) / 3
    dark = a & (lum < 150) & (rgb[..., 0] < 190)       # line art
    # barrier = jaw line; everything skin-coloured above it is the face
    seed_face = (600, 512)
    seed_neck = (1150, 520)
    lab, _ = ndi.label(a & ~ndi.binary_dilation(dark, iterations=1))
    face_core = lab == lab[seed_face]
    neck_core = lab == lab[seed_neck]
    assert lab[seed_face] != lab[seed_neck], 'jaw line did not separate face and neck'
    d_face = ndi.distance_transform_edt(~face_core)
    d_neck = ndi.distance_transform_edt(~neck_core)
    face_mask = a & (d_face <= d_neck + 2.0)
    # ears are their own blobs once the outline is removed: keep them on the face
    yy = np.arange(h)[:, None]
    face_mask |= a & (yy < 900) & ~neck_core
    neck_mask = a & ~face_mask

    face = img.copy()
    face[..., 3] = np.where(face_mask, img[..., 3], 0)

    neck = img.copy()
    neck[..., 3] = np.where(neck_mask, img[..., 3], 0)
    # extend the neck upward underneath the chin so head tilts never open a gap
    ys, xs = np.nonzero(neck_mask)
    top = ys.min()
    row = top + 70
    cols = np.nonzero(neck_mask[row])[0]
    nx0, nx1 = cols.min() + 3, cols.max() - 3
    shade = np.median(img[top + 78:top + 90, 480:560, :3].reshape(-1, 3), axis=0)
    for y in range(top - 190, row):
        seg = neck[y, nx0:nx1]
        lum_row = seg[:, :3].astype(np.int32).sum(-1) / 3
        # empty pixels and the leftover jaw outline both become chin shadow
        fill = (seg[:, 3] < 250) | ((lum_row < 165) & (y < top + 45))
        seg[fill, :3] = shade
        seg[fill, 3] = 255
        if y < top:
            neck[y, nx0 - 3:nx0] = neck[row, nx0 - 3:nx0]
            neck[y, nx1:nx1 + 3] = neck[row, nx1:nx1 + 3]
    # soften the seam left where the outline used to be
    band = neck[top - 4:top + 48, nx0:nx1, :3].astype(np.float32)
    neck[top - 4:top + 48, nx0:nx1, :3] = ndi.gaussian_filter(band, sigma=(2.0, 2.0, 0)).round().astype(np.uint8)
    return face, neck


def lengthen_hair(part, y0=420, gain=0.5, widen=0.09):
    """The supplied back hair is shorter than in the reference illustration.

    Stretch it progressively below the shoulders (rows above `y0` untouched,
    bottom rows end up (1 + gain) times taller) and flare it out slightly.
    """
    import cv2
    pad = PAD
    src = part[pad:-pad, pad:-pad].astype(np.float32)
    h, w = src.shape[:2]
    a = gain / (2.0 * (h - y0))
    new_h = int(round(h + a * (h - y0) ** 2))
    new_w = int(round(w * (1 + widen)))
    Y, X = np.mgrid[0:new_h, 0:new_w].astype(np.float32)
    # invert  Y = y + a (y - y0)^2
    d = np.maximum(Y - y0, 0)
    ysrc = np.where(Y <= y0, Y, y0 + (-1 + np.sqrt(1 + 4 * a * d)) / (2 * a))
    t = np.clip((ysrc - y0) / (h - y0), 0, 1)
    cx_new, cx = new_w / 2.0, w / 2.0
    xsrc = cx + (X - cx_new) / (1 + widen * t * t)
    prem = src.copy()
    prem[..., :3] *= prem[..., 3:4] / 255.0
    out = cv2.remap(prem, xsrc.astype(np.float32), ysrc.astype(np.float32), cv2.INTER_CUBIC,
                    borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    al = np.clip(out[..., 3:4], 0, 255)
    out[..., :3] = np.where(al > 0.5, out[..., :3] * 255.0 / np.maximum(al, 0.5), 0)
    out = np.clip(out, 0, 255)
    out[..., 3:4] = al
    out = out.round().astype(np.uint8)
    return np.pad(out, ((pad, pad), (pad, pad), (0, 0)))


def open_fringe(part, d_left=40.0, d_right=17.0):
    """Widen the gap in the fringe so both eyes sit inside it.

    The supplied fringe leaves a face window narrower than the eye span of the
    reference. Slide the inner edge of each side lock outward (left more than
    right, the window is off-centre) with a smooth displacement field: zero at
    the outer silhouette, zero above the point where the window opens, and
    fading out across the transparent gap so the centre strand stays put.
    """
    import cv2
    pad = PAD
    src = part[pad:-pad, pad:-pad].astype(np.float32)
    h, w = src.shape[:2]

    def sm(a, b, x):
        t = np.clip((x - a) / (b - a), 0, 1)
        return t * t * (3 - 2 * t)

    # left edge of the centre strand, by row (where the left window ends)
    strand_y = np.array([0, 360, 390, 420, 450, 480, 510, 540, h], np.float32)
    strand_x = np.array([300, 300, 312, 325, 341, 360, 405, 440, 440], np.float32)

    def disp(x, y):
        xe = np.interp(y, strand_y, strand_x)
        inner_l, outer_l = 264.0, 60.0
        wl = np.where(x <= inner_l,
                      0.5 * (1 - np.cos(np.pi * np.clip((x - outer_l) / (inner_l - outer_l), 0, 1))),
                      0.5 * (1 + np.cos(np.pi * np.clip((x - inner_l) / np.maximum(xe - inner_l, 1), 0, 1))))
        inner_r, outer_r, strand_r = 608.0, 772.0, 490.0
        wr = np.where(x >= inner_r,
                      0.5 * (1 + np.cos(np.pi * np.clip((x - inner_r) / (outer_r - inner_r), 0, 1))),
                      0.5 * (1 - np.cos(np.pi * np.clip((x - strand_r) / (inner_r - strand_r), 0, 1))))
        return -d_left * sm(350, 470, y) * wl + d_right * sm(300, 440, y) * wr

    Y, X = np.mgrid[0:h, 0:w].astype(np.float32)
    xs = X.copy()
    for _ in range(10):                      # invert x + disp(x) = X
        xs = X - disp(xs, Y)
    prem = src.copy()
    prem[..., :3] *= prem[..., 3:4] / 255.0
    out = cv2.remap(prem, xs.astype(np.float32), Y, cv2.INTER_CUBIC, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    al = np.clip(out[..., 3:4], 0, 255)
    out[..., :3] = np.where(al > 0.5, out[..., :3] * 255.0 / np.maximum(al, 0.5), 0)
    out = np.clip(out, 0, 255)
    out[..., 3:4] = al
    return np.pad(out.round().astype(np.uint8), ((pad, pad), (pad, pad), (0, 0)))


def tight(img):
    a = img[..., 3] > 8
    ys, xs = np.nonzero(a)
    part = img[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    part = np.pad(part, ((PAD, PAD), (PAD, PAD), (0, 0)))
    return part, (xs.min(), ys.min())


def nose_sprite():
    """The sheets ship no nose; draw the small stroke seen in the reference."""
    s = 4
    w, h = 40 * s, 44 * s
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    # short slanted stroke
    x0, y0, x1, y1 = 21 * s, 10 * s, 18 * s, 32 * s
    t = np.clip(((xx - x0) * (x1 - x0) + (yy - y0) * (y1 - y0)) /
                ((x1 - x0) ** 2 + (y1 - y0) ** 2), 0, 1)
    d = np.hypot(xx - (x0 + t * (x1 - x0)), yy - (y0 + t * (y1 - y0)))
    width = (1.6 + 2.2 * np.sin(t * np.pi)) * s
    a = np.clip((width - d) / (1.2 * s), 0, 1)
    img = np.zeros((h, w, 4), np.float32)
    img[..., 0], img[..., 1], img[..., 2] = 196, 112, 108
    img[..., 3] = a * 235
    im = Image.fromarray(img.round().astype(np.uint8)).resize((40, 44), Image.LANCZOS)
    return np.array(im), (0, 0)


def blush_sprite():
    w, h = 200, 110
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    r = np.hypot((xx - w / 2) / (w / 2), (yy - h / 2) / (h / 2))
    a = np.clip(1 - r, 0, 1) ** 1.6
    img = np.zeros((h, w, 4), np.float32)
    img[..., 0], img[..., 1], img[..., 2] = 255, 138, 128
    img[..., 3] = a * 150
    return img.round().astype(np.uint8), (0, 0)


def main():
    os.makedirs(OUT, exist_ok=True)
    parts = {}

    def put(pid, data, origin=None, src=None):
        data = bleed(data)
        Image.fromarray(data).save(os.path.join(OUT, pid + '.png'), optimize=True)
        parts[pid] = {'w': int(data.shape[1]), 'h': int(data.shape[0]), 'pad': PAD}
        if src:
            parts[pid]['src'] = src
        if origin is not None:
            parts[pid]['origin'] = [int(origin[0]), int(origin[1])]

    def simple(pid, name, box, flip=False, whole=False):
        data, origin = cut(name, box, flip, whole)
        put(pid, data, origin, name)

    # ---- hair -------------------------------------------------------------
    d, o = cut('01_hair_back', (0, 0, 1216, 1294))
    put('hair_back', lengthen_hair(d), o, '01_hair_back')
    simple('hair_side_r', '02_hair_side', (100, 90, 500, 1440))     # viewer's left
    simple('hair_side_l', '02_hair_side', (600, 90, 920, 1440))     # viewer's right
    d, o = cut('03_hair_front', (100, 40, 930, 760))
    put('hair_front', open_fringe(d), o, '03_hair_front')

    # ---- head -------------------------------------------------------------
    face, neck = split_head()
    d, o = tight(face); put('face', d, o, '04_head')
    d, o = tight(neck); put('neck', d, o, '04_head')
    d, o = nose_sprite(); put('nose', d)
    d, o = blush_sprite(); put('blush', d)

    # ---- eyes: row 1 = character's left (viewer's right) -------------------
    E = '05_eyes'
    simple('eye_l_white', E, (15, 430, 195, 545))
    simple('eye_l_iris', E, (198, 430, 340, 545))
    simple('eye_l_hl', E, (350, 440, 420, 535), whole=True)
    simple('eye_l_lash', E, (436, 425, 636, 520))
    simple('eye_l_crease', E, (465, 405, 540, 433))
    simple('eye_l_lower', E, (632, 490, 766, 530))
    simple('eye_l_closed', E, (826, 468, 1004, 520))
    simple('brow_l', E, (860, 400, 1010, 440))
    # row 2 = character's right (viewer's left); mirror the pieces the sheet
    # drew in the same orientation as row 1
    simple('eye_r_white', E, (15, 985, 195, 1095), flip=True)
    simple('eye_r_iris', E, (205, 1003, 342, 1112))
    simple('eye_r_hl', E, (350, 1012, 420, 1106), whole=True)
    simple('eye_r_lash', E, (422, 996, 625, 1090))
    simple('eye_r_crease', E, (518, 974, 588, 1002))
    simple('eye_r_lower', E, (648, 1063, 784, 1100))
    simple('eye_r_closed', E, (828, 1040, 1002, 1092), flip=True)
    simple('brow_r', E, (843, 974, 1004, 1018), flip=True)

    # ---- mouth ------------------------------------------------------------
    M = '06_mouth'
    simple('mouth_sad', M, (60, 258, 275, 312))
    simple('mouth_grin', M, (370, 300, 552, 348))
    simple('mouth_cavity', M, (660, 250, 872, 382))
    simple('mouth_teeth', M, (1000, 274, 1140, 332))
    simple('mouth_tongue', M, (1292, 268, 1440, 376))
    simple('mouth_closed', M, (46, 712, 184, 744))
    simple('mouth_open_s', M, (238, 702, 370, 754))
    simple('mouth_open_l', M, (420, 678, 574, 792))
    simple('mouth_open_m', M, (620, 690, 750, 782))
    simple('mouth_tongue_out', M, (808, 698, 916, 782))
    simple('mouth_smile_open', M, (975, 695, 1112, 770))
    simple('mouth_o', M, (1180, 695, 1276, 770))
    simple('mouth_smile', M, (1334, 708, 1494, 752))

    # ---- body / clothes ---------------------------------------------------
    simple('body', '10_body', (0, 0, 1024, 1536))
    simple('dress', '07_dress', (0, 0, 1024, 1536))
    simple('skirt', '08_skirt', (0, 0, 1024, 1536))
    simple('arm_r', '09_arms', (60, 350, 400, 1400))                # viewer's left
    simple('arm_l', '09_arms', (640, 350, 980, 1400))               # viewer's right

    # ---- accessories ------------------------------------------------------
    A = '11_accessories'
    simple('acc_hair_flower', A, (626, 0, 920, 664))
    simple('acc_earring', A, (552, 262, 618, 452))
    simple('acc_chest_bow', A, (195, 420, 575, 842))
    simple('acc_sash', A, (610, 686, 840, 1510))
    simple('acc_chain', A, (260, 886, 640, 1298))
    simple('acc_bow_r', A, (0, 928, 222, 1204))
    simple('acc_bow_l', A, (828, 932, 1024, 1216))

    with open(os.path.join(ROOT, 'model', 'parts.json'), 'w') as f:
        json.dump(parts, f, indent=1)
    total = sum(os.path.getsize(os.path.join(OUT, p + '.png')) for p in parts)
    print(f'{len(parts)} parts, {total / 1e6:.2f} MB')
    for k, v in parts.items():
        print(f"  {k:18s} {v['w']:5d}x{v['h']:<5d} origin {v.get('origin')}")


if __name__ == '__main__':
    main()
