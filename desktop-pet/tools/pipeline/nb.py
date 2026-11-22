"""Minimal Nano Banana 2.1 client via OpenCode (Google generateContent format)."""
import base64, json, sys, time, urllib.request, urllib.error, pathlib

BASE = "https://opencode.ai/inference/google/v1beta"
MODEL = "gemini-nano-banana-2.1"

def gen(prompt, refs=(), aspect="2:3", size="2K", out=None, retries=2):
    parts = []
    for r in refs:
        data = pathlib.Path(r).read_bytes()
        mime = "image/png" if r.endswith(".png") else "image/jpeg"
        parts.append({"inlineData": {"mimeType": mime, "data": base64.b64encode(data).decode()}})
    parts.append({"text": prompt})
    body = {"contents": [{"role": "user", "parts": parts}],
            "generationConfig": {"responseModalities": ["IMAGE"],
                                 "imageConfig": {"aspectRatio": aspect, "imageSize": size}}}
    req = urllib.request.Request(f"{BASE}/models/{MODEL}:generateContent", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer proxy-injected", "User-Agent": "pet-assets-client/1.0"})
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                d = json.load(resp)
            break
        except urllib.error.HTTPError as e:
            msg = e.read().decode()[:600]
            if attempt == retries or e.code < 500 and e.code != 429:
                raise RuntimeError(f"HTTP {e.code}: {msg}")
            time.sleep(8 * (attempt + 1))
    usage = d.get("usageMetadata", {})
    imgs = [p["inlineData"] for c in d.get("candidates", []) for p in c.get("content", {}).get("parts", []) if "inlineData" in p]
    texts = [p["text"] for c in d.get("candidates", []) for p in c.get("content", {}).get("parts", []) if "text" in p]
    if not imgs:
        raise RuntimeError("no image: " + json.dumps(d)[:600])
    raw = base64.b64decode(imgs[0]["data"])
    if out:
        pathlib.Path(out).write_bytes(raw)
    return {"mime": imgs[0].get("mimeType"), "bytes": len(raw), "usage": usage, "text": texts}

if __name__ == "__main__":
    print(gen(sys.argv[1], sys.argv[3:], out=sys.argv[2]))
