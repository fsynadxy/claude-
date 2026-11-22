"""White-background cutout for crayon-outlined art (closed dark-brown outlines)."""
import numpy as np
from scipy import ndimage as ndi

def disk(r):
    y, x = np.ogrid[-r:r+1, -r:r+1]; return (x*x + y*y) <= r*r

def cutout(rgb, protect=None, dark_thr=170, white_thr=238, ring_thr=0.6, zone_r=4, T=70.0):
    """rgb: HxWx3 float. Returns (fg_rgb, alpha) with white unblended."""
    H, W, _ = rgb.shape
    mn = rgb.min(2); mx = rgb.max(2)
    dark = mn < dark_thr
    barrier = ndi.binary_dilation(dark, disk(2))
    lab, n = ndi.label(~barrier)
    ids = np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))
    outside = np.isin(lab, ids[ids > 0])
    # enclosed white pockets (gaps between hair strands); keep pure-white eye whites via ring test
    cand = (mn >= white_thr) & ((mx - mn) < 14) & ~outside
    clab, cn = ndi.label(cand)
    holes = np.zeros_like(cand)
    if cn:
        sizes = ndi.sum(cand, clab, range(1, cn + 1))
        for i, s in enumerate(ndi.find_objects(clab), start=1):
            if sizes[i-1] < 60: continue
            comp = clab[s] == i
            pad = 5
            y0, y1 = max(s[0].start - pad, 0), min(s[0].stop + pad, H)
            x0, x1 = max(s[1].start - pad, 0), min(s[1].stop + pad, W)
            full = np.zeros((y1-y0, x1-x0), bool)
            full[s[0].start-y0:s[0].stop-y0, s[1].start-x0:s[1].stop-x0] = comp
            ring = ndi.binary_dilation(full, disk(4)) & ~full
            if (mn[y0:y1, x0:x1][ring] < dark_thr).mean() >= ring_thr:
                if protect is not None and protect[y0:y1, x0:x1][full].any(): continue
                holes[y0:y1, x0:x1] |= full
    bg = outside | holes
    zone = ndi.binary_dilation(bg, disk(zone_r))
    alpha = np.where(zone, np.clip((255.0 - mn) / T, 0, 1), 1.0)
    alpha[bg & (mn >= 246)] = 0.0
    a3 = alpha[..., None]
    with np.errstate(divide='ignore', invalid='ignore'):
        fg = np.where(a3 > 0, (rgb - (1 - a3) * 255.0) / np.maximum(a3, 1e-6), 0)
    return np.clip(fg, 0, 255), alpha

def components(alpha, min_px=150):
    solid = alpha > 0.05
    lab, n = ndi.label(solid, structure=np.ones((3, 3)))
    if not n: return lab, []
    sizes = ndi.sum(solid, lab, range(1, n + 1))
    objs = ndi.find_objects(lab)
    return lab, [(i + 1, int(sizes[i]), objs[i]) for i in range(n) if sizes[i] >= min_px]
