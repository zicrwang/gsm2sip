# SIP-GSM Gateway

Android app that bridges GSM calls (local SIM) with the callagent.pro SIP/Asterisk server.

| Dialer | SIP Registration |
|--------|-----------------|
| <img src="gsm2sip1.jpg" width="300"> | <img src="gsm2sip2.jpg" width="300"> |

## How It Works

A dedicated rooted Android phone with a local SIM card acts as a SIP-to-GSM gateway:

- **Inbound**: Someone calls the local number → phone auto-answers → bridges to Asterisk via SIP → AI agent handles the call
- **Outbound**: Asterisk sends SIP INVITE with `X-GSM-Forward: +<number>` header → phone dials the destination via GSM SIM → bridges audio back to SIP

Audio uses a device-specific bridge. On supported phones such as Xiaomi Mi 8,
privileged telephony capture and `incall_music` mixer controls provide the
digital path; other profiles can fall back to speaker/microphone routing. The
root module supplies the privileged permissions, audio tools, and concurrency
properties required by the bridge.

## Audio Codec

PCMA (G.711 A-law, 8 kHz, 20 ms/160-byte RTP frames) for compatibility with
Android audio HALs and Asterisk. Receive audio uses a sequence-aware bounded
jitter buffer before the existing deep-buffer `AudioTrack` path. Details and
acceptance metrics are documented in
[docs/RTP-AND-MEDIA-READINESS.md](docs/RTP-AND-MEDIA-READINESS.md).

## Requirements

- **Device**: Xiaomi Mi 8 (`dipper`, SDM845/Tavil) or Samsung Galaxy S10e with LineageOS + Magisk-compatible root
- **SukiSU Ultra v4.0.0**: use its built-in Magic Mount directly. Do not install `meta-overlayfs`; v4.0.0 predates SukiSU's metamodule integration.
- **SIM**: SIM card with voice plan
- **Network**: Stable WiFi connection
- **Power**: Always connected to charger
- **Build host**: Linux with JDK 17+

## Build

```bash
chmod +x build.sh
./build.sh          # debug build
./build.sh release  # release build
```

Outputs:
- `gateway-magisk.zip` — Magisk module containing the APK, permissions, and audio tools (tinymix, tinycap). This is the only file you need to install.

## Device Setup

Only the Magisk module needs to be installed — it includes the APK and handles all permissions automatically.

1. **Install root module**: Copy `gateway-magisk.zip` to the device and install it through Magisk, KernelSU, or SukiSU Manager
2. **Reboot** the device — the module installs the APK as a privileged system app and grants all permissions on boot
3. **Set as default phone app**: Settings → Apps → Default apps → Phone app → SIP-GSM Gateway
4. **Configure SIP and SIM**: Enter your Asterisk server details and select a fixed outgoing GSM SIM
5. **Start**: Tap START — the app registers with Asterisk and begins bridging calls

On dual-SIM devices, Gateway passes the selected SIM's `PhoneAccountHandle`
directly to Android Telecom. This avoids the system `SELECT_PHONE_ACCOUNT`
prompt, which would otherwise block unattended SIP-to-GSM calls.

### Web Debug Page

The APK included in the Magisk module contains a lightweight LAN debug page. After
rebooting and starting the gateway, tap **WEB** in the app settings, or open:

```text
http://<Android-phone-IP>:8787/
```

The page shows gateway state, SIP connection details, recent runtime logs, and
controls for start, stop, and statistics reload. The Android phone and browser
must be on the same LAN. The page is intended for local debugging and should not
be exposed directly to the Internet.

The standalone `gateway.apk` can be used for a quick Web Debug Page test, but
normal telephony use should install only `gateway-magisk.zip`, because the module
already contains the APK and supplies the required privileged audio permissions.

详细的三端部署、测试顺序、日志判断和 Asterisk 配置请参阅
[docs/DEPLOYMENT-AND-TROUBLESHOOTING.md](docs/DEPLOYMENT-AND-TROUBLESHOOTING.md)。

Xiaomi MI8 与 SukiSU Ultra v4.0.0 的挂载架构、安装验收和启动循环恢复请参阅
[docs/SUKISU-ULTRA-V4-MI8.md](docs/SUKISU-ULTRA-V4-MI8.md)。

## Asterisk Configuration

### 1. Create a SIP account for the gateway

