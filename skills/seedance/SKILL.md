---
id: "seedance"
name: seedance
description: Seedance 2.0 AI 视频生成 — 文生视频、图生视频、参考视频生成，支持同步音频，最高 1080p，4-15 秒短片
version: 1.0.0
author: AIMarketing
tags: [seedance, video-generation, ai-video, bytedance, text-to-video, image-to-video, content-creation]
entrypoints: [main]
inputs:
  type: object
  properties:
    action:
      type: string
      enum: [generate, guide, help]
      description: 技能动作
    prompt:
      type: string
      description: 视频描述提示词
    model:
      type: string
      enum: [seedance-2-0, seedance-2-0-fast, seedance-2-0-studio]
      description: 模型选择
outputs:
  type: object
dependencies: []
---

# Seedance 2.0 AI 视频生成技能

ByteDance Seedance 2.0 文生/图生/参考视频生成。支持同步音频，最高 1080p 分辨率，4-15 秒时长。

## 模型选择

| 模型 | 特点 | 适用场景 |
|------|------|----------|
| **Seedance 2.0 Pro** | 最佳质量，最高 1080p | 正式创作、高品质需求 |
| **Seedance 2.0 Fast** | 快速生成，最高 720p | 快速迭代、测试阶段 |
| **Seedance 2.0 Studio** | 高质量 + 私有资产库，人像一致性 | 品牌内容、人物特写 |

## 参数说明

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `prompt` | string | 必填 | 视频描述文本 |
| `generate_audio` | boolean | true | 生成同步音频 |
| `duration` | integer | 5 | 视频时长 4-15 秒 |
| `ratio` | enum | adaptive | 21:9 / 16:9 / 4:3 / 1:1 / 3:4 / 9:16 |
| `resolution` | enum | 720p | 480p / 720p / 1080p |
| `image` | file | - | 首帧参考图 |
| `end_image` | file | - | 尾帧参考图（需 image） |
| `reference_images` | file[] | - | 参考图（最多 9 张） |
| `reference_videos` | file[] | - | 参考视频（最多 3 个，各 2-15 秒） |
| `reference_audios` | file[] | - | 参考音频（最多 3 个，各 2-15 秒） |

## 使用方式

### CLI（需安装 belt CLI）
```bash
belt login
belt app run bytedance/seedance-2-0 --input '{
  "prompt": "your video description",
  "generate_audio": true,
  "duration": 5,
  "ratio": "16:9"
}'
```

### 多模态输入
支持同时传入图片、视频、音频作为参考，实现：
- **角色一致性**：用参考图锁定人物外观
- **运镜复制**：参考视频的镜头运动
- **音频同步**：参考音频的语调/节奏

## 输入约束

- 参考图：≤ 9 张，每张 ≤ 30MB，支持 jpeg/png/webp/bmp/tiff/gif
- 参考视频：≤ 3 个，每个 2-15 秒，≤ 50MB，支持 mp4/mov
- 参考音频：≤ 3 个，每个 2-15 秒，≤ 15MB，支持 wav/mp3
- 文件总数：≤ 12 个

## 提示词技巧

1. **图/文分工**：稳定身份（人脸/服装/标志）→ 放 `image_url`；变化叙事（动作/情绪/灯光）→ 放 `prompt`
2. **镜头语言**：使用 "中景近拍"、"慢推"、"手持跟拍"、"固定广角" 等明确指令
3. **音频方向**：指定语调 "温暖友好对话"、"冷静讲解"、"清脆新闻播报"
4. **时间分段**：10 秒以上视频按 0-3s/3-6s/6-10s 分段描述动作演变

## 参考资源

- Seedance 官网：https://www.volcengine.com/product/seedance
- RunComfy：https://www.runcomfy.com
- Fal AI：https://fal.ai
