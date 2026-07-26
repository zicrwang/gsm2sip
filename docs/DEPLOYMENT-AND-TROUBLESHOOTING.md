# GSM2SIP 部署与故障排查

本文档针对 Android CallAgent、WPhone 和 Asterisk 三端组成的 GSM-SIP 网关，记录当前推荐配置、安装流程、测试方法以及通话失败时的日志判断方法。

## 1. 当前推荐拓扑

| 设备 | 地址 | 用途 |
| --- | --- | --- |
| Asterisk / Armbian | `192.168.188.4:5060` | SIP 注册、路由和外拨 |
| Android CallAgent | `192.168.188.2` | GSM 与 SIP/RTP 桥接 |
| WPhone / iPhone 模拟端 | 以实际局域网地址为准 | SIP 呼叫端 |

所有设备应位于同一可互通网段。Android 的 SIP Contact 和 SDP 地址必须是 Asterisk 能访问的局域网地址，不能使用模拟器内部地址，例如 `192.168.111.98`。

## 2. 编解码配置

CallAgent 当前固定使用 PCMA（G.711 A-law，RTP payload type 8，8 kHz，
每包 160 字节/20 ms）。这是有意的兼容性选择：部分 Android 模拟器或旧音频
HAL 会声明支持宽带音频，但实际无法建立通话音频通道。接收端先经过按 RTP
扩展序列号排序的 3 帧预缓冲和 5 帧有界队列，再进入设备所需的深缓冲
`AudioTrack`；具体状态和指标见 [RTP 与媒体就绪](RTP-AND-MEDIA-READINESS.md)。

Asterisk 的 `chan_sip` 账号建议配置为：

```ini
[mi8]
type=friend
secret=<与 CallAgent 完全一致的密码>
host=dynamic
context=from-internal
disallow=all
allow=alaw
nat=force_rport,comedia
directmedia=no
```

修改后执行：

```text
asterisk -rx "sip reload"
asterisk -rx "sip show peer mi8"
```

确认输出中没有 G.722，且 `mi8` 的动态地址是 Android 当前局域网地址。

WPhone 账号也必须使用实际配置的用户名和密码。若 Asterisk 日志出现 `Wrong password`，先清除 WPhone 保存的旧账号，重新填写后再注册。

## 3. 安装 CallAgent

正常使用只安装 `gateway-magisk.zip`，因为模块已经包含 APK、特权权限和音频工具。

1. 将 `gateway-magisk.zip` 复制到手机。
2. 在 SukiSU Ultra 的模块页面安装。
3. 重启手机。
4. 将 Gateway 设置为默认电话应用。
5. 授予电话、通话记录和录音权限。
6. 在应用中填写 Asterisk 地址、端口、用户名和密码，并选择固定的
   `Outgoing GSM SIM`，然后点击启动。
7. 打开 `http://192.168.188.2:8787/` 查看调试页。

不要同时安装独立 APK。独立 `gateway.apk` 只适合快速验证界面；通话桥接需要 Magisk 模块提供的特权权限。

## 4. 推荐测试顺序

### 4.1 注册测试

先确认三端都已注册，再开始通话测试：

```text
asterisk -rx "sip show peers"
asterisk -rx "sip set debug peer mi8"
```

预期：`mi8` 和 WPhone 均为 `OK`，日志中不再出现 `Wrong password`、`Retransmission timeout` 或指向模拟器内部地址的 Contact。

### 4.2 WPhone 外拨到 GSM

从 WPhone 发起一次外拨，同时观察 Asterisk 控制台和 CallAgent 调试页。CallAgent 日志应大致按以下顺序出现：

```text
BRIDGE: GSM_DIALING
GSM ACTION_CALL started
GSM dialing via SIM1 PhoneAccount=2
GSM state: DIALING
GSM state: ACTIVE
RTP ready ... codec=PCMA
BRIDGE: BRIDGED
```

通话接通后保持 20 秒，再分别测试双方讲话、WPhone 挂断和 GSM 端挂断。

### 4.3 GSM 来电到 WPhone

拨打 Android 手机的 GSM 号码。预期流程是 GSM 响铃、CallAgent 发起 SIP 呼叫、WPhone 响铃、接听后进入 `BRIDGED`。最后从任一端挂断，CallAgent 应回到 `IDLE`。

## 5. 日志判断

### 只有注册，没有 INVITE

说明设备在线但没有进入呼叫路由。检查 Asterisk dialplan、分机号码和 `X-GSM-Forward` 头。

### 出现 `GSM_DIALING`，但没有 `GSM state: ACTIVE`

优先检查 Android 默认电话应用、`CALL_PHONE` 权限、SIM 状态以及 InCallService 是否被系统绑定。若出现以下日志，按提示处理：

