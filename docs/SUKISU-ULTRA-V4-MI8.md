# SukiSU Ultra v4.0.0 与 Xiaomi MI8 部署说明

本文记录 `gsm2sip` 在 Xiaomi MI8（`dipper`，SDM845/Tavil）、LineageOS 22.2、
SukiSU Ultra v4.0.0（Kernel Version `40090`）上的正确构建、安装和恢复流程。

## 1. 挂载架构

SukiSU Ultra v4.0.0 使用 `ksud` 内置的 Magic Mount。其启动顺序是：

1. 执行模块的 `post-fs-data.sh`。
2. 加载 `system.prop`。
3. 由内置 `magic_mount` 挂载模块的 `system/`。
4. 执行 `post-mount.sh` 和 `service.sh`。

因此，此版本应直接安装 `gateway-magisk.zip`，**禁止安装或启用
`meta-overlayfs`**。新版上游 KernelSU 的 metamodule 文档不适用于 SukiSU Ultra
v4.0.0。

参考：

- [SukiSU Ultra v4.0.0 Release](https://github.com/SukiSU-Ultra/SukiSU-Ultra/releases/tag/v4.0.0)
- [v4.0.0 内置 Magic Mount 启动代码](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/v4.0.0/userspace/ksud/src/init_event.rs#L143-L153)

Gateway 模块通过 Magic Mount 提供：

- `/system/priv-app/Gateway/Gateway.apk`
- `/system/etc/permissions/privapp-permissions-gateway.xml`
- `/system/bin/tinymix`
- 通话并发相关的 `system.prop`
- 启动后的运行时权限和 AppOps 配置

## 2. 构建约束

本项目在当前环境中禁止本机编译。所有 APK 和模块构建必须在
`36.139.119.122` 上完成。该主机可使用 `127.0.0.1:10808` HTTP/HTTPS 代理。

```bash
ssh root@36.139.119.122
cd /opt/gsm2sip
export http_proxy=http://127.0.0.1:10808
export https_proxy=http://127.0.0.1:10808
export GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808"
./build.sh release
```

提交正式构建前，在同一编译服务器运行 JVM 单元测试：

```bash
./gradlew testReleaseUnitTest
```

测试覆盖 RTP 扩展头/填充解析、乱序、重复包、丢包、序列号回绕、SSRC 切换和
缓冲溢出。当前发布版本应为 `2.8.66`（versionCode `344`）。

Release APK 必须经过签名。当前私有设备构建使用编译机上固定的 Android debug
keystore，便于后续版本使用同一签名升级；该签名不适合公开发行。

构建后必须验证：

```bash
/opt/android-sdk/build-tools/34.0.0/apksigner verify --verbose gateway.apk
/opt/android-sdk/build-tools/34.0.0/aapt dump badging gateway.apk | head -1
unzip -p gateway-magisk.zip module.prop
```

## 3. 安装到 MI8

MI8 的 ADB 地址为 `192.168.2.82:5555`，root 命令通过 `su -c` 执行。

优先通过 SukiSU Ultra Manager 的模块页面安装 `gateway-magisk.zip`。MI8 上
`ksud` 的实际位置为 `/data/adb/ksud`（`/data/adb/ksu/bin/ksud` 是指向它的
符号链接），但 SELinux 策略可能禁止普通 `su` 域直接执行该文件，因此不要把
命令行安装作为唯一流程，也不要为了安装长期关闭 SELinux。

安装前在 Manager 中确认 `meta-overlayfs` 已禁用，只启用 Gateway。若设备已
安装 meta-overlayfs，也可在重启前写入禁用标记：

```bash
adb connect 192.168.2.82:5555
adb -s 192.168.2.82:5555 push gateway-magisk.zip /data/local/tmp/gateway-magisk.zip
adb -s 192.168.2.82:5555 shell \
  'su -c "touch /data/adb/modules/meta-overlayfs/disable"'
```

重启后设置 Gateway 为默认电话应用：

```bash
adb -s 192.168.2.82:5555 shell \
  'su -c "cmd role add-role-holder --user 0 android.app.role.DIALER com.callagent.gateway 0"'
```

在 Gateway 的 SIP Configuration 中选择固定的 `Outgoing GSM SIM`。
默认使用 SIM 1；拨号时应用会将该卡槽对应的 `PhoneAccountHandle`
传给 Telecom，不能保留为系统的“每次询问”，否则双卡设备会停在
`SELECT_PHONE_ACCOUNT`，无法进入 GSM 拨号和音频桥接阶段。

MI8 上的实测映射为：SIM 1 对应 `subId=2`、PhoneAccount ID `2`
（CMCC），SIM 2 对应 `subId=1`、PhoneAccount ID `1`（China Unicom）。
v2.8.57 已验证选择 SIM 1 后 Telecom 直接进入 `DIALING`，不会再进入
`SELECT_PHONE_ACCOUNT`。

v2.8.58 在此基础上为 MI8 增加物理 Voice RX endpoint 静音，并保留
VOICE_DOWNLINK/VOICE_CALL 数字捕获和 incall_music 数字注入；挂断后 mixer
自动恢复。

## 4. 部署验收

以下检查必须全部通过：

```bash
# 系统应完整启动
adb -s 192.168.2.82:5555 shell getprop sys.boot_completed

# APK 必须来自 system/priv-app
adb -s 192.168.2.82:5555 shell pm path com.callagent.gateway

# 检查版本、SYSTEM/PRIVILEGED 标志及特权权限
adb -s 192.168.2.82:5555 shell \
  'su -c "dumpsys package com.callagent.gateway | grep -E '\''versionCode=|versionName=|pkgFlags=|privateFlags=|CAPTURE_AUDIO_OUTPUT:|MODIFY_PHONE_STATE:'\''"'

# 检查默认电话应用
adb -s 192.168.2.82:5555 shell \
  'su -c "cmd role get-role-holders --user 0 android.app.role.DIALER"'

# 检查 MI8 双 SIM 通话注入控件
adb -s 192.168.2.82:5555 shell \
  'su -c "tinymix '\''Incall_Music Audio Mixer MultiMedia2'\''; tinymix '\''Incall_Music_2 Audio Mixer MultiMedia2'\''"'
```

预期结果：

- `sys.boot_completed=1`
- APK 路径为 `/system/priv-app/Gateway/Gateway.apk`
- 包含 `SYSTEM` 和 `PRIVILEGED`
- `CAPTURE_AUDIO_OUTPUT`、`MODIFY_PHONE_STATE` 为 `granted=true`
- 默认电话应用为 `com.callagent.gateway`
- 两个 `Incall_Music` 控件均可读取

## 5. MI8 音频适配

`DeviceProfile.sdm845Dipper()` 会在 `Build.DEVICE=dipper` 时启用。该配置：

- 在音频播放开始前同时打开 SIM1 和 SIM2 的
  `Incall_Music ... MultiMedia1`；当前 `deep_buffer` 音轨实际使用
  PCM0/MultiMedia1，并同时保留 MultiMedia2 以兼容不同音频策略
- 通话结束时关闭两个卡槽的 MultiMedia1/MultiMedia2，避免残留混音路由
- 桥接时将 `CDC_IF TX6/TX7/TX8 MUX` 置为 `ZERO`，只断开 Tavil
  物理麦克风前端，不使用会同时切断 incall_music 的全局 `Voice Tx Mute`
- 保留 MultiMedia1 到 incall_music 的数字支路，同时关闭
  `QUAT_MI2S_RX Audio Mixer MultiMedia1`，避免注入音频在 MI8 本机播放后
  再被任何残留麦克风声学回采
- 优先尝试 `VOICE_DOWNLINK` 和 `VOICE_CALL` 数字通话录音源
- 失败或静音时回退到 `VOICE_RECOGNITION`、`MIC` 和
  `VOICE_COMMUNICATION`
- 使用 SDM845/Tavil 对应的 mixer 诊断，而不是 Samsung ABOX 控件

最终仍需通过一次真实 GSM 与 SIP 双向通话验证上下行声音、增益和回声参数。
RTP 排序、丢包补偿和验收指标见
[RTP-AND-MEDIA-READINESS.md](RTP-AND-MEDIA-READINESS.md)。

## 6. PackageManager 旧缓存

若 `/system/priv-app/Gateway/Gateway.apk` 已是新文件，但 `dumpsys package` 仍显示
旧版本，说明 Android 复用了旧的包解析缓存。不要删除整个
`/data/system/package_cache`，也不要依赖 `su` 直接移动其中的文件；SELinux
通常会拒绝该操作。

先确认本地 `gateway.apk` 与模块内 APK 同签名、同版本，再使用 Android 标准更新
路径触发重新解析：

```bash
adb -s 192.168.2.82:5555 install -r gateway.apk
adb -s 192.168.2.82:5555 shell \
  'dumpsys package com.callagent.gateway | grep -E "codePath=|versionCode=|versionName=|pkgFlags=|privateFlags="'
```

更新后当前包可能显示为 `/data/app/...`，同时具有 `SYSTEM`、`UPDATED_SYSTEM_APP`
和 `PRIVILEGED` 标志；这是系统应用的同签名更新，特权权限应继续为
`granted=true`。模块中的 `/system/priv-app/Gateway/Gateway.apk` 仍作为系统基包
保留。若签名不同，`adb install -r` 会失败，此时必须回到相同 keystore 的编译机
重新构建，禁止卸载应用或清除 Gateway 数据来绕过签名检查。

## 7. 启动循环恢复

若启用模块后设备反复重启：

1. 在每次出现开机 Logo 后快速按音量减至少 3 次，触发 SukiSU/KernelSU
   安全模式。
2. 或在 ADB 在线窗口立即写入禁用标记：

```bash
adb connect 192.168.2.82:5555
adb -s 192.168.2.82:5555 shell \
  'su -c "touch /data/adb/modules/sip-gsm-gateway/disable /data/adb/modules/meta-overlayfs/disable"'
```

3. 恢复开机后确认 `ksud module list` 中相关模块为 `enabled: false`。

曾出现的启动循环是由不兼容的 meta-overlayfs 流程造成：ext4 镜像内容带有
`u:object_r:unlabeled:s0` 标签，并被重复挂载到 `/system`，导致 SELinux 拒绝
执行系统文件。SukiSU Ultra v4.0.0 使用内置 Magic Mount 后不经过该镜像。

## 8. 安全要求

- 禁止覆盖、删除、停止或禁用 Android PermissionController。
- 禁止同时启用 Gateway 的 Magic Mount 和 meta-overlayfs 载荷。
- 禁止把未签名 APK 放入 `/system/priv-app`。
- 模块升级前保留 ADB TCP 连接和音量键安全模式恢复手段。
- 没有 SIP 凭据时只能完成静态部署验收，不能声称双向通话已经通过。
