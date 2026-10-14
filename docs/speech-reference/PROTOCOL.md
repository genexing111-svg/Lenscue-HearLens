# 前端—语音后端协议

文档日期：2026-09-26。以打包时的 `reference-app/server.mjs` 和 `reference-app/livetranslate.mjs` 为依据。

这是当前原型的实际协议。说明中的文字与时间示例是用于理解结构的示例数据，不是性能承诺。

## 1. 接入地址

同机默认：

```text
HTTP_BASE=http://127.0.0.1:8765
WS_BASE=ws://127.0.0.1:8765

GET /api/config
WS  /stream?engine=asr
WS  /stream?engine=live&target=en
WS  /stream?engine=live&target=zh
```

`engine` 可选 `asr`、`live`，缺省为 `live`。`target` 仅对同传有效，请显式填写 `en` 或 `zh`。当前实现将非 `zh` 值视为 `en`，不要利用这个宽松行为传入其他语言代码。

当前服务仅监听回环地址，同时校验 Host 与 Origin；HTTP 未开放跨域访问。同一电脑上另一个端口的网页也不能默认当成同源。手机的 `127.0.0.1` 指向手机自己。

手机／眼镜实际地址由后端负责人完成局域网部署后给出，本包未执行该部署。

## 2. 配置查询

`GET /api/config` 示例：

```json
{
  "configured": true,
  "model": "qwen3.8-livetranslate-flash-realtime",
  "region": "北京",
  "frameMs": 20,
  "defaultEngine": "live",
  "engines": [
    {"id":"live","model":"qwen3.8-livetranslate-flash-realtime","translation":true,"speakerDetection":true},
    {"id":"asr","model":"qwen-audio-3.1-asr-flash-streaming","translation":false,"speakerDetection":false}
  ]
}
```

`configured` 只说明后端有 Key，不证明该 Key 有效或云端在线。顶层 `model` 是默认模型，不代表一个正在运行的会话；会话实际模型以 `ready.model` 为准。

`GET /api/last-run` 是可选诊断接口，初始为 `null`，有结果时返回最近会话计数、耗时等。前端核心功能无需依赖它。

## 3. 音频与消息编码

| 项目 | 取值 |
| --- | --- |
| 采样率 | 16,000 Hz |
| 声道 | mono |
| 编码 | PCM，signed 16-bit，little-endian |
| 常规包 | 20 ms = 320 samples = 640 bytes |
| 音频消息 | WebSocket binary message |
| 控制消息与服务端消息 | UTF-8 JSON text message |

音频不要带 WAV 文件头，不要转为 Base64，不要嵌入 JSON。后端自行处理两种模型不同的云端上传协议。

实际采集采样率为 44.1／48 kHz 时先连续重采样，不能只把采样率字段改成 16 kHz。Android 原生可使用 AudioRecord；网页可参考 `reference-app/public/audio-worklet.js`。采集、重采样和网络发送都应持续处理，不录完整段后再上传。

停止时允许发送不足 20 ms 的尾包；尾包必须非空且字节数为偶数。单条消息的服务端上限为 65,536 字节，常规使用 640 字节远低于此限制。

## 4. 生命周期

```text
前端建立 WebSocket
  → 后端连接云端模型
  ← ready
前端持续发送二进制 PCM；同时处理字幕消息
前端停止录音、发送尾包
  → {"type":"stop"}
  ← 最后的 result / segment
  ← {"type":"finished"}
  ← 服务端正常关闭连接
```

`onopen` 仅表示连接到本地后端。**必须收到 `ready` 才能上传；当前后端不缓存提前发送的音频。**

准备完成消息：

```json
{"type":"ready","engine":"live","model":"qwen3.8-livetranslate-flash-realtime","readyMs":746,"frameMs":20,"target":"en"}
```

ASR 的 `ready` 不含 `target`。`readyMs` 是云端会话准备耗时，不是语音识别延迟。同传随后还会返回 `{"type":"configured","target":"en"}`。

当前服务仅允许一条识别会话。开始按钮应防止重复连接。正常停止后，ASR 等待最终结果超时为 7 秒，同传为 15 秒；遇到 `error` 或异常断线必须释放麦克风。正常关闭不能过早，否则可能丢失尾句。

## 5. 快速转写 result

```json
{
  "type": "result",
  "sentence": {
    "sentence_id": 1,
    "begin_time": 520,
    "end_time": null,
    "text": "我们明天",
    "sentence_end": false,
    "words": [
      {"begin_time":520,"end_time":800,"text":"我们","punctuation":""}
    ]
  }
}
```

