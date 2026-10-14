# 接口与参数

## 本机入口

默认页面：`http://127.0.0.1:8770/`。

| 接口 | 含义 |
| --- | --- |
| `GET /api/health` | `{liveReady, model, region}`；liveReady 只代表已配置 Key |
| `GET /api/last-run` | 最近一次已结束会话的时长、字节数、首包等统计，保存在内存；不含录音或转写正文 |
| `WS /stream?target=zh` | 中文重述；不传 target 也为 zh |
| `WS /stream?target=en` | 英文语音翻译 |

每次按住新建一个 WS 会话，当前只接受一个活动会话。浏览器请求受本机 Host/Origin 检查保护；本包没有远程用户认证。未带 Origin 的原生 WS 客户端可用于本机接入测试。

## 浏览器发给本机后端

连接完成后，先等 `ready`；等待期间可以采集到本地队列，但不能把尚未 ready 的包直接丢进后端。

- **二进制消息**：16 kHz、单声道、PCM16 little-endian，不含 WAV 文件头。正常每包 640 字节（20 ms）；最后一包允许较短，但字节数须为偶数。
- **文本消息**：发送队列排空后发送一次 `{"type":"stop"}`。它要求云端完成剩余输出，不等于立即断开连接。

服务端收到 stop 后会发送云端 `session.finish`，等待 `session.finished` 再通知浏览器。不要用关闭 WS 代替正常停止，否则容易丢最后几个字或音频块。

## 本机后端发给浏览器

所有下行消息为 JSON 文本，音频字段为 Base64。示例只展示结构。

```json
{"type":"ready","model":"qwen3.8-livetranslate-flash-realtime","target":"zh","readyMs":2400,"frameMs":20,"sampleRate":24000,"voiceClone":true}
```

```json
{"type":"segment","segment":{"id":"source-item","speakerId":"1","startMs":200,"endMs":2800,"original":"你好。","originalFinal":true,"translations":{"zh":"你好。"},"translationFinal":{"zh":true}}}
```

```json
{"type":"output_link","itemId":"output-item","sourceId":"source-item"}
```

```json
{"type":"audio","pcm":"BASE64_PCM_BYTES","sampleRate":24000,"itemId":"output-item","sourceId":"source-item","responseId":"response-id"}
```

```json
{"type":"finished"}
```

```json
{"type":"error","message":"可读错误原因"}
```

`startMs`/`endMs` 相对于本轮输入音频起点，尚未收到时为 null。`sourceId` 也可能暂时为 null，后续通过 `output_link` 补齐，不能猜测关联。`segment` 是完整快照，按 id 更新已有项；云端 delta 的累加已在后端完成。

`finished` 只表示云端输出结束；客户端播放器可能仍有已排程音频，需播完后再恢复可按状态。发生 error 时立即停止收音、清空待播音频并恢复重试。后端不会把 Key 返回给前端。

## 云端入口

业务空间 ID：`llm-h6q09ysut5xftgvu`。地域：`cn-beijing`。

```text
wss://llm-h6q09ysut5xftgvu.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?model=qwen3.8-livetranslate-flash-realtime
Authorization: Bearer <运行时 API Key>
```

这是 WebSocket 接口，不是 HTTP POST 接口。浏览器不能自行设置 Bearer 握手请求头，因此由 Node.js 的 `ws` 客户端连接。不要把 Key 放到 URL 查询参数或浏览器代码里。

连接后发送：

```json
{
  "type":"session.update",
  "session":{
    "output_modalities":["text","audio"],
    "audio":{
      "input":{
        "format":{"type":"pcm","sample_rate":16000},
        "turn_detection":{"type":"speaker_detection","threshold":0.5,"silence_duration_ms":500}
      }
    },
    "voice":"default",
    "enable_voice_clone":true,
    "voice_clone_options":{"frequency":"once"},
    "translation":{"language":"zh"}
  }
}
```

| 参数 | 为什么这样设置 |
| --- | --- |
| `output_modalities` | 同时得到生成语音及其文字 |
| 输入 `pcm/16000` | 对应本地音频采集和上传格式 |
| `speaker_detection`、threshold 0.5 | 使用模型支持的语音分段配置 |
| `silence_duration_ms=500` | 当前实测接受的断句设置 |
| `enable_voice_clone=true` | 从本次输入中复刻音色，默认关闭时不会自动保留本人音色 |
| `voice=default`、`frequency=once` | 每会话提取一次音色并在本会话复用；新按住动作会建立新会话 |
| `translation.language=zh/en` | 同语言中文重述或英文翻译；源语种保持自动识别 |

不发送图片，也没有另加“强制修复”的提示词。模型原生中文同语言输出已实测可用，但不保证逐字复读。`same_language_skip_options` 在此模型文档中标为不支持，本包不发送该参数。

收到 `session.updated` 后确认输出含 audio、复刻已启用，从 `session.audio.output.format.sample_rate` 读取返回采样率（本次为 24000），然后给前端 ready。

## 云端事件到本机事件

| 云端事件 | 本机处理 |
| --- | --- |
| `input_audio_buffer.speech_started/stopped` | 保存输入项的起止时间及可用的 speaker_id |
| `conversation.item.input_audio_transcription.delta` | 累加原文 |
| `conversation.item.input_audio_transcription.completed` | 用最终原文覆盖临时值 |
| `conversation.item.created` | 通过 previous_item_id 建立输出与输入的关联，发 output_link |
| `response.audio_transcript.delta/done` | 累加/定稿生成音频对应的文字 |
| `response.audio.delta` | Base64 PCM 透传为 audio，浏览器按播放门控处理 |
| `response.done` | 用完整输出修正文字，并检查失败状态 |
| `session.finished` | 发 finished，关闭本轮连接 |
| `error`、transcription.failed、异常断线 | 脱敏后发 error，清理本轮资源 |

上行每包封装为 `{"type":"input_audio_buffer.append","audio":"BASE64_PCM_BYTES"}`。正常结束发送 `{"type":"session.finish"}`。

超时设置：云端握手 12 秒、配置就绪 15 秒、结束后等待剩余输出 30 秒。错误不会自动重录或重放，避免把上一轮音频混入下一轮。
