#!/system/bin/sh
# service.sh — KernelSU may start this before Android's framework is ready.
#
# Keeps the priv-app APK in sync when the user updates via 'adb install -r'.
# The updated APK goes to /data/app/ but the priv-app base in the Magisk
# overlay becomes stale.  This script copies the latest APK so the overlay
# is correct on the NEXT reboot.
#
# Also logs CAPTURE_AUDIO_OUTPUT grant status for debugging.

MODDIR="${0%/*}"
TAG="GatewayMagisk"
PKG="com.callagent.gateway"

MOD_VER=$(grep '^version=' "$MODDIR/module.prop" 2>/dev/null | cut -d= -f2)
log -t "$TAG" "SIP-GSM Gateway Magisk Module ${MOD_VER:-unknown} — service.sh running"

PRIV_DIR="$MODDIR/system/priv-app/Gateway"
PRIV_APK="$PRIV_DIR/Gateway.apk"

# KernelSU service scripts can run before PackageManager has scanned the
# mounted priv-app.  Use bounded waits so APK sync, grants and diagnostics do
# not produce false failures, while still allowing boot to finish if Android
# services never become ready.
WAITED=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] && [ "$WAITED" -lt 90 ]; do
    sleep 2
    WAITED=$((WAITED + 2))
done
if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
    log -t "$TAG" "Android framework ready after ${WAITED}s"
else
    log -t "$TAG" "Android framework wait timed out after ${WAITED}s; continuing"
fi

# ── Sync APK ──────────────────────────────────────────
# pm path returns the currently-active APK (may be /data/app/ update)
APK_PATH=""
WAITED=0
while [ "$WAITED" -lt 30 ]; do
    APK_PATH=$(pm path "$PKG" 2>/dev/null | head -1 | sed 's/^package://')
    if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
        break
    fi
    sleep 2
    WAITED=$((WAITED + 2))
done

if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
    if [ ! -f "$PRIV_APK" ]; then
        # No priv-app APK yet — copy it
        mkdir -p "$PRIV_DIR"
        cp "$APK_PATH" "$PRIV_APK"
        chmod 644 "$PRIV_APK"
        log -t "$TAG" "Created priv-app APK from $APK_PATH (reboot needed)"
    elif ! cmp -s "$APK_PATH" "$PRIV_APK" 2>/dev/null; then
        # APK was updated via adb install — sync it
        cp "$APK_PATH" "$PRIV_APK"
        chmod 644 "$PRIV_APK"
        log -t "$TAG" "Synced updated APK from $APK_PATH (reboot needed for priv-app refresh)"
    else
        log -t "$TAG" "Priv-app APK is up to date"
    fi
else
    log -t "$TAG" "Gateway app not installed — nothing to sync"
fi

# ── Grant runtime permissions automatically ───────────
# These normally require user approval via UI prompts.
# Granting them here avoids manual setup on a headless gateway.
for PERM in \
    android.permission.RECORD_AUDIO \
    android.permission.READ_PHONE_STATE \
    android.permission.READ_PHONE_NUMBERS \
    android.permission.READ_CALL_LOG \
    android.permission.CALL_PHONE \
    android.permission.ANSWER_PHONE_CALLS \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.ACCESS_COARSE_LOCATION \
    android.permission.POST_NOTIFICATIONS \
; do
    pm grant "$PKG" "$PERM" 2>/dev/null && \
        log -t "$TAG" "Granted: $PERM" || \
        log -t "$TAG" "Skip (already granted or N/A): $PERM"
done

# ── Force-allow RECORD_AUDIO via appops ───────────────
# Keep Android's PermissionController intact. The app reasserts this appop
# while a call is active, so a gateway does not need to disable a core
# system package on a general-purpose phone.
appops set --uid "$PKG" RECORD_AUDIO allow 2>/dev/null
appops set "$PKG" RECORD_AUDIO allow 2>/dev/null && \
    log -t "$TAG" "appops RECORD_AUDIO: forced allow (--uid + pkg)" || \
    log -t "$TAG" "appops RECORD_AUDIO: failed to set"

