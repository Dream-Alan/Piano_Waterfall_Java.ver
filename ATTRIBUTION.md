# 素材来源与致谢

## 钢琴采样

`src/main/resources/samples/piano/` 中的 26 个 WAV 来自：

- **VSCO 2 Community Edition**（Versilian Studios Chamber Orchestra 2 Community Edition）
- 经由 **tonejs-instruments** 项目分发：<https://github.com/nbrosowsky/tonejs-instruments>
- 采样许可：**Creative Commons Attribution 3.0 (CC-BY 3.0)**
  <https://creativecommons.org/licenses/by/3.0/>

本项目对原始采样做了以下处理：按三半音间隔选取 26 个根音（A1–C8），
裁剪到 5.5 秒、淡入淡出、峰值归一化，并转为 44.1 kHz / 16-bit / 单声道 WAV。
这些处理不改变原始素材的 CC-BY 3.0 授权；再分发时请保留本致谢。

## 其他

- JavaFX / OpenJFX：GPL v2 + Classpath Exception。
- hFT-Transformer 转录模型、Kong CRNN 模型：仅在通过 Python 环境启用 AI 扒谱时使用，
  相关授权见对应上游项目。
