# RTP 与媒体就绪

本文描述 gsm2sip `2.8.72` 的 RTP 接收时间线，以及 SIP 接通前的媒体就绪边界。

## 固定媒体格式

- 编解码器：PCMA（G.711 A-law），payload type `8`；
- 时钟：8 kHz；
- 每包：160 字节，即 20 ms；
- 正确的 A-law 静音字节：`0xD5`。

RTP 解析会处理 CSRC、扩展头和 padding。payload type 或净负载长度不匹配的包
计入 `invalid`，不会送入 Android 音频输出。

## RFC2833 DTMF

SDP 会从首个 `m=audio` 媒体段解析协商的 `telephone-event/8000` 动态 payload
type。RTP 接收循环在音频抖动缓冲前分流这些事件包，并只在结束位出现时触发
按键。RFC2833 通常重复发送结束包，网关按 SSRC、RTP timestamp 和 event 编号
去重。

事件 `0..9`、`10`、`11` 分别映射到 `0..9`、`*`、`#`，再通过活动 Android
Telecom `Call` 播放 160 ms DTMF。未协商 telephone-event、GSM 尚未 ACTIVE、
负载不足 4 字节或 A-D 事件均不会触发蜂窝按键。

## 接收时间线

`RtpJitterBuffer` 按 SSRC 和扩展序列号排序：

- 初始目标 3 帧，按 RFC 3550 jitter、迟到和欠载在 3 至 6 帧自适应，容量 12 帧；
- 支持 16 位序列号回绕；
- 重复包和播放截止后的迟到包不进入输出；
- 短缺包用上一帧 PCM 乘以 `0.72` 衰减补偿；
- 连续欠载后重新预缓冲；
- SSRC 改变或大序列跳变时重新同步。

该队列只修正网络到达抖动。排序后的 20 ms 帧仍写入现有深缓冲
`AudioTrack`，以满足 MI8 数字通话路径的调度稳定性；两者不能互相替代。

## 媒体就绪边界

外拨时可以在 GSM 振铃阶段预热 RTP，但只有以下条件全部满足，CallAgent 才确认
桥接媒体就绪并发送 SIP 200 OK：

1. GSM 通话进入 `ACTIVE`；
2. RTP socket 和播放线程已启动；
3. `AudioTrack` 可写；
4. `AudioRecord` 至少产生一帧捕获数据。

外拨 INVITE 已携带远端 RTP 地址，因此 CallAgent 会在 Telecom 建立拨号音频用例后
主动预热 AppOps、AudioRecord、AudioTrack 和 RTP socket，而不再只依赖可能缺失的
`DIALING` 回调。GSM 进入 `ACTIVE` 后只重设必要的 incall_music 控件并等待一帧
ACTIVE 之后的捕获数据。耗时的全量 mixer、PCM 和 ALSA 诊断只在 SIP 已确认接通后
启动，避免串行 root 命令阻塞 ACTIVE 阶段的 incall_music 重设和 SIP 200 OK。

所有 GSM/SIP 拨号超时都绑定到通话代次和对应 SIP 通话对象。上一通遗留的延迟任务
无法结束下一通快速重拨。

`X-WPhone-Media-Ready` 和 `X-CallAgent-Media-Ready` 仅用于日志诊断。当前
Asterisk dialplan 不保证跨两条呼叫腿复制任意自定义响应头，通话正确性不能依赖
这些头。

## 指标

通话统计包含：

- `tx` / `rx`：RTP 收发包；
- `writes`：成功写入 AudioTrack 的帧；
- `overflow`：有界队列主动丢弃；
- `duplicate` / `reordered` / `late`：重复、乱序和迟到；
- `concealed` / `underrun`：丢包补偿和无后续包欠载；
- `resync` / `ssrcChanges`：序列大跳变和 SSRC 切换；
- `jitterMs`：RFC 3550 风格的到达抖动估算。
- `dtmf`：已去重并转发到 GSM 通话的 RFC2833 按键数。

干净局域网的目标是 `overflow=0`，`concealed` 与 `underrun` 接近 0，收发速率约
为每秒 50 包。

每 5 秒记录一次周期统计。通话停止时先终止 RTP 工作线程，再从会话计数器和
抖动缓冲生成不可变快照，并输出一次带 `final=true` 的最终统计；停止流程不再
清零抖动缓冲计数，也不会让休眠中的统计线程在挂断后继续执行 appops。

## MI8 捕获源可靠性

MI8 profile 固定使用已验证的数字 `VOICE_DOWNLINK`，不允许切换到可能包含蜂窝
上行的 `VOICE_CALL`，也不允许切换到 `VOICE_RECOGNITION`、`MIC` 或
`VOICE_COMMUNICATION` 等物理麦克风路径。连续低电平是合法的通话静音，只输出
限频诊断并保持当前源。

