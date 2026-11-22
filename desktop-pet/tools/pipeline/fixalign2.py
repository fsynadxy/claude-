"""4-frame sets: equalise scale inside each set (ORB similarity to frame 1) and align the lower body."""
import numpy as np, cv2, json
from PIL import Image
CW, CH = 640, 800
NO_SCALE = {'land'}   # frame 1 is a squashed crouch drawn with a bigger head
SETS = ['pat', 'wave', 'tea', 'read', 'curtsy', 'dizzy', 'yawn', 'land', 'happy', 'fly']

def bbox(a):
    ys = np.where(a.max(1) > 10)[0]; xs = np.where(a.max(0) > 10)[0]
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1

def gray(rgba):
    a = rgba[..., 3:4] / 255.0
    return cv2.cvtColor((rgba[..., :3] * a + 255 * (1 - a)).astype(np.uint8), cv2.COLOR_RGB2GRAY)

def warp(rgba, M):
    pre = rgba.astype(np.float32); pre[..., :3] *= pre[..., 3:4] / 255.0
    w = cv2.warpAffine(pre, M, (CW, CH), flags=cv2.INTER_LINEAR, borderValue=(0, 0, 0, 0))
    a = w[..., 3:4] / 255.0
    w[..., :3] = np.where(a > 1e-3, w[..., :3] / np.maximum(a, 1e-3), 0)
    return np.clip(w, 0, 255).astype(np.uint8)

orb = cv2.ORB_create(4000)
bf = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True)

if __name__ == '__main__':
    man = json.load(open('out/manifest.json'))
    for name in SETS:
        frames = [np.array(Image.open(f'out/{name}_{i:02d}.png')) for i in range(1, man[name] + 1)]
        k1, d1 = orb.detectAndCompute(gray(frames[0]), None)
        out = [frames[0]]
        for f in frames[1:]:
            k2, d2 = orb.detectAndCompute(gray(f), None)
            sc = 1.0
            if d2 is not None and d1 is not None:
                m = sorted(bf.match(d2, d1), key=lambda x: x.distance)[:400]
                if len(m) >= 25:
                    M, inl = cv2.estimateAffinePartial2D(np.float32([k2[x.queryIdx].pt for x in m]),
                                                         np.float32([k1[x.trainIdx].pt for x in m]),
                                                         method=cv2.RANSAC, ransacReprojThreshold=4.0)
                    if M is not None and inl.sum() >= 25:
                        s_ = float(np.hypot(M[0, 0], M[1, 0]))
                        if 0.85 < s_ < 1.18 and name not in NO_SCALE: sc = s_
            # scale about the bottom centre so the feet stay where they are
            x0, y0, x1, y1 = bbox(f[..., 3]); cx, by = (x0 + x1) / 2, y1
            S = np.float32([[sc, 0, cx - sc * cx], [0, sc, by - sc * by]])
            out.append(warp(f, S) if abs(sc - 1) > 0.01 else f)
            print(f'{name}: frame scale {sc:.3f}')
        # horizontal: centre of the lower 35% of each figure on one common x
        for i, f in enumerate(out, start=1):
            x0, y0, x1, y1 = bbox(f[..., 3])
            band = f[y1 - int((y1 - y0) * 0.35):y1, :, 3]
            xs = np.where(band.max(0) > 10)[0]
            dx = int(round(CW / 2 - (xs.min() + xs.max()) / 2))
            g = np.zeros_like(f)
            if dx >= 0: g[:, dx:] = f[:, :CW - dx]
            else: g[:, :CW + dx] = f[:, -dx:]
            Image.fromarray(g, 'RGBA').save(f'out/{name}_{i:02d}.png')