# Verification: wait 5 seconds and confirm the mode stuck.
(
    sleep 5
    MODE=$(appops get "$PKG" RECORD_AUDIO 2>/dev/null)
    log -t "$TAG" "appops RECORD_AUDIO verify: $MODE"
    if echo "$MODE" | grep -qi "foreground\|ignore\|deny"; then
        appops set --uid "$PKG" RECORD_AUDIO allow 2>/dev/null
        appops set "$PKG" RECORD_AUDIO allow 2>/dev/null
        log -t "$TAG" "appops RECORD_AUDIO: re-asserted after revert"
    fi
) &

# ── Ensure tinymix is available ────────────────────────
# tinymix is needed to control ABOX/ALSA mixer for incall_music injection.
# /system/bin/tinymix via Magisk overlay can hit SELinux "Permission denied"
# on some devices, so we install to /data/local/tmp/ which has a permissive
# context.  The app prefers /data/local/tmp/ in its discovery order.
# The bundled binary is ARM64 only — skip on 32-bit devices (e.g. S4 Mini).
DEVICE_ABI=$(getprop ro.product.cpu.abi 2>/dev/null)
if [ -f "$MODDIR/tinymix" ]; then
    case "$DEVICE_ABI" in
        arm64*|aarch64*)
            cp "$MODDIR/tinymix" /data/local/tmp/tinymix
            chmod 755 /data/local/tmp/tinymix
            chown root:root /data/local/tmp/tinymix
            log -t "$TAG" "tinymix: installed ARM64 binary to /data/local/tmp/tinymix"
            ;;
        *)
            log -t "$TAG" "tinymix: skipped (device ABI=$DEVICE_ABI, bundled binary is ARM64)"
            ;;
    esac
fi
TINYMIX_FOUND=false
for TPATH in /data/local/tmp/tinymix /vendor/bin/tinymix /system/bin/tinymix /system/xbin/tinymix; do
    if [ -x "$TPATH" ]; then
        TINYMIX_FOUND=true
        log -t "$TAG" "tinymix: using $TPATH"
        break
    fi
done
if [ "$TINYMIX_FOUND" = "false" ]; then
    log -t "$TAG" "tinymix: NOT FOUND — ABOX mixer controls will not work"
fi

# ── Ensure tinycap is available ───────────────────────
# tinycap is needed to probe ALSA capture PCMs for modem downlink audio.
# Same deployment strategy as tinymix: /data/local/tmp/ for SELinux compat.
if [ -f "$MODDIR/tinycap" ]; then
    case "$DEVICE_ABI" in
        arm64*|aarch64*)
            cp "$MODDIR/tinycap" /data/local/tmp/tinycap
            chmod 755 /data/local/tmp/tinycap
            chown root:root /data/local/tmp/tinycap
            log -t "$TAG" "tinycap: installed ARM64 binary to /data/local/tmp/tinycap"
            ;;
        *)
            log -t "$TAG" "tinycap: skipped (device ABI=$DEVICE_ABI, bundled binary is ARM64)"
            ;;
    esac
fi

# ── Log ALSA card info for diagnostics ────────────────
ALSA_CARDS=$(cat /proc/asound/cards 2>/dev/null)
if [ -n "$ALSA_CARDS" ]; then
    log -t "$TAG" "ALSA cards: $ALSA_CARDS"
fi

# ── Log privileged permission status ──────────────────
PERM_DUMP=$(dumpsys package "$PKG" 2>/dev/null)
for PERM in CAPTURE_AUDIO_OUTPUT MODIFY_PHONE_STATE READ_PRIVILEGED_PHONE_STATE CALL_PRIVILEGED; do
    if echo "$PERM_DUMP" | grep -q "$PERM.*granted=true"; then
        log -t "$TAG" "$PERM: GRANTED"
    else
        log -t "$TAG" "$PERM: NOT GRANTED — check priv-app install, reboot may be needed"
    fi
done

# Log install location for debugging
log -t "$TAG" "APK path: $APK_PATH"
log -t "$TAG" "Priv-app: $(ls -la $PRIV_APK 2>/dev/null || echo 'missing')"
