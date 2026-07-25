#!/system/bin/sh
# Installation customization for Magisk and KernelSU.
# The module manager extracts the archive before sourcing this script.

ui_print "- SIP-GSM Gateway Module ${MOD_VER:-$(grep '^version=' "$MODPATH/module.prop" 2>/dev/null | cut -d= -f2)}"
ui_print ""

PRIV_DIR="$MODPATH/system/priv-app/Gateway"
PRIV_APK="$PRIV_DIR/Gateway.apk"

if [ -f "$PRIV_APK" ]; then
    ui_print "- APK found in module"
else
    ui_print "- APK not in module, searching installed apps..."
    APK_PATH=$(pm path com.callagent.gateway 2>/dev/null | head -1 | sed 's/^package://')
    if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
        mkdir -p "$PRIV_DIR"
        cp "$APK_PATH" "$PRIV_APK"
        ui_print "- Copied installed APK to priv-app"
    else
        abort "! Gateway APK not found in module or installed apps"
    fi
fi

rm -f "$MODPATH/skip_mount"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/tinymix" 0 0 0755
set_perm "$MODPATH/tinycap" 0 0 0755
set_perm "$MODPATH/system/bin/tinymix" 0 0 0755

ui_print "- Privileged permissions and audio tools configured"
ui_print "- Reboot required to activate"
