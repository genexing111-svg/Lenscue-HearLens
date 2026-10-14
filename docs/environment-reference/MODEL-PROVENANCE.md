# 模型与资源来源

## 模型

- 本包路径：`models/yamnet.tflite`。参考网页为便于独立启动，另放了一份字节相同的副本。
- 官方下载地址：[MediaPipe YAMNet TFLite](https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/latest/yamnet.tflite)。链接中的 latest 可能更新，因此复现使用包内文件及其 SHA-256。
- 大小：4,126,810 字节。
- SHA-256：`4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf`。
- 文件元数据：名称 `yamnet/classification`、版本 `1`、作者 `MediaPipe`；许可证字段声明 Apache License 2.0；描述说明内部为 8 位量化。
- 外部 tensor：FLOAT32 波形 `[15600]` → FLOAT32 分数 `[1,521]`。本包没有对权重微调、剪枝或再次转换。
- 详细结构由实际文件按官方 FlatBuffer schema 读取，见 `models/tensor-inspection.json`，不是只抄产品页参数。

官方概览把输入写成 `1 × 15600`，而该文件实际 Tensor shape 是 `[15600]`。直接调用解释器时以运行时读取的实际 shape 为准，高阶 AudioClassifier API 则接收音频数组与采样率。[官方模型概览](https://developers.google.com/edge/mediapipe/solutions/audio/audio_classifier)

## 类别与语言

- 英文类别 CSV：[TensorFlow YAMNet 官方类别表](https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv)，包内 `data/yamnet-class-map.csv`。
- 模型内嵌标签 `yamnet_label_list.txt` 已导出为 `data/yamnet-embedded-labels.txt`；逐索引与 521 项英文表核对一致。
- 简体中文是此原型的译名，繁体用 `opencc-js@1.4.2` 的 `cn -> hk` 转换生成，生成结果固定在 `data/labels.json`。这不是模型自带的中文识别输出。
- 三语标签是声音类别的本地化，不是把人说的话翻译成另外一种语言。
- Doorbell/Knock 的原始语义参考 [AudioSet 门相关类别](https://research.google.com/audioset/ontology/door_1.html)。

## 运行依赖

| 组件 | 固定版本 | 用途 | 许可 |
|---|---|---|---|
| @mediapipe/tasks-audio | 1.0.1 | Web CPU/WASM 模型运行时 | Apache-2.0 |
| opencc-js | 1.4.2 | 构建时生成繁体标签；不在实时推理中调用 | MIT AND Apache-2.0 |
| Node.js | 本次验证 v24.15.0 | 本地静态文件服务器和验证脚本 | 可执行文件未随包提供 |

Node 包及其锁文件完整放在 `reference-web/`，无需联网安装即可启动已有参考页。第三方许可文件见 `licenses/` 和各依赖包目录；模型 Apache-2.0 信息也来自其内嵌元数据。

## 自检音频

`fixtures/public-reference-speech.wav` 与 `reference-web/dist/reference.wav` 是同一公开短音频，来源为 [Qwen 官方公开测试音频](https://qianwen-res.oss-cn-beijing.aliyuncs.com/Qwen3-ASR-Repo/asr_zh.wav)。用途只有 YAMNet 的 Speech 类别自检；本包没有 Qwen 模型、云服务凭据或此前语音转写项目。

另附的 `.f32le` 文件是程序生成的正弦波和短脉冲，用于重采样／分窗比较，不是录下来的用户麦克风音频。包内不包含用户录音。

## 校验

`MANIFEST.json` 列出全部交付文件的路径、字节数和 SHA-256。`node tools/verify-package.mjs` 可在收包后逐文件核对；`node tools/verify-behavior.mjs` 另外核对两份模型、两份判定代码和两份原 worklet 的一致性。
