import sys
from PIL import Image, ImageDraw
names = sys.argv[2:]; out = sys.argv[1]
ims = []
for n in names:
    p = f"raw/{n}.jpg" if not n.endswith('.png') else n
    try: im = Image.open(p).convert("RGB")
    except FileNotFoundError: im = Image.open(f"raw/{n}.png").convert("RGB")
    im.thumbnail((620, 620)); ims.append((n, im))
W = sum(i.width for _, i in ims) + 10 * (len(ims) + 1); H = max(i.height for _, i in ims) + 34
s = Image.new("RGB", (W, H), (90, 90, 90)); d = ImageDraw.Draw(s); x = 10
for n, im in ims:
    s.paste(im, (x, 28)); d.text((x, 8), n, fill=(255, 255, 255)); x += im.width + 10
s.save(out)