```text
GSM dial blocked: CALL_PHONE permission is not granted
GSM dial blocked: CallAgent is not the default phone app
GSM ACTION_CALL launch failure
GSM dial timeout
```

双卡设备若停在 `SELECT_PHONE_ACCOUNT`，说明拨号没有携带明确的
`PhoneAccountHandle`。v2.8.57 起应在 SIP Configuration 中选择固定卡槽，
并在日志中确认 `GSM dialing via SIM<n> PhoneAccount=<id>`；MI8 实测 SIM1
对应 PhoneAccount ID `2`，SIM2 对应 ID `1`。

### SIP 已接通后立即 BYE

重点检查三项：

- WPhone 是否报告无法建立通话音频格式。
- SDP 是否只协商 `PCMA/8000`。
- CallAgent 是否记录 `AudioRecord OK`、`AudioTrack` 启动和 RTP 收发计数增长。

音频源由设备配置决定。Xiaomi MI8 (`dipper`) 会优先尝试 `VOICE_DOWNLINK`
和 `VOICE_CALL`，再回退到普通麦克风源；未验证数字通话路径的设备仍优先使用
普通音频输入。若实际顺序与设备配置不符，应先核对 APK 版本和 `DeviceProfile`
日志。

### `RTP ready` 但无声音

确认 Asterisk 的 RTP 端口可达、`directmedia=no` 已设置，并检查 CallAgent 日志中
的 `txPacketCount`、`rxPacketCount`、`captureRms`、`playbackRms`、`overflow`、
`duplicate`、`reordered`、`late`、`concealed`、`underrun`、`resync` 和
`jitterMs`。只有 `rxPacketCount` 增长表示收到对端 RTP；只有 `txPacketCount`
增长表示 CallAgent 正在发送音频。干净局域网中 `overflow` 应为 0，补偿和欠载
应接近 0。

MI8 的 `USAGE_MEDIA` 音轨通过 `deep_buffer` 落到 PCM0/MultiMedia1。通话中应确认
`Incall_Music Audio Mixer MultiMedia1` 或
`Incall_Music_2 Audio Mixer MultiMedia1` 已为 `On`；只打开 MultiMedia2 会导致
播放缓冲持续溢出，并伴随 `pcm_prepare`、`ADSP_EFAILED` 或 ASoC prepare 错误。

### Contact 或 SDP 使用错误地址

不要把模拟器内部 IP 手动写入 Asterisk。应配置模拟器 UDP 5060/5062 和 RTP 端口映射，并让 Asterisk 使用实际收到的源地址。CallAgent 调试页的 `Local IP` 应显示 `192.168.188.2`，而不是 `192.168.2.82` 或 `192.168.111.98`。

## 6. 发布文件校验

发布前可使用以下命令校验文件：

```text
sha256sum gateway.apk gateway-magisk.zip
```

当前构建文件：

```text
gateway.apk
b3dbfa1fc16874444ef63d18da639c7853a263711d402645cc08bc7de626f44a

gateway-magisk.zip
531d390b16a6b6e68c18f4f4b73656c0d77f23d3e9eddf12d178483f25e7ecbe
```

APK 版本应为 `2.8.68`（versionCode `346`），并通过 APK Signature Scheme
v2 验证。

v2.8.68 的 MI8 profile 保留 VOICE_DOWNLINK/VOICE_CALL 数字录音与
incall_music 数字注入；桥接期间断开 `CDC_IF TX6/TX7/TX8` 物理麦克风前端，
并关闭 MultiMedia1 到本机 QUAT 接收器/扬声器的渲染支路。`Voice Tx Mute`
保持关闭，因此数字注入不会随全局蜂窝 TX 一起被静音。SIP 200 OK 前必须完成
GSM ACTIVE、RTP socket、播放线程和捕获帧
就绪；重复启动请求在首次初始化完成前会被忽略。RTP 接收新增序列号回绕、
乱序/重复/迟到处理、有界抖动缓冲和短时丢包衰减补偿；启动 NAT priming 使用
有效的 PCMA A-law 静音并推进 RTP 序列号和时间戳。外拨媒体在 GSM 振铃阶段主动
预热，ACTIVE 后的关键路径不再执行全量 mixer dump，耗时 root 诊断延后到 SIP
接通之后；GSM/SIP 超时绑定通话代次，
旧通话的计时器不会中断下一次快速重拨。

## 7. 仍需在设备端完成的事项

CallAgent 代码可以固定编解码、修复桥接状态和选择兼容的 Android 音频输入，但不能代替设备端配置：

- Asterisk 必须只允许 PCMA，并确认密码一致。
- WPhone 必须使用正确的局域网 Contact/端口映射。
- Android 必须设置默认电话应用并授予权限。
- 模拟器缺少真实电话音频 HAL 时，不能仅靠 `tinymix` 创建不存在的硬件控件；最终音频能力应在真实 Android 设备上验证。
