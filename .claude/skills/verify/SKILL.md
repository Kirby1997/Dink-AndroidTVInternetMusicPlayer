---
name: verify
description: Build, deploy, and drive Dink on the real Android TV over adb to verify changes at the device surface.
---

# Verifying Dink on the TV

Package is `com.dink.player` (activity `com.example.dink_smb_player.MainActivity`). TV at `192.168.138.95:5555` (override with `DINK_SERIAL`).

## Build + deploy

```bash
./gradlew :app:assembleDebug -q
adb connect 192.168.138.95:5555
adb -s 192.168.138.95:5555 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.138.95:5555 shell am force-stop com.dink.player   # Apply Changes won't pick up structural edits
adb -s 192.168.138.95:5555 shell am start -n com.dink.player/com.example.dink_smb_player.MainActivity
```

Wait ~10–12 s after launch for session restore before driving playback.

## Driving playback

- `input keyevent 126` (PLAY) / `127` (PAUSE) / `85` (PLAY_PAUSE) — route via MediaSession. Keys drop if `dumpsys media_session | grep "Media button session"` is null (happens after another media app died); drive the UI instead.
- UI drive: `uiautomator dump /sdcard/ui.xml` + `cat` to find focus; `input keyevent 22/21/20/19` (d-pad) + `23` (select). Home screen has a RESUME tile right of the rail.
- `tools/focuscheck.py` is the D-pad crawler for nav/focus bugs.

## Observing state

```bash
# Playback state: state=3 playing, state=2 paused; position/speed confirm
adb -s $SERIAL shell dumpsys media_session | grep -A9 "package=com.dink.player" | grep state=PlaybackState

# Audio focus stack (who holds focus, attrs)
adb -s $SERIAL shell dumpsys audio | grep -A8 "Audio Focus stack"
```

## Second-app focus test

SmartTube installed: `org.smarttube.stable` (and `.beta`). `monkey -p org.smarttube.stable 1` to launch, ~15 s to home, `keyevent 23` starts the focused tile playing. Kill with `am force-stop`.

## Gotchas

- Deploy/verify `com.dink.player`, NOT `com.example.dink_smb_player` (orphan package trap).
- Leave the TV paused when done — playback keeps going on the user's real TV otherwise.
