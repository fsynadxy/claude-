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


def open_fringe(part, d_left=50.0, d_right=24.0):
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
        # the tips curl back in at cheek height: push them out a little further there
        tips = 42.0 * sm(500, 600, y)
        return -(d_left * sm(350, 470, y) + tips) * wl + (d_right * sm(300, 440, y) + tips) * wr

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


def strip_cape(part, outer_left=True):
    """Remove the capelet flap that the arm sheet draws on top of each sleeve.

    The dress already carries the capelet, so the copy on the arm pokes out
    from under it. Cut the flap's wing away, repaint the part of it that lay
    over the sleeve as plain fabric and redraw the sleeve outline there.
    The canvas size is kept so the layout does not move.
    """
    pad = PAD
    img = part[pad:-pad, pad:-pad].astype(np.float32)
    if not outer_left:
        img = img[:, ::-1].copy()
    h, w = img.shape[:2]
    a = img[..., 3] > 128
    rgb = img[..., :3]
    lum = rgb.sum(-1) / 3
    rows = np.arange(h)
    left = np.array([np.nonzero(a[y])[0].min() if a[y].any() else w for y in rows], np.float32)
    right_sil = np.array([np.nonzero(a[y])[0].max() if a[y].any() else 0 for y in rows], np.float32)

    yc = int(np.argmin(left[:450]))                 # outermost point of the flap
    yy = np.arange(yc + 48, yc + 100)
    slope, icpt = np.polyfit(yy, left[yy], 1)       # the sleeve's own outer edge, x = icpt + slope * y
    y_knee = yc - 25
    x_knee = icpt + slope * y_knee
    inner = float(np.median(right_sil[yc - 80:yc + 90]))   # the sleeve's inner edge is vertical

    def outer_edge(y):
        y = np.asarray(y, np.float32)
        return np.where(y >= y_knee, icpt + slope * y, x_knee + 0.16 * (y_knee - y))

    # trace the flap's hem (dark line running up and inward from its corner)
    dark = a & (lum < 115)
    hem = [(left[yc], yc)]
    x_prev, step = left[yc], 2.0
    for y in range(yc - 6, yc - 130, -6):
        guess = x_prev + step * 6
        xs = np.nonzero(dark[y, :int(inner) - 4])[0]
        xs = xs[xs > left[y] + 6]
        if len(xs) == 0:
            break
        x = xs[np.argmin(np.abs(xs - guess))]
        if abs(x - guess) > 22:
            break
        step = 0.6 * step + 0.4 * (x - x_prev) / 6
        hem.append((float(x), y))
        x_prev = x
    hx_ = np.array([p[0] for p in hem]); hy_ = np.array([p[1] for p in hem], np.float32)
    k = np.polyfit(hx_[-4:], hy_[-4:], 1)

    def hem_y(x):
        x = np.asarray(x, np.float32)
        return np.where(x <= hx_[-1], np.interp(x, hx_, hy_), k[0] * x + k[1])

    Y, X = np.mgrid[0:h, 0:w].astype(np.float32)
    top_zone = Y < yc + 48
    # 1. silhouette: nothing outside the sleeve edges above the join
    keep = np.clip((X - (outer_edge(Y) - 3.0)) / 1.5, 0, 1) * np.clip(((inner + 1.5) - X) / 1.5, 0, 1)
    alpha = img[..., 3] * np.where(top_zone, keep, 1.0)
    # make the sleeve solid all the way up (the flap used to provide these pixels)
    solid = top_zone & (X >= outer_edge(Y)) & (X <= inner)
    alpha = np.where(solid, np.maximum(alpha, 255.0 * keep), alpha)

    # 2. fabric: repaint everything that belonged to the flap
    cloth = a & (lum > 190) & (Y > yc + 60) & (Y < yc + 120) & (X > outer_edge(Y) + 25) & (X < inner - 25)
    base = np.median(rgb[cloth], axis=0)
    shade = base * np.array([0.93, 0.91, 0.94], np.float32)       # under the capelet
    d = hem_y(X) + 14.0 - Y                                        # > 0 from a little below the hem upward
    grad = np.clip(Y / max(float(hem_y(inner)), 1.0), 0, 1)[..., None]
    paint = shade * (1 - grad) + base * grad
    t = np.clip(d / 10.0, 0, 1)[..., None] * top_zone[..., None]   # hem line itself is fully repainted
    t = t * t * (3 - 2 * t)
    rgb_new = rgb * (1 - t) + paint * t

    out = np.dstack([rgb_new, alpha])
    # 3. outline along the rebuilt edges
    ss = 4
    line = Image.new('L', (w * ss, h * ss), 0)
    from PIL import ImageDraw
    dr = ImageDraw.Draw(line)
    pts = [(float(outer_edge(y)) * ss, y * ss) for y in range(0, y_knee + 24, 4)]
    dr.line(pts, fill=255, width=int(2.6 * ss), joint='curve')
    cut_rows = [y for y in range(0, yc + 40) if right_sil[y] > inner + 3 or y < 60]
    if cut_rows:
        dr.line([(inner * ss, 0), (inner * ss, (max(cut_rows) + 6) * ss)], fill=255, width=int(2.6 * ss))
    la = np.array(line.resize((w, h), Image.LANCZOS), np.float32)[..., None] / 255.0
    ink = np.array([62, 42, 48], np.float32)
    out[..., :3] = out[..., :3] * (1 - la) + ink * la
    out[..., 3] = np.maximum(out[..., 3], la[..., 0] * 255.0 * (Y < yc + 48))
    out = np.clip(out, 0, 255).round().astype(np.uint8)
    if not outer_left:
        out = out[:, ::-1]
    return np.pad(out, ((pad, pad), (pad, pad), (0, 0)))


