#!/usr/bin/env python3
"""Bundle the model into a single self-contained page.

Usage: python3 tools/build_site.py [--fragment OUT]
Writes index.html (opens straight from disk, no server needed).
--fragment also writes the head-less variant used for embedded previews.
"""
import argparse
import base64
import io
import json
import os

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src')
TEX = os.path.join(ROOT, 'model', 'textures')
CACHE = os.path.join(ROOT, 'model', '.webp-cache')
JS = ['engine.js', 'model.js', 'animator.js', 'app.js']


def read(*p):
    with open(os.path.join(*p), encoding='utf-8') as f:
        return f.read()


def webp_data_uri(tex_id):
    src = os.path.join(TEX, tex_id + '.png')
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, tex_id + '.webp')
    if not os.path.exists(cached) or os.path.getmtime(cached) < os.path.getmtime(src):
        im = Image.open(src).convert('RGBA')
        buf = io.BytesIO()
        im.save(buf, 'WEBP', quality=93, method=6, exact=True)
        with open(cached, 'wb') as f:
            f.write(buf.getvalue())
    with open(cached, 'rb') as f:
        return 'data:image/webp;base64,' + base64.b64encode(f.read()).decode('ascii')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--fragment', default=None)
    args = ap.parse_args()

    layout = json.loads(read(ROOT, 'model', 'layout.json'))
    parts = json.loads(read(ROOT, 'model', 'parts.json'))
    used = sorted({p['tex'] for p in layout['parts']})
    data = {
        'layout': layout,
        'parts': {k: {'w': parts[k]['w'], 'h': parts[k]['h'], 'pad': parts[k]['pad']} for k in used},
        'textures': {k: webp_data_uri(k) for k in used},
    }
    template = read(SRC, 'template.html')
    js = '\n'.join(read(SRC, f) for f in JS)
    assert '</script' not in js.lower()
    fragment = template.replace('/*__DATA__*/', 'window.L2D_DATA = ' + json.dumps(data, ensure_ascii=False, separators=(',', ':')) + ';')
    fragment = fragment.replace('/*__JS__*/', js)

    head, body = fragment.split('<!--BODY-->')
    full = ('<!doctype html>\n<html lang="zh-CN">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
            + head + '</head>\n<body>\n' + body + '</body>\n</html>\n')
    with open(os.path.join(ROOT, 'index.html'), 'w', encoding='utf-8') as f:
        f.write(full)
    print(f'index.html  {len(full.encode("utf-8")) / 1e6:.2f} MB  ({len(used)} textures)')
    if args.fragment:
        with open(args.fragment, 'w', encoding='utf-8') as f:
            f.write(fragment.replace('<!--BODY-->', ''))
        print('fragment   ', args.fragment)


if __name__ == '__main__':
    main()
