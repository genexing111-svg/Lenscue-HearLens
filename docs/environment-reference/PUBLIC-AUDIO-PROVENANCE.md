# Public diagnostic audio provenance

`app/src/androidTest/assets/environment/public-reference-speech.wav` is the public Chinese ASR example referenced by the official QwenLM/Qwen3-ASR repository:

- Official example: https://github.com/QwenLM/Qwen3-ASR/blob/main/examples/example_qwen3_asr_transformers.py
- Public sample: https://qianwen-res.oss-cn-beijing.aliyuncs.com/Qwen3-ASR-Repo/asr_zh.wav
- Size: 134,570 bytes
- SHA-256: `46dbc998c9d1d48111267c40741dd3200f2e5bcf4075f8c4c97f4451160dce50`

On 14 October 2026, the staged project file and a fresh download from that public source had identical SHA-256 hashes. This fixture is used for controlled audio-classifier and speech-protocol checks. It is not a recording collected from the project user or a target-user trial.

The `.f32le` fixtures under `app/src/test/resources/environment` are generated sine-wave and pulse signals for resampling/window tests, as documented in MODEL-PROVENANCE.md. They are separate from the public speech example.

Original sources retain their rights. The source/provenance record does not grant unrestricted reuse of any person's voice.
