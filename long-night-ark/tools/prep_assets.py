#!/usr/bin/env python3
"""Fetch the official stills + soundtrack listed in assets.json and prepare them for the renderer.

  assets/raw/     untouched downloads (cached)
  assets/img/     <key>.jpg  working stills (low-res classics are 4x upscaled with Real-ESRGAN anime-6B first)
  assets/audio/   score.wav  the soundtrack

Nothing under assets/ is committed: the material belongs to Hypergryph / Monster Siren Records and is
fetched from their own public servers at build time.
"""
import json, os, subprocess, sys, urllib.request
from PIL import Image
Image.MAX_IMAGE_PIXELS = None
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW, IMG, AUD = (os.path.join(ROOT, 'assets', d) for d in ('raw', 'img', 'audio'))
UA = {'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36'}
MODEL_URL = 'https://huggingface.co/amd/realesrgan-x4plus-anime-6b/resolve/main/RealESRGAN_x4plus_anime_6B.pth'

def fetch(url, dst, referer=None):
    if os.path.exists(dst) and os.path.getsize(dst) > 1000: return dst
    h = dict(UA); 
    if referer: h['Referer'] = referer
    print('fetch', url, flush=True)
    with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=300) as r, open(dst, 'wb') as f:
        f.write(r.read())
    return dst

def main():
    for d in (RAW, IMG, AUD): os.makedirs(d, exist_ok=True)
    man = json.load(open(os.path.join(ROOT, 'assets.json')))
    todo_up = []
    for key, it in man['images'].items():
        raw = fetch(it['url'], os.path.join(RAW, os.path.basename(it['url'])), 'https://ak.hypergryph.com/')
        out = os.path.join(IMG, key + '.jpg')
        if os.path.exists(out): continue
        if it['upscale']:
            up = os.path.join(RAW, os.path.splitext(os.path.basename(raw))[0][-12:] + '_x4.png')
            if not os.path.exists(up): todo_up.append(raw)
    if todo_up:
        model = fetch(MODEL_URL, os.path.join(RAW, 'RealESRGAN_x4plus_anime_6B.pth'))
        subprocess.check_call([sys.executable, os.path.join(ROOT, 'tools', 'upscale.py'), model, RAW] + todo_up)
    for key, it in man['images'].items():
        out = os.path.join(IMG, key + '.jpg')
        if os.path.exists(out): continue
        raw = os.path.join(RAW, os.path.basename(it['url']))
        src = os.path.join(RAW, os.path.splitext(os.path.basename(raw))[0][-12:] + '_x4.png') if it['upscale'] else raw
        im = Image.open(src).convert('RGB')
        if it['upscale']: im = im.resize((2560, round(2560 * im.height / im.width)), Image.LANCZOS)
        im.save(out, quality=94, subsampling=0)
        print('img', key, im.size, flush=True)
    wav = os.path.join(AUD, 'score.wav')
    if not os.path.exists(wav):
        meta = json.loads(urllib.request.urlopen(urllib.request.Request(man['music']['api'], headers=UA), timeout=60).read())
        fetch(meta['data']['sourceUrl'], wav, 'https://monster-siren.hypergryph.com/')

if __name__ == '__main__':
    main()