Add to `sip.conf` or create via the realtime database:

```ini
[gateway-gw1](agent-template)
secret = <strong-password>
context = gateway-incoming
```

### 2. Add gateway dialplan context

Add to `extensions.conf`:

```ini
; Gateway incoming calls (GSM → SIP → Agent)
[gateway-incoming]
exten => _X.,1,NoOp(Gateway call from ${CALLERID(num)} via GSM SIM)
same => n,Set(CDR(destination)=${EXTEN})
same => n,Set(CDR(userfield)=gateway-gw1)
; Route to AI agent (same logic as incoming-calls)
same => n,Set(AgentToUse=${ODBC_AGENT_LOOKUP(gateway-gw1)})
same => n,GotoIf($["${AgentToUse}" = ""]?default_agent:route_to_agent)
same => n(route_to_agent),MixMonitor(/var/spool/asterisk/monitor/${STRFTIME(${EPOCH},,%Y%m%d-%H%M%S)}-${UNIQUEID}.wav)
same => n,Dial(SIP/${AgentToUse},60,tT)
same => n,Hangup()
same => n(default_agent),MixMonitor(/var/spool/asterisk/monitor/${STRFTIME(${EPOCH},,%Y%m%d-%H%M%S)}-${UNIQUEID}.wav)
same => n,Dial(SIP/100,60,tT)
same => n,Hangup()

; Outbound: Agent calls a number via the gateway
; The agent context already allows outbound calls:
;   Dial(SIP/gateway-gw1,,X-GSM-Forward: +1234567890)
; Or use a custom AGI/ARI to set the header.
```

### 3. Making outbound calls through the gateway

From Asterisk dialplan, to call a number via the gateway:

```ini
exten => _X.,1,NoOp(Outbound via GSM gateway: ${EXTEN})
same => n,SIPAddHeader(X-GSM-Forward: +${EXTEN})
same => n,Dial(SIP/gateway-gw1,60)
same => n,Hangup()
```

## Architecture

```
┌─────────────────┐     GSM      ┌──────────────────┐
│  Remote Caller   │◄───────────►│  Android Phone    │
│  (local #)       │   voice     │  (S10e + SIM)     │
└─────────────────┘              │                    │
                                 │  ┌──────────────┐ │
                                 │  │ InCallService │ │  GSM call control
                                 │  └──────┬───────┘ │
                                 │         │         │
                                 │  ┌──────▼───────┐ │
                                 │  │ Orchestrator  │ │  Bridges GSM ↔ SIP
                                 │  └──────┬───────┘ │
                                 │         │         │
                                 │  ┌──────▼───────┐ │
                                 │  │  SIP Client   │ │  Registration + calls
                                 │  │  RTP Session  │ │  PCMA audio stream
                                 │  └──────┬───────┘ │
                                 └─────────┼─────────┘
                                           │ SIP/RTP
                                           │ (WiFi)
                                 ┌─────────▼─────────┐
                                 │  Asterisk Server   │
                                 │  (CallAgent SIP)   │
                                 └─────────┬─────────┘
                                           │
                                 ┌─────────▼─────────┐
                                 │  AI Voice Agent    │
                                 └───────────────────┘
```

## Root Module

The `gateway-magisk.zip` module does two critical things:

1. **Disables audio concurrency restrictions** (`system.prop`):
   - `voice.voip.conc.disabled=false` — allows VoIP audio during GSM calls
   - `voice.record.conc.disabled=false` — allows audio recording during calls
   - `voice.playback.conc.disabled=false` — allows audio playback during calls

2. **Grants system-level permissions** (`privapp-permissions-gateway.xml`):
   - `CAPTURE_AUDIO_OUTPUT` — capture audio from other sources
   - `MODIFY_PHONE_STATE` — control telephony
   - `READ_PRECISE_PHONE_STATE` — detailed call state info

## Troubleshooting

- **One-way audio**: Ensure the Magisk module is installed and device is rebooted
- **Echo**: The app uses Android's AcousticEchoCanceler + VOICE_COMMUNICATION mode
- **SIP not registering**: Check WiFi connectivity, server address, and credentials
- **Calls not auto-answering**: Ensure the app is set as the default phone app
- **Audio drops**: Check WiFi stability; the app holds a WiFi lock but poor signal will cause issues
