# Clau娘宣传片

- `clau-promo.html`：用 Canvas 逐帧绘制的宣传动画（63 秒，1920×1080），附播放进度条；人物帧和特效来自 App 的素材。
- `audio/`：用 App 同款离线语音（sherpa-onnx + piper huayan）合成的 6 句台词，`lines.json` 记录了文本和时长。
- `Clau娘宣传片.mp4`：导出的成片（H.264 + AAC，带配音）。

重新导出视频：

```
python3 serve_utf8.py .          # 以 UTF-8 提供页面，避免中文乱码
node export.js "$PWD" "$PWD/Clau娘宣传片.mp4"   # 30 帧/秒逐帧渲染并混入台词
```
