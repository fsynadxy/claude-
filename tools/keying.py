"""Green-screen keying tuned for flat anime art.

Alpha comes from projecting each edge pixel onto the line between the key
colour and the nearest solid foreground colour, so outlines keep their own
colour instead of picking up a green fringe.
"""
import numpy as np
from scipy import ndimage as ndi


def key_green(rgb: np.ndarray) -> np.ndarray:
    """rgb: HxWx3 uint8 on a green screen -> HxWx4 uint8 straight-alpha."""
    p = rgb[..., :3].astype(np.float32)
    h, w = p.shape[:2]
    border = np.concatenate([p[:6].reshape(-1, 3), p[-6:].reshape(-1, 3),
                             p[:, :6].reshape(-1, 3), p[:, -6:].reshape(-1, 3)])
    key = np.median(border, axis=0)
    dom = p[..., 1] - np.maximum(p[..., 0], p[..., 2])
    dom_key = key[1] - max(key[0], key[2])

    core = dom < 0.10 * dom_key          # certainly foreground
    bg = dom > 0.80 * dom_key            # certainly background
    core_in = ndi.binary_erosion(core, iterations=1)

    # nearest solid foreground colour for every pixel
    dist, (iy, ix) = ndi.distance_transform_edt(~core_in, return_indices=True)
    fg = p[iy, ix]

    # projection alpha
    fk = fg - key
    denom = np.maximum((fk * fk).sum(-1), 1.0)
    a_proj = ((p - key) * fk).sum(-1) / denom
    # fallback alpha (no reliable neighbour): linear in green dominance
    a_lin = 1.0 - dom / dom_key
    near = dist <= 3.5
    alpha = np.where(near, a_proj, a_lin)
    alpha = np.clip(alpha, 0.0, 1.0)

    # colour: solid pixels keep theirs, edge pixels borrow the neighbour's,
    # far edge pixels get un-mixed
    a_safe = np.maximum(alpha, 0.05)[..., None]
    unmix = (p - (1.0 - a_safe) * key) / a_safe
    col = np.where(near[..., None], fg, unmix)
    col = np.where(core[..., None], p, col)
    # despill: nothing in this artwork is legitimately green-dominant
    mx = np.maximum(col[..., 0], col[..., 2])
    col[..., 1] = np.minimum(col[..., 1], mx)
    col = np.clip(col, 0, 255)

    alpha[core] = 1.0
    alpha[bg] = 0.0

    # Solid rim pixels can still carry a little screen colour: an outline that
    # is 85% ink and 15% green passes as solid but looks olive. Within a few
    # pixels of the edge, cap green at the proportion the fill colour further
    # in has (relative to red), which leaves real ink and fill untouched.
    deep = ndi.binary_erosion(core, iterations=3)
    if deep.any():
        ddist, (jy, jx) = ndi.distance_transform_edt(~deep, return_indices=True)
        inner = p[jy, jx]
        ratio = inner[..., 1] / np.maximum(inner[..., 0], 1.0)
        rim = core & ~deep & (ddist <= 6.0)
        cap = col[..., 0] * np.minimum(ratio, 1.0) * 1.05
        col[..., 1] = np.where(rim, np.minimum(col[..., 1], cap), col[..., 1])
    alpha[alpha < 0.04] = 0.0
    # kill isolated specks of noise far from any real foreground
    solid = ndi.binary_dilation(core_in, iterations=4)
    alpha[~solid] = 0.0

    out = np.dstack([col, alpha * 255.0]).round().astype(np.uint8)
    return out


def bleed(rgba: np.ndarray, iterations: int = 0) -> np.ndarray:
    """Fill fully transparent pixels with the nearest visible colour."""
    a = rgba[..., 3] > 0
    if a.all() or not a.any():
        return rgba
    _, (iy, ix) = ndi.distance_transform_edt(~a, return_indices=True)
    out = rgba.copy()
    out[..., :3] = rgba[iy, ix, :3]
    return out