def apply_sleeve_fill(part, panel_x, yc_hint=356):
    """Take the hand-filled upper sleeve from assets/source/12_sleeve_fill.png.

    That sheet is a 1.9656x enlargement of the two stripped arm textures laid
    side by side on blue (285 px | 8 px gap | 288 px, first 700 rows), with the
    area the capelet used to cover painted in. Rows above the old hem come
    from it; below, the sharper original sheet is kept.
    """
    fill = np.array(Image.open(os.path.join(SRC, '12_sleeve_fill.png')).convert('RGB'))
    keyed = key_green(fill[..., [0, 2, 1]])[..., [0, 2, 1, 3]].astype(np.float32)   # blue screen: swap G/B
    keyed[..., :3] *= keyed[..., 3:4] / 255.0
    small = np.array(Image.fromarray(np.clip(keyed, 0, 255).astype(np.uint8), 'RGBA')
                     .resize((581, 700), Image.LANCZOS)).astype(np.float32)
    al = small[..., 3:4]
    small[..., :3] = np.where(al > 0.5, small[..., :3] * 255.0 / np.maximum(al, 0.5), 0)
    h, w = part.shape[:2]
    patch = small[:, panel_x:panel_x + w]
    out = part.astype(np.float32)
    rows = np.arange(700, dtype=np.float32)[:, None, None]
    t = np.clip((rows - (yc_hint + 20)) / 50.0, 0, 1)
    t = t * t * (3 - 2 * t)                                    # 0 = filled sheet, 1 = original
    out[:700] = patch * (1 - t) + out[:700] * t
    return np.clip(out, 0, 255).round().astype(np.uint8)


