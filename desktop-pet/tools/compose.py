import numpy as np
from scipy import ndimage as ndi
from PIL import Image, ImageDraw

CW, CH = 1024, 1536
TARGET_H = round(CH * 0.85)   # character height = 85% of canvas
FOOT_Y = round(CH * 0.95)     # feet line = 5% from bottom

cut = Image.open('cutout_fullres.png')
bbox = cut.getbbox()
char = cut.crop(bbox)
scale = TARGET_H / char.height
nw, nh = round(char.width * scale), TARGET_H
# resize in premultiplied space to avoid dark/white fringes
arr = np.array(char).astype(np.float64)
a = arr[..., 3:4] / 255.0
pre = np.dstack([arr[..., :3] * a, a * 255.0])
pre_img = [Image.fromarray(pre[..., i].astype(np.float32), 'F').resize((nw, nh), Image.LANCZOS) for i in range(4)]
pre_s = np.dstack([np.array(p) for p in pre_img])
a_s = np.clip(pre_s[..., 3], 0, 255) / 255.0
rgb_s = np.where(a_s[..., None] > 1e-4, pre_s[..., :3] / np.maximum(a_s[..., None], 1e-4), 0)
small = np.dstack([np.clip(rgb_s, 0, 255), a_s * 255]).round().astype(np.uint8)
small = Image.fromarray(small, 'RGBA')

canvas = Image.new('RGBA', (CW, CH), (0, 0, 0, 0))
x0 = (CW - nw) // 2
y0 = FOOT_Y - nh
canvas.alpha_composite(small, (x0, y0))
canvas.save('base_transparent.png')
print('scale', round(scale, 4), 'placed at', x0, y0, 'size', nw, nh)

white = Image.new('RGBA', (CW, CH), (255, 255, 255, 255))
white.alpha_composite(canvas)
white.convert('RGB').save('base_reference_white.png')

# sticker version: soft white border ~9px
al = np.array(canvas)[..., 3] / 255.0
solid = al > 0.35
dist = ndi.distance_transform_edt(~solid)
R = 9.0
border_a = np.clip(R + 1.0 - dist, 0, 1)   # 1 inside radius, 1px soft edge
# fill tiny interior gaps of the sticker so it reads as one shape
border_a = np.maximum(border_a, ndi.binary_fill_holes(border_a > 0.5).astype(float))
sticker = np.zeros((CH, CW, 4), np.uint8)
sticker[..., :3] = 255
sticker[..., 3] = (border_a * 255).round().astype(np.uint8)
st = Image.fromarray(sticker, 'RGBA')
st.alpha_composite(canvas)
st.save('base_sticker.png')

# fx scaled with the same factor
for src, dst in [('fx_flower_src.png', 'fx_flower.png'), ('fx_notice_src.png', 'fx_notice.png')]:
    f = Image.open(src)
    f.resize((max(1, round(f.width * scale)), max(1, round(f.height * scale))), Image.LANCZOS).save(dst)

# QA preview: transparent sprite on several backgrounds + sticker on dark
def checker(w, h, s=24):
    c = Image.new('RGBA', (w, h), (255, 255, 255, 255)); d = ImageDraw.Draw(c)
    for yy in range(0, h, s):
        for xx in range(0, w, s):
            if (xx // s + yy // s) % 2: d.rectangle([xx, yy, xx + s - 1, yy + s - 1], fill=(205, 205, 205, 255))
    return c
pw, ph = CW // 2, CH // 2
bgs = [Image.new('RGBA', (CW, CH), (32, 34, 40, 255)), Image.new('RGBA', (CW, CH), (58, 110, 165, 255)),
       checker(CW, CH), Image.new('RGBA', (CW, CH), (32, 34, 40, 255))]
sprites = [canvas, canvas, canvas, st]
sheet = Image.new('RGBA', (pw * 4, ph), (255, 255, 255, 255))
for i, (b, s) in enumerate(zip(bgs, sprites)):
    b = b.copy(); b.alpha_composite(s)
    sheet.paste(b.resize((pw, ph), Image.LANCZOS), (i * pw, 0))
sheet.convert('RGB').save('qa_preview.png')
# full-res zoom on dark for edge check (hair left side + hand)
z = Image.new('RGBA', (CW, CH), (32, 34, 40, 255)); z.alpha_composite(canvas)
z.crop((60, 350, 460, 950)).save('qa_zoom_left.png')
z.crop((620, 550, 1000, 1100)).save('qa_zoom_right.png')
