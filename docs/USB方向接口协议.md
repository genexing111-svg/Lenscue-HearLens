# USB 方向角接口 v1

v0.12.2 连接策略：进入应用提前准备授权；声音感知关闭时前台连接可处于 standby，`enabled=false`、`connected=true`、`fresh=false`，不输出旧角度。状态增加 `permissionRequests / portOpens / portCloses / permissionPending / standby`，计数属于本次控制器实例。拒绝后自动请求去重，授权本身始终由 Android 查询决定。后台仍关闭串口。

串口输入与相机、语音识别独立；由 `UsbDirectionController` 持有一个 USB 串口读取线程。`DirectionProtocol` 将完整文本记录转换为 `DirectionEvent.Angle` 或 `NoDirection`，`DirectionSnapshot` 提供校准与有效期状态。画面合成读取不可变状态快照，USB 读取不会在绘制线程阻塞。

## 本次小板与帧格式

- VID:PID `1A86:7523`，CH340；已在电脑 COM3 读取真实方向记录。
- 默认 115200 / 8N1，无硬件流控，DTR / RTS 为 false。不写串口业务字节；驱动配置波特率等 USB 控制传输仍是打开串口所必需的。
- 每条记录以 LF、CR 或 CRLF 结束，UTF-8；支持任意 USB 分包及多记录粘包。
- 单行最大 512 字节。超过长度或出现二进制控制字节，整行丢弃直到下一个换行；不能让超长行末尾伪装成有效新记录。

支持格式：

```text
angle: 354.0 deg
angle=90
azimuth: 180
DOA=270
方向角: 45°
```

JSON 模式 / 自动模式：

```json
{"angle":123.5,"valid":true}
```

数值字段可为 `angle`、`angle_deg`、`azimuth`、`doa`、`direction`，但每条记录只能出现其中一个。值必须为 JSON 数值，不接受数字字符串、布尔值或非有限值。`{"valid":false}` 立即使旧方向失效。额外业务字段忽略，不执行其内容。

只有显式选择“每行仅角度数字”时，`123.5` 才作为角度。自动模式不接受此格式。角度有效范围为 0–360°，360 归零；负数（包括常见的无方向标记 −1）不作为角度。不是本协议的二进制设备需按厂家协议增加解码器，不能从任意字节猜测角度。

## 状态读取与显示

通过现有 Wi-Fi `GET /status`（仍需 `X-HearLens-Key`）读取 `stereo.direction`：

```json
{
  "enabled":true,
  "connected":true,
  "fresh":true,
  "message":"已收到有效方向角",
  "device":"USB 串口 · 1A86:7523",
  "rawDegrees":1.0,
  "relativeDegrees":6.0,
  "sector":"正前方",
  "ageMs":120,
  "frontDegrees":355.0,
  "clockwise":true,
  "baudRate":115200,
  "format":"AUTO",
  "staleMs":3000,
  "showOverlay":true,
  "receivedBytes":360,
  "accepted":20,
  "rejected":9
}
```

上面是协议示例，不是实时状态。过期、断开或禁用时 `fresh=false`，`rawDegrees`、`relativeDegrees`、`sector` 为 null，不继续暴露旧值为当前角度。`ageMs` 使用单调时间衡量最近有效记录的年龄；无线 HTTP 本身还有网络延迟，该字段不是总延迟。

校准只改变手机端角度换算，不改小板固件或原始输出；不影响相机朝向、画面镜像或字幕布局。语音识别文本尚未绑定此方位，更没有将一个角度解释成多个独立声源。

## 扩展位置

v0.11.1 增加 `DirectionSideCalibration.kt`：采集按键之后的稳定侧向声源角度，推断 `clockwise`，不更改 `frontDegrees`。一键互换直接反转 `clockwise`。`stereo.direction` 字段保持兼容；无新增网络校准或模拟输入接口。

- USB 驱动：`UsbDirectionController.kt`，基于固定版本 `usb-serial-for-android:3.11.0`，许可证随 APK 和源码提供。
- 帧解析、范围和失效规则：`DirectionProtocol.kt`。
- 手机配置和正前方校准：`DirectionSettingsDialog.kt`。
- 普通预览 / 双眼 / 无线画面提示：`DirectionPainter.kt`，通过 `LocalStereoEngine` 调用。
- 协议分片、启动日志过滤、0/360 跨界、顺逆时针和失效状态测试：`DirectionProtocolTest.kt`。

前方基准适用于当前固定安装；换板或改变小板安装朝向后应重新校准。没有内置陀螺仪融合、头部姿态或世界坐标方向补偿。
