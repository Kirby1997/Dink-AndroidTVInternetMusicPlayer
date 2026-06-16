#!/usr/bin/env bash
# Build + install + launch Dink on the TV — but only when source changed since
# last successful deploy. Safe to call on every turn (Stop hook): it no-ops fast
# when nothing changed, so it never fires a 2-min gradle build after a Q&A turn.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 0

PKG=com.example.dink_smb_player
ACT=$PKG/.MainActivity
APK=app/build/outputs/apk/debug/app-debug.apk
STAMP=.deploy-stamp
SERIAL=${DINK_SERIAL:-192.168.138.95:5555}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}

# Android Studio (Windows) rewrites local.properties to the Windows SDK path,
# which Linux gradle can't use (build-tools are .exe). Pin the WSL SDK path here
# before every build so WSL and AS can coexist — each fixes the file for itself.
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

# --- change gate -----------------------------------------------------------
newest=$(find app/src -type f \( -name '*.kt' -o -name '*.xml' \) -printf '%T@\n' 2>/dev/null | sort -nr | head -1)
newest=${newest%.*}
last=$(cat "$STAMP" 2>/dev/null || echo 0)
if [ "${newest:-0}" -le "${last:-0}" ]; then
  exit 0   # nothing changed; stay silent
fi

# Did a focus-relevant file change this round? Gate the focus smoke check on it
# so it runs only when nav/focus behavior could have regressed — captured BEFORE
# the stamp is rewritten. Covers the screens/components (ui/), the rail + nav
# model (nav/), and the app shell that owns focus routing (DinkApp.kt). No stamp
# yet (first deploy) counts as changed.
PKGDIR=app/src/main/java/com/example/dink_smb_player
FOCUS_PATHS="$PKGDIR/ui $PKGDIR/nav $PKGDIR/DinkApp.kt"
if [ -f "$STAMP" ]; then
  ui_changed=$(find $FOCUS_PATHS -name '*.kt' -newer "$STAMP" 2>/dev/null | head -1)
else
  ui_changed=$(find $FOCUS_PATHS -name '*.kt' 2>/dev/null | head -1)
fi

echo "[deploy] source changed — assembling…"
if ! ./gradlew :app:assembleDebug -q; then
  echo "[deploy] BUILD FAILED" >&2
  exit 1
fi

# Ensure device reachable (TV drops 5555 on sleep/reboot).
adb connect "$SERIAL" >/dev/null 2>&1
if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
  echo "[deploy] device $SERIAL offline — APK built, not installed" >&2
  echo "$newest" > "$STAMP"
  exit 1
fi

adb -s "$SERIAL" install -r -t "$APK" >/dev/null 2>&1 || { echo "[deploy] INSTALL FAILED" >&2; exit 1; }
adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$SERIAL" shell am start -n "$ACT" >/dev/null 2>&1
echo "$newest" > "$STAMP"
echo "[deploy] installed + launched $PKG"

# Lightweight auto focus-check when a UI/screen file changed (not the full tour).
if [ -n "$ui_changed" ] && [ -f tools/focuscheck.py ]; then
  echo "[deploy] UI changed — running focus smoke check…"
  python3 tools/focuscheck.py smoke
fi
