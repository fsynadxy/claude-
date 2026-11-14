#!/usr/bin/env python3
"""Tile stills into one contact sheet: sheet.py out.jpg cols a.jpg b.jpg ..."""
import sys
from PIL import Image
out, cols, files = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
tw = 1920 // cols; th = tw * 9 // 16
rows = (len(files) + cols - 1) // cols
S = Image.new('RGB', (cols * tw, rows * th), (60, 0, 60))
for i, f in enumerate(files):
    S.paste(Image.open(f).resize((tw, th), Image.LANCZOS), ((i % cols) * tw, (i // cols) * th))
S.save(out, quality=90)
