# Changelog

All notable changes to Dink are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions match the app `versionName`.

## [1.1] - 2026-07-01

### Fixed
- Tracks tagged only with **ID3v1** or **APEv2** (older rips and ExactAudioCopy CDs)
  now show their real title / artist / album instead of the filename. Media3 reads
  only ID3v2-at-start, so these came through as filenames; a new tail-tag reader now
  parses ID3v1 and APEv2 over SMB without downloading the file. (~800 tracks fixed on
  a typical library.)
- Corrupt or mis-encoded tags no longer overwrite clean folder-derived names. Text is
  decoded UTF-8-first with a Latin-1 fallback, fields that still look like mojibake are
  rejected, and re-tag now self-heals any previously mangled name from the file path.

### Changed
- **Re-tag no longer re-checks the same tracks every run.** Each track is marked once it
  has been read, so a second press finishes instantly instead of re-scanning the whole
  library. A new **Force full re-tag** button (Settings → Library) re-reads everything —
  use it after editing tags on the server.
- Embedded-tag read timeout raised from 3s to 10s, so slow SMB reads (large embedded
  cover art on a busy NAS) complete instead of timing out.

## [1.0] - 2026-06-30

- Initial release: Android TV internet music player with SMB, cloud and local/USB/SD
  sources, embedded tag + cover-art reading over the network, lyrics (sidecar + online
  providers), audio EQ, D-pad navigation, and an in-app About/licenses screen.
