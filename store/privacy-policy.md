# Dink — Privacy Policy

_Last updated: 16 June 2026_

Dink ("the app") is a music player for Android TV. This policy explains what the app does
with your data. The short version: **Dink does not collect, store, or share your personal
data on any server we control. There is no account, no analytics, and no advertising.**

## Data stored on your device
- **Network credentials** (usernames and passwords for SMB/network shares, and any cloud
  tokens) are stored **encrypted on your device** using Android's hardware-backed
  keystore. They are never transmitted to us and are excluded from device backups.
- **Your music library index and app settings** are stored locally so the app remembers
  your sources and preferences.

We (the developer) have no access to any of the above.

## Network connections the app makes
Dink connects to the network only to do the things you ask it to:
- **Your own servers / shares (SMB):** to browse and stream the music files you point it
  at. These connections go to addresses you configure, not to us.
- **Online lyric providers (optional):** when you enable online lyrics, the app sends the
  **song title and artist** to third-party lyric services to look up matching lyrics.
  These services have their own privacy policies. You can disable online lyric lookups,
  or individual providers, in Settings.
- **Cover-art / metadata** is read from your files; album art may be fetched over your
  network from the same sources as your music.

Dink does not upload, copy, or back up your music files anywhere.

## Permissions
- **Storage / media access** — to find and play music on the device, USB, or SD card.
- **Internet / network state** — for streaming from your shares and optional lyric
  lookups.
- **Foreground service** — to keep music playing when the app is in the background.

## Children
Dink is a general-audience music player and is not directed at children. It collects no
personal information.

## Changes
If this policy changes, the updated version will be posted at this URL with a new date.

## Contact
Questions: jj@wilkinsons.me.uk

---

### How to host this (you need a public URL for the Play listing)
Play requires a publicly reachable privacy-policy URL. Easiest options:
1. **GitHub Pages / Gist** — push this repo to GitHub, enable Pages, link to the rendered
   file (e.g. `https://<user>.github.io/dink/privacy-policy`). A public Gist URL also works.
2. **Your own site** — host the rendered HTML at e.g.
   `https://wilkinsons.me.uk/dink/privacy`.
Paste whichever URL into Play Console → Store listing → Privacy policy.
