"""Scale each landing frame so its head matches the idle frame's head (ORB similarity); feet on the baseline."""
import numpy as np, cv2
from PIL import Image
from fixalign2 import bbox, gray, warp, orb, bf, CW
BASE = 780
ref = np.array(Image.open('out/idle_01.png'))
kr, dr = orb.detectAndCompute(gray(ref), None)
for i in range(1, 5):
    f = np.array(Image.open(f'out/land_{i:02d}.png'))
    k, d = orb.detectAndCompute(gray(f), None)
    m = sorted(bf.match(d, dr), key=lambda x: x.distance)[:400]
    M, inl = cv2.estimateAffinePartial2D(np.float32([k[x.queryIdx].pt for x in m]), np.float32([kr[x.trainIdx].pt for x in m]),
                                         method=cv2.RANSAC, ransacReprojThreshold=4.0)
    sc = float(np.hypot(M[0, 0], M[1, 0])) if M is not None and inl.sum() >= 20 else 1.0
    sc = min(max(sc, 0.75), 1.35)
    x0, y0, x1, y1 = bbox(f[..., 3]); cx = (x0 + x1) / 2
    S = np.float32([[sc, 0, CW / 2 - sc * cx], [0, sc, BASE - sc * y1]])   # centre x, feet on baseline
    g = warp(f, S)
    Image.fromarray(g, 'RGBA').save(f'out/land_{i:02d}.png')
    gb = bbox(g[..., 3]); print(f'land_{i:02d}: scale {sc:.3f} inliers {int(inl.sum()) if inl is not None else 0} height {gb[3]-gb[1]}')
