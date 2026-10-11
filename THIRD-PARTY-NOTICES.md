# 第三方资源与声明 / Third-Party Notices

本仓库包含或依赖以下第三方资源。**其版权归各自作者所有**，本项目按各自的许可条款使用，并在下面逐项保留署名与许可信息。

- 项目自身的许可：**Apache License 2.0**，全文见 [`LICENSE`](LICENSE)
- 本文件用于满足各开源许可的署名与声明义务（如 Apache-2.0 第 4 条）

---

## 1. Live2D 模型

### 模型一：大肥鱼（默认模型）

| 项 | 内容 |
|---|---|
| 作者 | B 站 UP 主 **Mao绒绒家园** |
| 许可 | 开放模型，**版权归原作者所有**；使用与再分发须保留作者署名 |
| 本项目位置 | `app/src/main/assets/live2d/dafeiyu/` |
| 说明 | 模型文件按原样使用，未作修改 |

### 模型二：DeepSeek

| 项 | 内容 |
|---|---|
| 来源 | <https://github.com/lrplrplrp/dsh-live2d> |
| 许可 | **Apache License 2.0** |
| 本项目位置 | `app/src/main/assets/live2d/deepseeklive2dmodel2/` |
| 分发义务 | 协议副本见 [`LICENSE`](LICENSE)；来源与署名保留于上 |

---

## 2. 运行库与框架

### Live2D Cubism SDK for Java 5-r.5

- 版权：© Live2D Inc.
- 许可：**Live2D Open Software License**（<https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html>）
- 本项目位置：框架源码内嵌于 `app/src/main/java/com/live2d/`，运行库 `app/libs/Live2DCubismCore.aar`
- 注意：公开分发前请自行确认 Live2D 的 SDK 发行条款（个人 / 小规模通常免费，商用请咨询 Live2D 官方）

### sherpa-onnx 1.13.8

- 来源：<https://github.com/k2-fsa/sherpa-onnx>
- 许可：**Apache License 2.0**（副本见 [`LICENSE`](LICENSE)）
- 本项目位置：`app/libs/sherpa-onnx-1.13.8.aar`，用于本地离线语音识别

### OkHttp 4.12

- 来源：<https://square.github.io/okhttp/>
- 许可：**Apache License 2.0**（副本见 [`LICENSE`](LICENSE)）
- 用途：豆包 TTS 的 WebSocket 客户端

---

## 3. 模型与数据

### FunASR / Paraformer-zh-small-2024-03-09

- 来源：阿里达摩院 **FunASR**，<https://github.com/modelscope/FunASR>
- 本项目所用 onnx 版本由 **sherpa-onnx** 导出
- 本项目位置：`app/src/main/assets/asr/model.int8.onnx`、`asr/tokens.txt`
- 使用与再分发请遵循 FunASR 仓库及模型页的说明

---

## 4. 在线服务（非本地组件，不随本仓库分发）

以下服务需使用者自行申请凭据，并遵守各自的服务条款与计费规则：

| 服务 | 用途 | 地址 |
|---|---|---|
| DeepSeek Open Platform | 对话生成 | <https://platform.deepseek.com> |
| 火山引擎豆包语音合成（TTS） | 回复朗读 | <https://www.volcengine.com/product/voice-tech> |
| Tavily / 博查（可选） | 联网搜索 | <https://tavily.com> / <https://bochaai.com> |

---

## 5. 许可全文位置

- **Apache License 2.0**（本项目自身，以及 DeepSeek 模型、sherpa-onnx、OkHttp 所采用）：全文见 [`LICENSE`](LICENSE)
- Live2D Open Software License：见上方官方链接
- FunASR 及其模型：见其仓库与模型页说明