只有 `AudioRecord.startRecording()` 失败、录音状态错误或 `read()` 返回硬错误才
执行同源恢复。同一个 `VOICE_DOWNLINK` 连续三次硬故障后记录
`Capture fatal ... action=hangup`。若阻塞式 `read()` 连续 5 秒没有产生任何帧，
独立看门线程会直接按不可恢复停滞挂断。`CallOrchestrator` 会核对当前 RTP 会话身份
并同时结束 SIP 与 GSM 呼叫，不能以静音 RTP 无限维持半通话。

## 已实施：音频启动与抖动缓冲优化

### 真机基线

2026-07-27 使用 MI8、WPhone、Asterisk 和 `10086` 完整链路验证：

- WPhone 冷启动音频图在一次恢复后于 320 ms 就绪，随后成功发送 INVITE；
- WPhone 热启动预热耗时为 0 ms；
- MI8 从 GSM `ACTIVE` 到发送 SIP 200 OK 为 1293 ms；
- 其中 `Media ready gate` 为 1276 ms，ACTIVE 后约 12 ms 已取得首个捕获帧；
- root 音频诊断已经在 SIP 接通后启动，不再直接阻塞 SIP 200 OK。

实现已将两个方向拆分：`gsmToSipReady` 只等待 RTP/播放线程和 ACTIVE 后捕获帧，
`sipToGsmReady` 在独立音频优先级线程完成 HAL 参数及 mixer 写入。后者未成功前
AudioTrack 只写数字静音，不会回退到物理麦克风。

### 启动优化顺序

1. 媒体门控已拆分为 `gsmToSipReady` 和 `sipToGsmReady`。GSM `ACTIVE` 后，只要
   RTP socket、播放线程和 ACTIVE 之后的 AudioRecord 捕获帧已就绪，即可发送
   SIP 200 OK；TX 注入路由同时完成，未就绪期间必须保持数字静音，不能回退到
   物理麦克风。
2. SIP 200 OK 前的 root mixer 命令只保留 `ABOX NSRC0 -> SIFS0` 和
   `ABOX NSRC1 -> SIFS0` 两次写入；100 ms 等待已删除，四项读回移到接通后。
3. 关键写入使用独立 `su -c` 通道，不与 discovery、完整 `tinymix`、PCM/ALSA
   诊断共用 FIFO。
4. root 关键结果包含退出码、输出、耗时和超时标记，并要求显式成功标记；超时、
   非零退出或空输出均保持 `sipToGsmReady=false` 和数字静音。
5. 网关在 RTP socket 预热成功后发送带 SDP 的 SIP 183；WPhone 可在最终 200 OK
   前启动 early media。最终应答仍只由 GSM ACTIVE 后的 `gsmToSipReady` 决定。

### 抖动缓冲方向

- 队列容量为 12 帧，初始目标为 3 帧，根据 RFC 3550 jitter、`late` 和
  `underrun` 在 3 至 6 帧间调整；异常时快速增加，稳定至少 10 秒后逐帧降低。
- Android RTP 收包、AudioTrack 播放和 AudioRecord 捕获线程均使用音频优先级。
- 队列超过容量时执行一次受控时间线重同步，保留当前目标深度，不逐包推进期望
  序号，避免 `overflow -> concealed -> late` 连锁。
- 当前上一帧乘以 `0.72` 的补偿只适合短缺包。连续丢包应逐步过渡到语音周期
  复制、重叠淡化和舒适噪声；若本地实现仍不稳定，再评估成熟的语音抖动缓冲库。

### 阶段验收目标

- `GSM_ACTIVE->SIP_200`：常态小于 300 ms，P95 小于 500 ms；
- ACTIVE 后首个 GSM 到 SIP RTP 音频帧：小于 100 ms；
- 干净局域网 10 分钟通话：`overflow=0`，`late`、`concealed` 和 `underrun`
  各自低于接收包数的 1%；
- 冷启动、连续快速重拨、锁屏和录音开启场景分别验收，不能只使用热启动结果；
- 所有优化必须继续保证物理麦克风不会混入蜂窝上行。

## 构建与验收

项目禁止本机编译。使用 `docs/SUKISU-ULTRA-V4-MI8.md` 指定的
`36.139.119.122` 构建服务器执行：

```bash
./gradlew testReleaseUnitTest
./build.sh release
```

自动测试通过后仍需在 MI8 完成外拨、来电、拒接、两端挂断和至少 10 分钟双向
语音测试，并同时观察 Asterisk 与 CallAgent 日志。
