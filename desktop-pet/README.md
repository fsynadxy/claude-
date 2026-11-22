# 桌宠素材 · Clau娘

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

## Nano Banana 2.1 生成的素材
- `generated/`：模型原图（白底，网格排列），总览见 `generated/_lineup_preview.png`
- `tools/pipeline/`：生成与处理脚本
  - `nb.py` 调用接口，`jobs.py` 全部提示词，`run.py` 批量生成
  - `cut.py` 白底抠图，`frames.py` 切帧、统一比例、对齐脚底
  - `idle.py` 把眨眼、口型配准后只合成脸部，保证与待机帧逐像素对齐
- 处理结果在 `android/app/src/main/assets/pet/`：640×800 画布，脚底基线 y=780，所有动作帧朝左

## 安卓应用（Clau娘）
项目在仓库根目录的 `android/`，推送后 GitHub Actions（`.github/workflows/android.yml`）自动编译 APK，在 Actions 页面的 Artifacts 里下载。推送 `v` 开头的标签（如 `v1.0`）时，还会把 APK 发布到 Releases，方便在手机上直接下载。

## 聊天（角色卡）
- 双击 Clau娘 或在通知栏点“聊天”，会弹出聊天面板；完整回复显示在面板里，她头顶的气泡只说第一句。
- 主界面“聊天设置”：支持 Anthropic 格式和 OpenAI 兼容格式。默认是 OpenCode 的 Anthropic 接口，模型 `claude-sonnet-5-5`；填入自己的 Key 即可使用。Key 只保存在手机的应用数据里。
- 内置角色卡“Claude大小姐”：内在就是 Claude，说话尽量贴近 Claude 本人的风格。
- 自定义角色卡：可以新建、编辑（内置卡会先复制一份）、删除；也能导入 SillyTavern / TavernAI 角色卡（.json，或内嵌数据的 .png，支持 v1 / v2 / v3）。每张卡的聊天记录分开保存。
