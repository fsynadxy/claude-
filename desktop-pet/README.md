# 桌宠素材 · 橙花少女

## 提示词
- 在线页面：https://claude.ai/artifact/FdmhTA5RjqE9NzgQf3DU4o（私有，默认只有你能打开）
- 本地副本：`prompts/pet-prompts.html`（用浏览器打开即可，复制按钮和进度勾选同样可用）

## 目录
| 路径 | 内容 |
| --- | --- |
| `source/original.png` | 初始立绘原图（1024×1536） |
| `base/base_reference_white.png` | 白底参考图：上传给生成工具 |
| `base/base_transparent.png` | 透明底图：桌宠待机帧 |
| `base/base_sticker.png` | 贴纸白边版（可选风格） |
| `fx/fx_notice.png`、`fx/fx_flower.png` | 从原图提取的惊觉短线、小花特效 |
| `tools/` | 生成上述底图的脚本（抠图、去装饰、统一画布） |

## 底图规格
- 画布 1024×1536，人物高度占 85%，脚底距底边 5%，水平居中（相对原图缩放 0.8945）
- 未经 AI 重绘，与原画逐像素一致；发丝间的封闭空隙已抠成透明

## 重新生成底图
在放有 `original.png` 的目录中依次运行（需要 numpy、scipy、Pillow）：

```
python3 tools/process.py   # 抠图、去掉装饰符号、提取特效
python3 tools/compose.py   # 统一画布，输出白底、透明、贴纸三个版本
```

`process.py` 里的 `FACE` 区域用来保护眼白不被当成背景，只适用于这张原图。
