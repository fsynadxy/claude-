"""Idle frames: base sprite + blink/talk edits registered and composited (face region only)."""
import numpy as np, cv2
from scipy import ndimage as ndi
from PIL import Image
W0 = '../work/'
base = np.array(Image.open(W0 + 'base_transparent.png')).astype(np.float64)   # 1024x1536 RGBA
ref = np.array(Image.open(W0 + 'base_reference_white.png').convert('RGB')).astype(np.float32)
H, W = ref.shape[:2]
FACE = (330, 620, 350, 600)  # x0,x1,y0,y1 canvas coords

def register(edit_path):
    ed = np.array(Image.open(edit_path).convert('RGB').resize((W, H), Image.LANCZOS)).astype(np.float32)
    g1 = cv2.GaussianBlur(cv2.cvtColor(ref, cv2.COLOR_RGB2GRAY), (5, 5), 0)
    g2 = cv2.GaussianBlur(cv2.cvtColor(ed, cv2.COLOR_RGB2GRAY), (5, 5), 0)
    warp = np.eye(2, 3, dtype=np.float32)
    crit = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 300, 1e-6)
    cc, warp = cv2.findTransformECC(g1, g2, warp, cv2.MOTION_AFFINE, crit, None, 5)
    aligned = cv2.warpAffine(ed, warp, (W, H), flags=cv2.INTER_LINEAR + cv2.WARP_INVERSE_MAP, borderValue=(255, 255, 255))
    return aligned, cc

def composite(edit_path, name):
    al, cc = register(edit_path)
    d = np.abs(al - ref).mean(2)
    d = ndi.gaussian_filter(d, 2)
    m = np.zeros_like(d, bool)
    x0, x1, y0, y1 = FACE
    m[y0:y1, x0:x1] = d[y0:y1, x0:x1] > 22
    lab, n = ndi.label(m)
    if n:
        sizes = ndi.sum(m, lab, range(1, n + 1))
        keep = [i + 1 for i in range(n) if sizes[i] > 120]
        m = np.isin(lab, keep)
    m = ndi.binary_closing(ndi.binary_dilation(m, iterations=6), iterations=4)
    soft = ndi.gaussian_filter(m.astype(float), 3)[..., None]
    out = base.copy()
    out[..., :3] = base[..., :3] * (1 - soft) + al * soft
    Image.fromarray(out.round().clip(0, 255).astype(np.uint8), 'RGBA').save(f'out/{name}_full.png')
    ys, xs = np.where(m)
    print(name, 'ecc', round(cc, 3), 'mask bbox', xs.min(), xs.max(), ys.min(), ys.max())

Image.fromarray(base.astype(np.uint8), 'RGBA').save('out/idle_full.png')
composite('raw/blink_closed.png', 'blink_closed')
composite('raw/blink_half.jpg', 'blink_half')
composite('raw/talk.jpg', 'talk')
# QA crop
crops = [Image.open(f'out/{n}_full.png').crop((320, 380, 660, 620)) for n in ('idle', 'blink_half', 'blink_closed', 'talk')]
s = Image.new('RGBA', (340 * 4 + 30, 240), (40, 40, 40, 255))
for i, c in enumerate(crops): s.alpha_composite(c, (i * 350, 0))
s.save('qa_idle.png')