def tight(img):
    a = img[..., 3] > 8
    ys, xs = np.nonzero(a)
    part = img[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    part = np.pad(part, ((PAD, PAD), (PAD, PAD), (0, 0)))
    return part, (xs.min(), ys.min())


def refine_iris(part):
    """Bring the sheet's iris closer to the reference illustration.

    The reference has a deep dusky upper half, a dark oval pupil and a calmer
    orange; the sheet's iris is mostly bright orange with a pale red slit.
    Only colours are touched: the dusky zone is carried further down, the
    pupil is repainted, and the whole thing is toned down a little.
    """
    pad = PAD
    img = part[pad:-pad, pad:-pad].astype(np.float32)
    h, w = img.shape[:2]
    rgb, a = img[..., :3], img[..., 3]
    Y, X = np.mgrid[0:h, 0:w].astype(np.float32)
    lum = rgb.sum(-1) / 3
    glint = lum > 232                                   # painted highlights stay white

    # the pupil sits on the vertical line art in the middle of the sprite
    mid = (a > 200) & (np.abs(X - w * 0.53) < w * 0.12) & (Y > h * 0.2) & (Y < h * 0.75)
    slit = mid & (rgb[..., 0] > 150) & (rgb[..., 0] < 222) & (rgb[..., 1] < 100)
    slit = ndi.binary_opening(slit, iterations=1)
    lab, n = ndi.label(slit)
    sizes = ndi.sum(slit, lab, range(1, n + 1))
    blob = lab == (1 + int(np.argmax(sizes)))
    ys, xs = np.nonzero(blob)
    pcx, pcy = float(xs.mean()), float(ys.mean())
    prx, pry = (xs.max() - xs.min()) / 2 + 4.0, (ys.max() - ys.min()) / 2 + 2.5

    # 1. dusky upper zone down to ~60% of the sprite, gently bowed
    u = (X - pcx) / (w * 0.37)
    edge = h * 0.61 - h * 0.045 * u * u
    t = np.clip((edge - Y) / 1.6 + 0.5, 0, 1)
    bright = np.clip((rgb[..., 0] - 196) / 24.0, 0, 1) * (a > 0)    # only the orange fill, not the outline
    k = np.clip((Y - h * 0.38) / (h * 0.24), 0, 1)[..., None]
    dusk = np.array([150, 80, 90], np.float32) * (1 - k) + np.array([172, 92, 88], np.float32) * k
    m = (t * bright * ~glint)[..., None]
    rgb = rgb * (1 - m) + dusk * m
    # nudge the existing dusky paint toward the reference's greyer plum
    upper = ((Y < edge) & ~glint & (lum > 70))[..., None]
    rgb = np.where(upper, rgb * 0.8 + np.array([152, 100, 110], np.float32) * 0.2, rgb)

    # 2. pupil: a darker, wider oval
    d = np.sqrt(((X - pcx) / prx) ** 2 + ((Y - pcy) / pry) ** 2)
    pm = np.clip((1.0 - d) * min(prx, pry) / 1.2 + 0.5, 0, 1)[..., None]
    shade = np.clip((Y - (pcy - pry)) / (2 * pry), 0, 1)[..., None]
    pupil = np.array([92, 40, 46], np.float32) * (1 - shade) + np.array([122, 56, 54], np.float32) * shade
    rgb = rgb * (1 - pm) + pupil * pm

    # 3. calmer overall: a little less saturated
    g = rgb.sum(-1, keepdims=True) / 3
    toned = rgb * 0.9 + g * 0.1
    rgb = np.where(glint[..., None], rgb, toned)

    out = np.dstack([np.clip(rgb, 0, 255), a]).round().astype(np.uint8)
    return np.pad(out, ((pad, pad), (pad, pad), (0, 0)))


def soften_inner_lash(part, inner_left=True, reach=0.2):
    """Shorten the long barbs at the nose end of the upper lash.

    In the reference the lash barely passes the inner corner; the sheet's
    lash ends in two long barbs there that read as a glare. Fade them out.
    """
    pad = PAD
    out = part.astype(np.float32)
    h, w = out.shape[:2]
    x = np.arange(w, dtype=np.float32)
    if not inner_left:
        x = (w - 1) - x
    t = np.clip((x - pad - (w - 2 * pad) * 0.03) / ((w - 2 * pad) * reach), 0, 1)
    out[..., 3] *= (t * t * (3 - 2 * t))[None, :]
    return out.round().astype(np.uint8)


def catch_light(part):
    """Turn the sheet's big sparkle into the reference's small upright dash.

    Keeps only the main blob of the highlight sprite and rotates it so its
    long axis is vertical.
    """
    pad = PAD
    img = part[pad:-pad, pad:-pad]
    a = img[..., 3] > 60
    lab, n = ndi.label(a)
    sizes = ndi.sum(a, lab, range(1, n + 1))
    blob = lab == (1 + int(np.argmax(sizes)))
    ys, xs = np.nonzero(blob)
    pts = np.stack([xs - xs.mean(), ys - ys.mean()])
    w, v = np.linalg.eigh(np.cov(pts))
    major = v[:, np.argmax(w)]
    ang = np.degrees(np.arctan2(major[0], major[1]))     # tilt of the long axis away from vertical
    one = img.copy()
    one[..., 3] = np.where(ndi.binary_dilation(blob, iterations=2), img[..., 3], 0)
    im = Image.fromarray(one[ys.min():ys.max() + 1, xs.min():xs.max() + 1], 'RGBA')
    im = im.rotate(-ang, resample=Image.BICUBIC, expand=True)
    out = np.array(im)
    out[..., :3] = 255
    ys, xs = np.nonzero(out[..., 3] > 8)
    out = out[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return np.pad(out, ((pad, pad), (pad, pad), (0, 0)))


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
    d, o = cut(E, (198, 430, 340, 545))
    put('eye_l_iris', refine_iris(d), o, E)
    d, o = cut(E, (350, 440, 420, 535), whole=True)
    put('eye_l_hl', catch_light(d), o, E)
    d, o = cut(E, (436, 425, 636, 520))
    put('eye_l_lash', soften_inner_lash(d, inner_left=True), o, E)
    simple('eye_l_crease', E, (465, 405, 540, 433))
    simple('eye_l_lower', E, (632, 490, 766, 530))
    simple('eye_l_closed', E, (826, 468, 1004, 520))
    simple('brow_l', E, (860, 400, 1010, 440))
    # row 2 = character's right (viewer's left); mirror the pieces the sheet
    # drew in the same orientation as row 1
    simple('eye_r_white', E, (15, 985, 195, 1095), flip=True)
    d, o = cut(E, (205, 1003, 342, 1112))
    put('eye_r_iris', refine_iris(d), o, E)
    d, o = cut(E, (350, 1012, 420, 1106), whole=True)
    put('eye_r_hl', catch_light(d), o, E)
    d, o = cut(E, (422, 996, 625, 1090))
    put('eye_r_lash', soften_inner_lash(d, inner_left=False), o, E)
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
    d, o = cut('09_arms', (60, 350, 400, 1400))                     # viewer's left
    put('arm_r', apply_sleeve_fill(strip_cape(d, outer_left=True), 0), o, '09_arms')
    d, o = cut('09_arms', (640, 350, 980, 1400))                    # viewer's right
    put('arm_l', apply_sleeve_fill(strip_cape(d, outer_left=False), 293), o, '09_arms')

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