- `sentence_id` 标识句子，仅当前会话有效。
- `text` 是该句当前全文；同一编号后来的结果替换旧全文。
- `sentence_end=false` 表示临时结果；`true` 表示最终结果。
- `begin_time`、`end_time`、字时间戳单位均为毫秒，相对于本次上传音频时间轴。
- `end_time`、字时间戳可能尚未给出；空文字事件无需渲染字幕。
- 当前模型没有说话人字段，也没有翻译。不要把 `sentence_id` 当成说话人。

例如先后收到「我们」「我们明天」「我们明天见」，最终显示「我们明天见」，而不是三段文本叠加。

## 6. 同传 segment

后端已经完成原文、译文和说话人关联，返回的是句段快照：

```json
{
  "type": "segment",
  "segment": {
    "id": "item_example",
    "speakerId": "1",
    "startMs": 200,
    "endMs": null,
    "original": "我们明天见。",
    "originalFinal": true,
    "translations": {
      "zh": "我们明天见。",
      "zh-Hant": "我們明天見。"
    },
    "translationFinal": {"zh":false,"zh-Hant":false}
  }
}
```

- 按 `segment.id` 新增或覆盖整个句段。这里的文字不是增量，不要再拼接。
- `speakerId` 是字符串或 `null`；`"0"` 也是可能的编号，不能用真假值判断是否有效。
- 同一会话内维护 `speakerId → 显示名称／颜色` 映射；新会话重新建立映射。
- `originalFinal` 仅描述原文；每个译文的完成状态查看 `translationFinal[语言]`。
- `translations` 和 `translationFinal` 是稀疏对象，不能假定所有语种同时存在。
- 可能先收到只有说话人、时间信息的空句段，也可能先有原文，再有译文。
- `startMs`、`endMs` 是音频相对毫秒时间，可能为 `null`。模型端点可能包含静音。
- 说话人编号是模型判断的匿名角色，不是真实身份，也不能直接绑定画面人物。

## 7. 输出档位和切换

| 前端档位 | 建连 target | 显示字段 |
| --- | --- | --- |
| 原文 | 可沿用现有 target；新会话默认可用 en | original |
| 中文 | zh | translations.zh |
| 英文 | en | translations.en |
| 繁体中文 | zh | translations["zh-Hant"] |

一次同传会话只有一个翻译目标。`target=zh` 时，后端用 OpenCC 同时提供简体和香港繁体版本。只显示原文时，云端仍可能生成未显示的译文；这不是纯 ASR 模式。

原文与已存在译文切换、简繁切换都是前端显示行为。切换中文／英文翻译目标或切换模型，需要停止旧会话并建立新连接。切换期间可以短暂停顿并明确提示；当前实现清空字幕和计时，不补译历史句子。

后端目前接受的配置请求：

```json
{"type":"configure","target":"zh"}
```

但它只返回：

```json
{"type":"restart_required","target":"zh"}
```

**这不是热切换成功，也不是后端自动重连。** 前端直接执行停止／重连即可，无须先发送 configure。

## 8. 错误与关闭

```json
{"type":"error","message":"已有识别会话，请先停止它"}
```

错误消息适合显示给用户。处理 `error` 时停止采集并释放音频资源；也必须处理没有 error 消息的 WebSocket 异常关闭。

后端通常使用 1000 正常结束、1008 拒绝缺少配置／重复会话／无效模式、1011 表示运行错误。不要只依赖关闭码判断是否收到了完整尾句，以 `finished` 为准。

前端为每次连接维护独立 session generation。断线不意味着可以把之前缓冲的音频高速重发；当前后端没有自动断点续传，音频积压过多会主动报错停止。

## 9. 延迟展示（如需要）

- 首次出字／首次原文：首次非空原文到达时刻减去模型报告的语音开始时刻。
- ASR 字幕延迟估算：新增字的结果到达时刻减去最新字音频结束时刻；有字时间戳时才计算。
- 同传首次译文：首次非空译文到达时刻减去语音开始时刻。
- 定稿延迟估算：完成结果到达时刻减去对应语音结束时刻。

需要建立「上传音频时间轴 ↔ 本机单调时钟」映射。不要混用手机与电脑的墙钟，不要把 readyMs 当作识别速度。没有时间戳时显示空值，不能沿用上一句或另一个模型的数值。

这些估算不包含未经标定的麦克风硬件前置延迟。同传没有提供与 ASR 相同的逐字时间戳，不应伪造该项读数。

## 10. 参考代码

- 最小网络连接：`examples/caption-client.mjs`
- 原型完整前端：`reference-app/public/app.js`
- 音频重采样与分包：`reference-app/public/audio-worklet.js`
- 后端路由与模型协议：`reference-app/server.mjs`
- 同传消息关联：`reference-app/livetranslate.mjs`
