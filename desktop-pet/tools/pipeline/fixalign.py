"""Re-place walk frames by head x, and climb frames by ORB similarity to frame 1."""
import numpy as np, cv2
from PIL import Image
CW, CH, BASE = 640, 800, 780

def load(name):
    return Image.open(f'out/{name}.png')

def bbox(a):
    ys = np.where(a.max(1) > 10)[0]; xs = np.where(a.max(0) > 10)[0]
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1

# ---- walk: keep scale and feet baseline, align the head's horizontal centre
heads = []
ims = [load(f'walk_{i:02d}') for i in range(1, 9)]
for im in ims:
    a = np.array(im)[..., 3]
    x0, y0, x1, y1 = bbox(a)
    band = a[y0:y0 + int((y1 - y0) * 0.16)]
    xs = np.where(band.max(0) > 10)[0]
    heads.append((xs.min() + xs.max()) / 2)
target = CW * 0.46
for i, (im, hx) in enumerate(zip(ims, heads), start=1):
    out = Image.new('RGBA', (CW, CH), (0, 0, 0, 0))
    out.alpha_composite(im, (0, 0)) if False else None
    shifted = Image.new('RGBA', (CW, CH), (0, 0, 0, 0))
    dx = round(target - hx)
    shifted.paste(im, (dx, 0), im)
    shifted.save(f'out/walk_{i:02d}.png')
print('walk head x before', [round(h) for h in heads])

# ---- climb: similarity transform of each frame onto frame 1 (matches mostly on the head)
cl = [np.array(load(f'climb_{i:02d}')) for i in range(1, 5)]
def gray(rgba):
    a = rgba[..., 3:4] / 255.0
    rgb = rgba[..., :3] * a + 255 * (1 - a)
    return cv2.cvtColor(rgb.astype(np.uint8), cv2.COLOR_RGB2GRAY)
orb = cv2.ORB_create(4000)
k1, d1 = orb.detectAndCompute(gray(cl[0]), None)
bf = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True)
results = [cl[0]]
for i in range(1, 4):
    k2, d2 = orb.detectAndCompute(gray(cl[i]), None)
    m = sorted(bf.match(d2, d1), key=lambda x: x.distance)[:400]
    src = np.float32([k2[x.queryIdx].pt for x in m]); dst = np.float32([k1[x.trainIdx].pt for x in m])
    M, inl = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=4.0)
    sc = float(np.hypot(M[0, 0], M[1, 0]))
    ang = np.degrees(np.arctan2(M[1, 0], M[0, 0]))
    print(f'climb {i + 1}: scale {sc:.3f} rot {ang:.1f} inliers {int(inl.sum())}')
    # keep scale + translation only (no rotation)
    S = np.float32([[sc, 0, M[0, 2]], [0, sc, M[1, 2]]])
    # premultiplied warp to avoid fringes
    pre = cl[i].astype(np.float32); pre[..., :3] *= pre[..., 3:4] / 255.0
    w = cv2.warpAffine(pre, S, (CW, CH), flags=cv2.INTER_LINEAR, borderValue=(0, 0, 0, 0))
    a = w[..., 3:4] / 255.0
    w[..., :3] = np.where(a > 1e-3, w[..., :3] / np.maximum(a, 1e-3), 0)
    results.append(np.clip(w, 0, 255).astype(np.uint8))
# every frame: limbs touch the wall at x = 0
for i, r in enumerate(results, start=1):
    x0, y0, x1, y1 = bbox(r[..., 3])
    out = np.zeros_like(r)
    out[:, :CW - x0] = r[:, x0:]
    Image.fromarray(out, 'RGBA').save(f'out/climb_{i:02d}.png')
    print(f'climb_{i:02d} bbox after', bbox(out[..., 3]))
