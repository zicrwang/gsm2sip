# RTP 与媒体就绪

本文描述 gsm2sip `2.8.68` 的 RTP 接收时间线，以及 SIP 接通前的媒体就绪边界。

## 固定媒体格式

- 编解码器：PCMA（G.711 A-law），payload type `8`；
- 时钟：8 kHz；
- 每包：160 字节，即 20 ms；
- 正确的 A-law 静音字节：`0xD5`。

RTP 解析会处理 CSRC、扩展头和 padding。payload type 或净负载长度不匹配的包
计入 `invalid`，不会送入 Android 音频输出。

## 接收时间线

`RtpJitterBuffer` 按 SSRC 和扩展序列号排序：

- 3 帧预缓冲，最大 5 帧；
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

干净局域网的目标是 `overflow=0`，`concealed` 与 `underrun` 接近 0，收发速率约
为每秒 50 包。

## 构建与验收

项目禁止本机编译。使用 `docs/SUKISU-ULTRA-V4-MI8.md` 指定的
`36.139.119.122` 构建服务器执行：

```bash
./gradlew testReleaseUnitTest
./build.sh release
```

自动测试通过后仍需在 MI8 完成外拨、来电、拒接、两端挂断和至少 10 分钟双向
语音测试，并同时观察 Asterisk 与 CallAgent 日志。
