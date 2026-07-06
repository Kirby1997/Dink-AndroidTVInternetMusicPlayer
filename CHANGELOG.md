# Changelog

All notable changes to Dink are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions match the app `versionName`.

## [1.2.2] - 2026-07-06

### Fixed
- **Dink now respects other apps' audio.** Starting playback in another app (for example
  SmartTube) pauses Dink instead of both playing over each other. Previously Dink never
  requested audio focus, so it talked over other apps and the two "playing" sessions
  fought over the remote's media keys, making playback hard to stop. Dink stays paused
  when the other app finishes and picks up focus again when you press play.
- **Mid-track stream errors fixed at the root.** Playback streams over its own dedicated
  SMB connection so background library walks, imports, cover art and tag reads can no
  longer close the connection under a live track. Transient network errors mid-stream now
  retry instead of skipping to the next song, and the idle-connection cleanup no longer
  evicts a connection that still has a file open.
- **Now Playing polish.** Focus returns to the queue after selecting an upcoming track,
  and the source badge shows SMB/CLOUD correctly for remote tracks instead of LOCAL.

## [1.2.1] - 2026-07-02

### Fixed
- **Playback no longer locks up after a network hiccup.** A transient error mid-stream
  (for example the NAS dropping an idle SMB connection) left the player in a dead state:
  play/pause did nothing and the only way out was picking a new song. The player now
  re-prepares the audio engine when recovering, so the automatic skip to the next track
  works and the play button always resumes.

## [1.2] - 2026-07-02

### Changed
- **Artists and Albums are now de-duplicated.** Grouping keys are normalised (case,
  punctuation, diacritics, leading "The", "feat." collaborators) so the same artist or
  album no longer appears several times under slightly different spellings. Collaboration
  tracks are attributed to the primary artist, and the most common raw spelling is shown.
  On a typical library this collapsed 543 artists down to 224. Keys are precomputed at
  import so the library grid loads without recomputing them each time.
- **Selecting an artist now opens that artist's albums** instead of a flat track list, so
  large discographies are easier to browse. Back returns to the previous screen with your
  scroll position and focus restored.

### Added
- **Session resume.** Relaunching Dink restores your last track, queue, and position
  (paused) so you can pick up where you left off.

### Fixed
- Tightened the library grid: smaller titles, more rows on screen, and names no longer
  clip. Down-navigation no longer drifts to the leftmost column.

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
