# Dink — Build Phases (Plan of Record)

Reconstructed from yesterday's planning transcripts (2026-05-21). Source of truth for what each phase delivers and what's still mocked at each boundary.

| Phase | Scope | Status |
|-------|-------|--------|
| 1 | **Foundation** — design tokens, `DinkTheme`, four font families, focus ring, `MainActivity` → `DinkApp`. | done |
| 2 | **Shell** — `SideRail` (12 screens, 4 groups), `TopBar` crumbs, `MiniPlayer`, `ExitConfirmDialog`, `ToastHost`, `DinkNav` back-stack. | done |
| 3 | **Data layer + mock seed** — `data/model/*`, in-memory `MediaIndex` (Room blocked by AGP 9), `EncryptedShareStore`, `SharePrefs` DataStore, `PreviewMockData`. | done |
| 4 | **Home + Now Playing + procedural album art** — Hero (blurred art + Resume + 3 CTAs), 3 shelves, `LyricsPane`, 3-pane NowPlaying, `PlayerState` synthetic clock, 7 procedural `AlbumArtShape`s. | done |
| 5 | **Songs + Shuffle All** — `SongsScreen` (Header + Shuffle All + sort/filter `Seg` + LazyColumn rows). `Seg` component. Expanded mock data. | done |
| 6 | **Player (Media3) + minimal lyrics (sidecar + ID3)** — replace synthetic clock with `ExoPlayer`, `PlayerService extends MediaSessionService`, `PlayerViewModel` StateFlow surface, queue + shuffle + repeat + gapless, sidecar `.lrc` + ID3 USLT lyric loader. Verify with local-asset MP3 before SMB. | done |
| 6.5 | **Local + removable storage (first-class source)** — `READ_MEDIA_AUDIO` + `usb.host` manifest entries, `LocalStorageScreen`, `UsbMountReceiver` (MEDIA_MOUNTED/UNMOUNTED/EJECT/REMOVED), `LocalSyncWorker` (WorkManager unique-work, REPLACE policy). Rail order: Local → SMB → Cloud. | done |
| 7 | **SMB shares + Add-share wizard + smbj** — `SmbSharesScreen` stats strip + 3-col card grid, 5-step `AddShareWizard`, smbj client, persistable creds in `EncryptedShareStore`. Validates competitor pain #1 (share-forgetting). | done |
| 7.x | **LAN auto-discovery + UX polish** — `LanScanner` (NSD `_smb._tcp.` + TCP-445 subnet sweep, 32-concurrent, /24 derived from `ConnectivityManager.LinkProperties`). Discover button on host/port wizard step opens an inline picker. `SmbBrowseScreen` per-share file browser (post-save destination). TV tap-to-edit text fields (suppress IME until Enter). `DinkNavState.replaceTop` so post-wizard back doesn't re-enter wizard. `DinkApplication.appScope` for save coroutines so DataStore writes survive composition departure. | done |
| 7.5 | **Library unification + Import/Monitor** — wire the orphaned `MediaIndex`/`IndexDao` as single source of truth for playable tracks. Source screens become **browsers, not players**: `SmbBrowseScreen` is a lazy folder browser (`SmbBrowser.list(path)`, no upfront 50k walk) with per-folder **Import** + per-share **Monitor**; share ops (Re-import / Monitor / Delete) move off the cramped card. `SmbImporter` walks only the chosen scope → `LibraryRepository.importSource` (upsert + prune). `MonitorWorker` (WorkManager periodic, 6h, network-constrained) re-imports monitored shares + local MediaStore. `PlayerState` **windowed engine queue** (±200 around current) so shuffle-all over a whole-library queue can't ANR. Library screens read `LibraryRepository`. Cloud import folds into Phase 8. | done |
| 8 | **Cloud Storage + Connect modal + provider clients** — `CloudScreen` (Connected / Needs attention / Available), 4-up grid w/ brand glyphs, OAuth device-flow for Google Drive (validates Phase 8 milestone). **Streaming, not downloading** — index only, bytes stream on demand. | in progress |
| 8.5 | **Lyrics subsystem (foo_openlyrics port)** — full provider chain: sidecar → online (LRCLIB → NetEase → QQ → Musixmatch) → ID3 → online plain → sidecar txt. Per-provider toggle in Settings. | done |
| 8.7 | **Tag/metadata reading** — read embedded ID3/Vorbis/MP4 tags so title/artist/album/track/year are accurate instead of filename/folder-derived. Fixes Artists/Albums grouping for SMB+cloud and unlocks the URL-slug lyric providers. | done |
| 9 | **Stubbed screens** — Search, Albums, Artists, Playlists, Folders, Settings — proper screens in the same design language, library-detail screens included. | done |
| 10 | **Polish** — light theme behind Settings toggle, D-pad audit per README §10, ProGuard rules. | done |
| 11 | **Audio EQ / tuner** — multi-band graphic equalizer over the ExoPlayer audio pipeline (`androidx.media3` audio processor or `android.media.audiofx.Equalizer` on the session id), presets (Flat / Rock / Vocal / Bass / custom), per-output persistence, TV-friendly band sliders in Settings. | done |
| 12 | **Cast from mobile device** — Ability to cast a song from a mobile device. The goal of this is to allow karaoke from a mobile device. This will need much more planning. Maybe a premium feature? Need to monetise the app in some way.

## Cross-phase notes

- **Gapless playback**: budgeted into Phase 6 via Media3 `concatenatingMediaSource`.
- **Lyrics**: sidecar `.lrc` + ID3 USLT in Phase 6; full online chain in Phase 8.5.
- **Verification gate for Phase 6**: play a local-asset MP3 first to confirm Media3 wiring before depending on SMB.
- **Verification gate for Phase 7**: connect to real LAN SMB share, restart app, share reconnects without re-entering credentials.
- **Verification gate for Phase 8**: complete real Google Drive device-flow login, files index, restart still connected.
- **Verification gate for Phase 8.5**: track without sidecar/ID3 lyrics gets synced lyrics from LRCLIB within ~2s; toggle LRCLIB off → fall through to NetEase.
- **No automated UI tests in v1** — TV UI tests are expensive to maintain at this fidelity; rely on compile/lint + manual play-through on the AVD.

## Mock data lifetime

`PreviewMockData` is `BuildConfig.DEBUG`-gated. Phase 10 strips it from release builds at call sites. Until then, every screen falls back to it when MediaIndex is empty.

## Numbering correction (2026-05-22)

The queue + shuffle + repeat work landed earlier in this session under the label "Phase 6" but is actually a sub-component of Phase 6. The remaining Phase 6 deliverables are the Media3 swap and sidecar/ID3 lyric loading. Phase 6 is **in progress**, not done.

## 6 + 6.5 completion (2026-05-23)

Phase 6 closed: `PlayerService` runs `ExoPlayer` (audio-only `RenderersFactory`) under `MediaSessionService`; `LyricChain` wired with sidecar → ID3 → LRCLIB → Txt. Phase 6.5 closed: `usb.host` feature declared, `UsbMountReceiver` registered for MEDIA_MOUNTED/UNMOUNTED/EJECT/REMOVED (file scheme), `LocalSyncWorker` runs MediaStore re-query as unique work (REPLACE policy collapses mount-burst duplicates), enqueued at app boot from `DinkApplication.onCreate`. Next: Phase 7 SMB shares + smbj.

## Phase 7 scaffolding (2026-05-23)

Landed:
- `data/source/smb/SmbClient.kt` — smbj wrapper, cached `DiskShare` per share id, `test()` for wizard verification step, 30 s connect/SO timeouts.
- `data/source/smb/SmbSync.kt` — recursive audio enumeration (mp3/flac/ogg/oga/opus/m4a/wav/aac/wma), depth ≤ 8, file cap 50k, emits Song with `smb://host:port/share/...?sid=<shareId>` URIs.
- `data/source/smb/SmbDataSource.kt` — Media3 BaseDataSource backed by smbj `File.inputStream`; resolves `?sid=` via `SmbConnectionRegistry`; recreates stream + `skip()` for seek.
- `data/source/smb/DinkDataSourceFactory.kt` — scheme router; wired into `PlayerService` ExoPlayer via `DefaultMediaSourceFactory.setDataSourceFactory`.
- `data/SharesLibrary.kt` — per-share Song lists + syncing/error state for SmbSharesScreen.
- `ui/screens/sources/SmbSharesScreen.kt` — stats strip + 3-col grid + share cards (status pill / track count / Sync button).
- `ui/screens/sources/AddShareWizard.kt` — 5-step wizard (Host/Port → Share → Auth → Credentials → Test/Save); skips step 4 on Guest; persists to `SharePrefs` + `EncryptedShareStore`; pushes new share into `SmbConnectionRegistry` so playback is immediate.
- `DinkApp` boots the registry: installs `EncryptedShareStore` cred lookup once + collects `SharePrefs.shares` to mirror state into registry.
- `slf4j-nop` runtime-only dep so smbj's slf4j-api 1.7.36 binds to a no-op logger.

Pending verification gate (real-LAN test): connect to a real SMB share via the wizard, restart app, share reconnects without re-entering creds, playback streams. Will close Phase 7 once gate confirmed on AVD or device.

## Phase 7.5 scaffolding (2026-05-26)

Triggered by an ANR + crash after syncing a ~28k-track NAS share: the whole-share flat enumeration fed `PlayerState.playFrom` → `_queue.addAll(28k)` + `setMediaItems(28k)` on the UI thread (6 s spin → "Waited 5002ms for KeyEvent"). Fix is architectural — playback should not originate from source views over an unbounded flat list.

Landed:
- `data/library/LibraryRepository.kt` — wraps the previously-orphaned `MediaIndex`/`IndexDao` as the single source of truth. `Song`↔`TrackEntity` bridge; `importSource` (upsert + prune + stats); `trackIdFor` deterministic PK.
- `data/source/smb/SmbBrowser.kt` — lazy one-directory listing (dirs + audio), folders-first sort. Replaces the upfront tree walk for browsing.
- `data/source/smb/SmbImporter.kt` — walks only `SmbShare.importPaths` → `TrackEntity` (size from `endOfFile`, metadata from path; duration filled on first play).
- `data/source/MonitorWorker.kt` — periodic (6 h, network-constrained) re-import of monitored shares + local MediaStore; enqueued at boot from `DinkApplication`.
- `SmbShare.importPaths` + `monitored` fields (serializable defaults, back-compatible with existing blobs).
- `SharesLibrary` — `importFolder` / `reimport` / `setMonitored` + `importingShares` state; `deleteShare` now also drops the index source.
- `MediaLibrary.refresh` mirrors local MediaStore into the index (source id `local-mediastore`).
- `PlayerState` — windowed engine queue (`engineWindow = 400`, `engineBase`): only a slice is handed to ExoPlayer; cross-window advance via `onTrackEnded → moveTo`; engine repeat/shuffle forced off when windowed (handled queue-wide). Big shuffle is baked into queue order at `playFrom`.
- `SmbBrowseScreen` rewritten as a folder browser with per-folder Import + share-level Monitor / Re-import / Delete (moved off the card). `SmbSharesScreen` card reduced to a single **Open** target.
- `SongsScreen` reads `LibraryRepository.songs` (local + imported SMB) instead of `MediaLibrary.localSongs`.

Pending: HomeScreen still mock-driven (resume/shelves) — moves to index-backed feeds in Phase 9; cloud import in Phase 8. Verification gate: import an SMB folder → tracks appear in Songs & play; add a file on the NAS → MonitorWorker imports it; shuffle-all of a >400-track library no longer ANRs.

## Phase 7 family closed (2026-06-11)

Phases 7, 7.x, 7.5 all marked **done** in the table. Verification gate **passed**: real NAS share over LAN connects via the wizard, survives app restart without re-entering creds, folder import lands tracks in Songs & plays, >400-track shuffle-all no longer ANRs (windowed engine queue). Library index persists across restart (`library_index.json` atomic write). Folder-scoped import/re-import shipped same day. `compileDebugKotlin` green. Deferred (not Phase 7 gaps): HomeScreen index-backed feeds → Phase 9; `SmbClient.preferProtocol` dialect pin (no caller, smbj auto-negotiates). Next: **Phase 8 — Cloud Storage**.

## Phase 8 scaffolding (2026-06-11)

Cloud = **streaming, never download** (user decision: TV storage is tight). Connecting a provider only INDEXES its audio; playback streams on demand. Same model as SMB.

Landed (compiles + deployed to TV, CloudScreen + Connect modal verified on-device):
- `data/source/cloud/GoogleDriveClient.kt` — OAuth 2.0 limited-input device flow (`requestDeviceCode` → `pollOnce` loop honouring interval/slow_down → token; `refresh`), Drive v3 audio listing (paged, `mimeType contains 'audio/'`, 20k cap), `about` for account email, `downloadUrl`. OkHttp + org.json. Client id/secret from `BuildConfig.GOOGLE_OAUTH_CLIENT_ID/SECRET`.
- `data/source/cloud/CloudConnectionRegistry.kt` — `?pid=` → `CloudProvider` + token; `validAccessToken` refreshes + persists a near-expiry token before a stream opens. Mirror of `SmbConnectionRegistry`.
- `data/source/cloud/CloudDataSource.kt` — `gdrive://file/<fileId>?pid=<providerId>` Media3 `BaseDataSource`; HTTP Range streaming w/ bearer, seek = fresh ranged request, no local cache. Wired into `DinkDataSourceFactory` scheme router (`gdrive` branch).
- `data/source/cloud/CloudImporter.kt` — DriveFile → `TrackEntity` (uri = gdrive://…, path = "<Provider>/<file>", albumTitle = provider). Index-only.
- `data/source/cloud/CloudProviderSpec.kt` — provider catalog; Google Drive `available=true`, Dropbox/OneDrive/Box `Coming soon`. Brand-tinted procedural glyphs (no trademarked bitmaps).
- `data/CloudLibrary.kt` — device-flow driver + per-provider index/error state (mirror of `SharesLibrary`). `connect` (Requesting→AwaitingAuth→Indexing→Done/Failed/NotConfigured), `reindex`, `deleteProvider`. Own SupervisorJob scope.
- `ui/screens/sources/CloudScreen.kt` — Connected / Needs attention / Available sections + 4-up grid, glyph badges, Connect modal (shows user code + verification URL + poll). TV focus routing like SmbSharesScreen.
- Wiring: `ScreenId.Cloud` implemented=true; `DinkApp.ScreenHost` route; boot `LaunchedEffect` installs cloud token store + mirrors `SharePrefs.providers` into the registry (app-wide `?pid=` resolution). okhttp added to app deps (was in catalog only).
- Persistence layer (`CloudProvider`/`CloudToken`/`EncryptedShareStore.put/getCloudToken`/`SharePrefs.providers`) was already stubbed in Phase 3 — now consumed.

Pending verification gate (needs real Google OAuth client): set `DINK_GOOGLE_CLIENT_ID`/`DINK_GOOGLE_CLIENT_SECRET` (TVs-and-Limited-Input client type) in `~/.gradle/gradle.properties` (WSL build = `/home/linux/.gradle/gradle.properties`, NOT the committed repo `gradle.properties`), rebuild → Connect Google Drive via device flow → browse + import folders → a cloud track streams + survives restart. Until creds set, Connect surfaces a "Cloud not configured" modal (verified). Deferred: Dropbox/OneDrive/Box clients.

### Phase 8 folder-scoped cloud + rescan (2026-06-11, same day)

Cloud now mirrors SMB folder-scoping (user wanted per-folder monitoring, not whole-account). Compiles + deployed; CloudScreen renders on TV (browse/import need real creds to exercise).
- `CloudFolderRef(id, path)` + `CloudProvider.importFolders`/`monitoredFolders` (serializable defaults, back-compat). `id` = Drive folder id (or `"root"` = My Drive); `path` = name breadcrumb → drives `TrackEntity.path` so monitor reconcile scopes by prefix like SMB.
- `GoogleDriveClient.listChildren(folderId)` (folders + audio, paged) replaces whole-account `listAudio`. `FOLDER_MIME`, `ROOT_ID` consts.
- `CloudBrowser.list(provider, token, folderId, namePath)` — lazy one-folder listing, folders-first; songs playable (streamed) pre-import, ids match index PK.
- `CloudImporter` rewritten: recursive walk of `CloudFolderRef`s → `TrackEntity` (path = "<Provider>/<breadcrumb>/<file>"), `monitoredPrefix(provider, ref)`.
- `CloudLibrary`: connect no longer whole-account-indexes — it stores creds, sets `activeBrowseProviderId`, `Done(providerId)` → modal "Choose folders" navigates to browse. Added `importFolder`/`removeImportedFolder`/`setFolderMonitored` (mirror SharesLibrary, via `LibraryRepository.importScoped`).
- `CloudBrowseScreen` (mirror of `SmbBrowseScreen`): folder-id nav stack + name breadcrumb, per-folder Add-to-library/Monitor/Remove + Disconnect, In-library tick. `ScreenId.CloudBrowse` (Bottom), `railCurrentFor` maps it to Cloud, `DinkApp.ScreenHost` route. CloudScreen connected card = Open → browse.
- `MonitorWorker` now also rescans monitored cloud folders — installs the registry token store itself (cold-process safe) then `validAccessToken` (refreshes) + `refreshMonitored` per provider.
- `GlyphBadge` drawable-override hook: `res/drawable/ic_provider_<id>` (e.g. `ic_provider_googledrive`) renders the real vendor logo if present, else the procedural brand glyph. Brand VectorDrawables for Drive/Dropbox/Box added (Simple Icons CC0, brand-colored); OneDrive not on Simple Icons (procedural glyph).

### ⛔ Google Drive PARKED — device-flow scope wall (2026-06-11)

Tried the real device flow on a TV with a configured "TVs and Limited Input devices" OAuth client + `drive.readonly` on the consent screen. Google's token/device endpoint rejects it: **"Invalid device flow scope: …/auth/drive.readonly"**. Confirmed in [Google's docs](https://developers.google.com/identity/protocols/oauth2/limited-input-device): the limited-input device flow allows **only** `email`, `openid`, `profile`, `drive.appdata`, `drive.file`, and YouTube scopes. `drive`/`drive.readonly` are **not** permitted — so a TV (no browser) **cannot** browse/stream an existing Drive library via device flow. `drive.file` only sees app-created / picker-opened files. **Not fixable in the Console — it's Google policy.**

Decision (user, 2026-06-11): **park cloud**. Google Drive set `available=false` ("Needs phone sign-in"); all providers non-actionable for now. The full cloud subsystem (browser, importer, monitor, streaming `CloudDataSource`, device-flow plumbing) stays in place and compiles — it's provider-agnostic, so it'll light up for a provider whose device flow allows broad file scopes. Future paths if revisited: **OneDrive** (Microsoft device-code flow *does* allow `Files.Read.All` — best TV fit), **Dropbox** (PKCE + manual code paste), or **Google via companion sign-in handoff** (phone does full OAuth, relays token to TV — needs a relay). Next work: Phase 8.5 (lyrics) / Phase 9 (stub screens).

## Phase 8.5 — lyrics chain landed (2026-06-11)

foo_openlyrics-style online chain added behind the existing local sources. Compiles; Settings UI verified on TV (toggles render + flip + persist). **Runtime gate still open**: NetEase/QQ/Musixmatch fetch correctness needs a real track without sidecar/ID3 lyrics to play — exercise on-device.
- `OnlineLyricProvider` interface + `OnlineLyrics(synced, plain)` + `OnlineLyricProviders.all` (order: LRCLIB → NetEase → QQ → Musixmatch). Shared `LyricHttp` (OkHttp, 5s/8s timeouts) + `plainToLines`.
- `LrcLibProvider` wraps existing `LrcLibLookup`. `NeteaseLyrics` (music.163.com search + lyric, Referer header). `QqLyrics` (c.y.qq.com search + `nobase64=1` LRC, Referer). `MusixmatchLyrics` (apic-desktop token.get + macro.subtitles.get; unofficial/brittle → **default OFF**, ToS-grey). All reuse `LrcParser`; synced if timestamps present, else plain.
- `LyricChain.resolve` rewritten: sidecar .lrc → loop enabled online providers (stop at first synced, remember first plain) → ID3 USLT → online plain → sidecar .txt → distribute-if-flat.
- `LyricSettings` (in-memory @Volatile flags — chain runs sync on IO, can't read DataStore) + `LyricPrefs` (DataStore `lyric_<id>` booleans). `DinkApp` boot hydrates `LyricSettings` from prefs; Settings toggle writes both (instant + persisted).
- `SettingsScreen` gained a "Lyric sources" section: per-provider pill toggle rows (D-pad Center/Enter/Space), verticalScroll. Defaults: LRCLIB/NetEase/QQ/Genius on, Musixmatch off.

### Phase 8.5 follow-ups (2026-06-11)

- **Genius added** (`GeniusLyrics`) — search API + `text_format=plain` (public foo_openlyrics bearer token), plain-text. Reliable because it's a fuzzy /search, not a URL-slug lookup. Order: LRCLIB → NetEase → QQ → Musixmatch (synced) → Genius (plain).
- **Remaining foo_openlyrics sources** — added 2026-06-11 after Phase 8.7 made tags accurate. `LyricScrapers.kt` + `LyricHtml.kt` (slug + HTML→text helpers): **Lyricsify** (synced), **Letras** (synced/plain), **DarkLyrics** (plain, album page, track matched by title since `Song` has no track no.), **Metal Archives** (plain, AJAX search→id→lyrics), **AZLyrics** (plain, captcha-prone), **SongLyrics** (plain), **Bandcamp** (plain), **LyricFind** (plain, `__NEXT_DATA__` JSON). Full chain order — synced: LRCLIB→NetEase→QQ→Lyricsify→Letras→Musixmatch; plain: Genius→DarkLyrics→MetalArchives→AZLyrics→SongLyrics→Bandcamp→LyricFind. Defaults ON: LRCLIB/NetEase/QQ/Lyricsify/Genius/DarkLyrics/MetalArchives; OFF: Letras/Musixmatch/AZLyrics/SongLyrics/Bandcamp/LyricFind (niche/captcha/fragile, keeps default fan-out small). All 13 render + toggle in Settings (verified on TV). **Scrapers are best-effort** — site HTML/slug can change/anti-bot; each toggleable off. Chain stops at first synced, so plain scrapers only run on a synced miss.
- **Title cleaning NOT done** (considered + reverted): tried stripping leading track numbers ("04.17 Girls In A Row" → "17 Girls in a Row") but user opted to fix names properly via the metadata phase instead of band-aiding filenames. `LyricChain` queries with metadata as-is.
- **Songs list fast-scroll focus bug fixed**: `LazyColumn` now `.focusGroup()` — a focused row recycling mid-fling no longer drops focus to the root (drawer/rail grabbing it). Verified on TV (25 rapid DOWN presses stayed in list). Real issue was focus escaping the lazy list, not the produceState reload (produceState keeps its prior value across key changes, so no blank-flicker).
- **Lyrics are session-temp**: resolved per track-change → `player.setLyricsFor` (in-memory). Never persisted; recomputed each play. (Could add an on-disk cache later if desired.)

## Phase 8.7 — tag/metadata reading landed (2026-06-11)

Embedded tags now drive the library instead of filename/folder. **No jaudiotagger, no download** — the key trick is reading tags over the network from streamed sources. Verified on-device: a Slipknot track indexed as `(8)_Killers_Are_Quiet` (dur 0) enriched to `Killers Are Quiet` / Slipknot / Mate.Feed.Kill.Repeat / 6:05, and LRCLIB then returned 23 synced lines (lyrics displayed on Now Playing).

Two layers:
- **Import-time** (`data/source/TagReader.kt`) — runs Media3 `MetadataRetriever` over our own `DinkDataSourceFactory`, so the extractor pulls only the container header / metadata atom over smbj or HTTP Range (no full download). `Metadata.Entry.populateMediaMetadata` decodes ID3/Vorbis/MP4 into one `MediaMetadata` — no per-format parser. `SmbImporter`/`CloudImporter` `trackFor` overlay title/artist/album/year/trackNumber, falling back to filename/folder when untagged/unreadable/timeout (15s). Threaded `Context` through `enumerate`/`walk` + the 4 call sites (SharesLibrary, CloudLibrary, MonitorWorker ×2). **Cost: import is slower** — one header read per file over the network; bounded by what the user imports. Re-import a folder to upgrade its tags.
- **Playback-time** (`PlayerState.onEngineMetadata` via `onMediaMetadataChanged`) — remote (`smb`/`gdrive`) MediaItems are built WITHOUT our (dirty) metadata so the engine surfaces the file's REAL tags; captured and (a) upgrade the in-memory `Song`, (b) persist via `LibraryRepository.enrichTrack` (no-op when unchanged → no snapshot churn), (c) re-fire lyric resolution (DinkApp lyric `LaunchedEffect` keyed on id+title). Also fills real duration (`MetadataRetriever` doesn't expose duration in media3 1.4, so playback is the duration source). Local tracks keep clean MediaStore tags + immediate MediaItem metadata.
- `TrackEntity` already had `year`/`trackNumber`/`albumTitle` fields — now populated. Unlocks the deferred URL-slug lyric providers (AZLyrics/DarkLyrics/Lyricsify/etc.) since they need accurate artist+title.

## SMB monitoring made real + fast (2026-06-11)

User found new NAS files weren't auto-indexing. Cause: folders were imported but never flagged monitored (`monitoredPaths` empty), and a naive monitor would re-walk + re-tag everything each pass (~3 min on 25k).
- **Auto-monitor on import**: `SharesLibrary.importFolder` / `CloudLibrary.importFolder` now also add the folder to `monitoredPaths`/`monitoredFolders`. One-time boot migration (`DinkApplication.migrateAutoMonitorOnce`, guarded by `dink_flags/monitor_migrated`) backfills monitored = imported for existing libraries, so they auto-update with no user action. Monitor still toggleable off per-folder.
- **Incremental scan**: importers reuse already-indexed rows (`LibraryRepository.sourceTrackMap` → `existing` map) so only genuinely NEW files are tag-read. Monitor/re-import of a big folder no longer re-reads 25k tags.
- **Parallel SMB walk**: `SmbImporter.enumerate` is now suspend + `withContext(IO)`, walks directories concurrently (Semaphore(16) — smbj multiplexes over one connection). Permit held only for the `list()` round-trip, not while awaiting children (no deadlock). `dropNestedRoots` drops a root nested under another (e.g. `music\Music` under `music`) so overlapping monitor roots don't double-walk. **Measured: ~174s → ~32s on the user's 25774-track tree.**
- **Launch catch-up + cadence**: `MonitorWorker.enqueueNow` (OneTime, KEEP) fires at boot so files added while Dink was closed appear soon after opening; periodic cadence tightened 6h → 2h (TV is mains-powered, scan is cheap now). Worker logs `scanned=/new=` per share.
- Limit: SMB folder mtimes don't propagate up the tree, so there's no reliable "only changed folders" shortcut — each pass is a full (parallel) walk. Background WorkManager job, never blocks UI.

## Phase 9 — Search + Playlists landed (2026-06-11)

`compileDebugKotlin` + `assembleDebug` green. Stub screens Search and Playlists now real.

- **Search** (`ui/screens/library/SearchScreen.kt`): reads unified `LibraryRepository.songs`; embedded-tag metadata (`Song.title`/`artist`/`albumTitle`) is the match key — same truth the library facets group by, so tag-corrected SMB/cloud tracks are findable by real name. TV tap-to-edit field (OK opens leanback IME, Back/Search commits; D-pad trapped while typing) mirrors `AddShareWizard.TapEditField`. Tokenised case-insensitive AND-match ("slip wait" → Slipknot · Wait and Bleed). Three sections Songs / Albums / Artists; songs play from the match, album/artist rows play the whole bucket (bounded queue). Filter+bucket off-main via `produceState(Dispatchers.Default)`; capped 80/30/30. `ScreenId.Search.implemented = true`.
- **Playlists** (full feature): `data/model/Playlist.kt` (id, name, ordered `songIds`, timestamps) — ids are `sha1(type+source+path)`, stable across re-import. `PlaylistStore` (atomic JSON `playlists.json`, mirrors `LibraryStore`; corrupt file preserved, never silently overwritten — playlists aren't re-derivable). `PlaylistRepository` (StateFlow + persist-on-mutate; create/rename/delete/addSong/removeSong/songsOf). Restored at boot in `DinkApp` next to `LibraryRepository.ensureRestored`.
- **TV context menu** (`ui/components/SongContextMenu.kt`): long-press (hold OK) a song row → centered Dialog of D-pad-focusable rows (no hover/right-click on a remote — hold-OK is the Plex/YouTube-TV convention). Two levels: Add to playlist / Add to queue → playlist picker + inline "New playlist…" naming (leanback IME, Done creates seeded with the track). Wired into `SongsScreen` rows and `SearchScreen` song results via `androidx.tv.material3.Surface(onLongClick=)` (alpha07 supports it). `PlaylistsScreen` lists playlists; click plays whole playlist, long-press → Play / Delete. Offline source → ids just don't resolve, playlist shortens rather than errors. `ScreenId.Playlists.implemented = true`.
- Remaining Phase 9 stub work: none flagged `implemented=false` in the Library/Top groups now; Settings already real. Folders/Albums/Artists were done earlier via `LibraryGroupScreen`.

## Phase 9 — Home index-backed + duration import + cloud parked + navrail consistency (2026-06-12)

`compileDebugKotlin` green.

- **Home is index-backed** (`ui/screens/home/HomeScreen.kt`): dropped the mock feed. Now reads `LibraryRepository.songs` + `recentlyPlayed(12)` + `recentlyAdded(12)`. Resume = most-recently-played (else first indexed track); shelves *fall back* to library slices so a fresh import with no play history still looks alive. "New in your library" dedupes by album so a 12-track import isn't 12 identical cards. Empty library → `EmptyHome` invitation (Add a source → SmbShares) instead of a barren hero. Real tracks carry no art, so `synthAlbumFor` derives deterministic procedural cover (palette+shape hashed off albumTitle/id, same scheme as NowPlaying's fallback) keyed on album so a whole album shares one cover. "View Album"/album cards now navigate to Albums (were Phase-9 toasts).
- **Song duration imported** (`data/source/DurationReader.kt`): Media3 1.4's `MetadataRetriever` (used by `TagReader` for tags) can't surface duration, so a second probe drives the platform `MediaMetadataRetriever` over a `MediaDataSource` bridge backed by `DinkDataSourceFactory` — header/metadata-atom bytes only over smbj/HTTP Range, **no download**. A 4 MB byte BUDGET caps each probe so it can never fall through to pulling a whole file (past budget → EOF → falls back to playback-time enrichment). Folded into `TagReader.Tags.durationMs`; consumed by `SmbImporter`/`CloudImporter` `trackFor` (`durationMs = tags?.durationMs ?: 0L`) and the `rescanMissingTags` merge. Playback-time enrichment (`PlayerState`) still fills/overrides on first play. **Cost: a second header read per NEW file at import** (bounded by what's imported + incremental reuse).
- **Cloud parked → "Coming soon"** (`ui/screens/sources/CloudScreen.kt`): rewritten to a coming-soon placeholder (CloudOff glyph, COMING SOON pill, explains the device-flow scope wall, points at SMB/USB). The provider grid / connect-modal UI is gone from the screen; the rest of the cloud subsystem (registry, `CloudDataSource`, importer, `CloudBrowse`) still compiles and is wired app-wide — just not user-actionable. `CloudBrowse` is now unreachable from the UI.
- **Navrail consistency** (`ui/components/SideRail.kt`): ALL rail items are commit-only now (`navOnFocus = false`), matching what NowPlaying always did. Killed preview-on-focus (content pane swapping as you arrow past an item) — it was inconsistent (only NowPlaying was exempt) and on NowPlaying it wedged the rail. Arrowing the rail now just moves focus; the screen changes only on select. The `previewNav`/`previewTarget` machinery in `DinkApp` + `gatedSelect` in `SideRail` are now dormant (harmless).

### UX pass (on-device, same day)
Drove the app on the real TV (focuscheck `tour` too slow @ 9min; `smoke` clean — its lone flag is a tool false-positive: the `y1 > 0.85H` geometry heuristic mistags the low-sitting Home hero button as MINIPLAYER. Focus *does* enter content). Four fixes:
1. **Mock-data leak removed** — `SongsScreen` was `PreviewMockData.songs + librarySongs`, so ~13 fake demo tracks (Ixion etc.) showed in the real Songs list (and via the index, Search). Now reads the index only. Verified: Songs shows exactly 25774, real artists.
2. **MiniPlayer hidden on Now Playing** (`DinkApp`) — it duplicated NowPlaying's own transport. Now `if (nav.current != ScreenId.NowPlaying)`. Safe to toggle per-screen now that rail preview-on-focus (the old dropped-keypress culprit) is gone.
3. **Songs default sort → Title** (was MostPlayed, which looks random on a fresh 0-play library).
4. **Duration backfill for existing library** — new imports get duration, but the 25k tracks imported earlier read 0:00. Broadened `retagAll`'s filter from `looksFilenameDerived` to `looksFilenameDerived || durationMs <= 0`, so the existing Settings → "Re-read tags from files" button (chunked, resumable, off-main) now refills lengths in the same probe. Settings copy updated to say so.

### Library-detail screens — Phase 9 closed (2026-06-12)
The last open Phase-9 sub-deliverable ("library-detail screens included"). Tapping an Album / Artist / Folder row used to blind-play the whole group; now it opens a **detail track list**.
- `ScreenId.LibraryDetail` (transient, not a rail entry — like SmbBrowse/CloudBrowse). `LibraryDetailNav` singleton (`group` / `facet` / `parent`) carries the tapped facet, since `DinkNav` is screen-as-state with no args (mirrors `activeBrowse*`).
- `ui/screens/library/LibraryDetailScreen.kt` — header (synth cover + facet label + "N tracks · length" + **Play all** / **Shuffle**) over a numbered `LazyColumn` tracklist; row tap plays from index → NowPlaying, long-press → `SongContextMenu` (add to playlist/queue). `formatLongDuration` shows "length pending" when all durations are 0 (pre-backfill).
- `LibraryGroupScreen` row `onClick` now sets `LibraryDetailNav` + navigates to `LibraryDetail` (was `playFrom(group.songs,0)`); gained `facet` + `parent` params (3 call sites in `DinkApp` updated). `DinkApp.railCurrentFor`/`crumbsFor` special-case `LibraryDetail` → highlight parent rail item, crumb `Library / <Parent> / <title>`. Back pops to the parent (stack-based).
- `ui/components/AlbumArtSynth.kt` — extracted the procedural-cover helper (`synthAlbumFor`) to a shared public fn (Home keeps its private copy; NowPlaying still has `fallbackAlbumFor`). Verified on device: Albums → "The Spaghetti Incident?" → 8-track Guns N' Roses list, crumb + parent highlight correct.

**Phase 9 = done** (table updated). Open items pushed to later: real **embedded cover art** (TagReader reads no pictures yet — everything is procedural) and Phase 10 (light theme toggle, D-pad audit, ProGuard/R8 keep-rules, strip `PreviewMockData` from release).

## Embedded cover art (2026-06-12)

Tracks now show their REAL embedded cover where present, procedural art otherwise — lazy, cached, no full download.
- `data/source/Media3MediaDataSource.kt` — extracted the `MediaDataSource`↔Media3-`DataSource` bridge out of `DurationReader` (now shared, `internal`). Reads header/atom bytes over smbj/Range with a byte budget.
- `data/art/ArtExtractor.kt` — platform `MediaMetadataRetriever.embeddedPicture` over the bridge (24 MB budget — art is bigger than a tag/duration probe). Returns `ByteArray?`.
- `data/art/AlbumArtCache.kt` — keyed `artist|album` (else track id). Three states: in-memory `LruCache(32)` decoded bitmaps · disk `<hash>.jpg` (downscaled ≤512px, JPEG 85) · disk `<hash>.none` negative marker (art-less album not re-probed). Per-key `Mutex` dedupes concurrent resolves; a `Semaphore(4)` caps concurrent NETWORK extractions so a fast scroll over 25k distinct albums can't open hundreds of SMB reads. **Lazy on purpose** — only albums the user looks at are fetched (extracting 25k up front = a storm).
- `ui/components/CoverArt.kt` — `CoverArt(song, palette, shape, …)` drop-in for `AlbumArt`: shows the cached cover if resolved, else procedural `AlbumArt`; `rememberCoverBitmap` resolves off-main and recomposes. Wired into NowPlaying (big art + queue rows), LibraryDetail header, Home hero, Home `SongCard`, Songs rows. (`AlbumCard` left procedural — no `Song` in hand.)
- Verified on device: NowPlaying big art shows a real Babymetal cover; Songs shows a real Billie Eilish cover; HELLYEAH/R.E.M. (no embedded art) stay procedural. 7 `.jpg`, 0 stuck `.none` after a play-through.
- NOT covered: external `folder.jpg`/`cover.jpg` sidecars (many ripped libraries store art there, not embedded) — natural follow-up.

## Phase 10 — Polish + cover art + UX requests (2026-06-12)

`compileDebugKotlin` + `assembleRelease` green; verified on-device. **Phase 10 = done** (table updated).

- **Light theme toggle** — `ui/theme/ThemeController.kt` (ThemeMode System/Dark/Light, persisted to `dink_theme` DataStore), held ABOVE `DinkTheme` in `MainActivity` via `LocalThemeController`; `DinkTheme(darkTheme=…)` already supported both palettes. Settings → Display → Theme `Seg`. Whole UI flips (NowPlaying keeps its own dark "tinted ambience" tokens by design).
- **R8 / shrink** — release `isMinifyEnabled + isShrinkResources`, **NO obfuscation** (`-dontobfuscate`, reversible per user). Keep rules in `proguard-rules.pro` for kotlinx.serialization, smbj (+ mbassador + BouncyCastle), Media3, jaudiotagger, and **WorkManager+Room+startup** (the one that crashed first run — `WorkDatabase` was stripped). Release signed with debug key for now (swap before store). **85 MB → ~15 MB.** Verified: runs, library index restores (serialization OK), SMB share connects.
- **Mock strip / D-pad** — `PreviewMockData` no longer leaks into UI (SongsScreen fixed earlier); remaining refs are lookup-only + R8-kept. focuscheck smoke clean (lone flag = tool geometry false-positive).

### User UX requests (same day)
- **Albums/Artists = cover-art grid** — `LibraryGroupScreen` gained `artGrid` param; Albums + Artists render `LazyVerticalGrid` of `GroupTile` (CoverArt square + title + subtitle) instead of icon rows, so it reads as albums not a folder list. Folders stays rows. Tap → existing `LibraryDetail`. (Untagged tracks still show folder-derived titles like "()320kbps" — that's a data gap the Settings "Re-read tags" rescan fixes.)
- **Search facet selector + grouped results** — `SearchScreen` rebuilt: a `Seg` (Songs/Albums/Artists) picks BOTH the match field and the layout. Songs → title-matched song rows (with CoverArt). Albums → album rows → detail. Artists → each matching artist as a header (`N tracks · M albums`) with their albums (tappable → detail) and each album's songs nested beneath — the "organise by artist" view. `search(query, library, facet)` computes only the active facet. **Focus fix:** the facet Seg sits at the left edge, so spatial DOWN from the full-width field beamed past it onto the results (facets were unreachable); added an explicit `down` target (`focusGroup`+`FocusRequester`) from the field into the Seg.
- **Settings tabs** — `SettingsScreen` split into Display / Lyrics / Library D-pad tabs (`SettingsTab`, switch-on-focus) instead of one long scroll. First tab owns `contentFocus`; tabs route Left → rail.
- **Bug fixes (pre-existing, surfaced here):** `Seg` rendered only its first option full-width — `SegButton`'s inner `Box(fillMaxSize())` made the first button greedily consume the Row width (others 0dp). Changed to `fillMaxHeight()`. This had broken Songs sort/filter too. Same class of bug bit `SettingsTab` (a `fillMaxWidth()` underline) — removed the underline.

### Phase 10 — perf + UX pass (2026-06-16, verified on-device, 25774-track library)
- **Section-load speed** — `LibraryRepository.songs()` was a cold `StateFlow.map` (sort + 25k toSong) recomputed on EVERY screen entry, then `produceState` re-grouped it. Now: `songs()` is a process-cached hot `StateFlow` (`stateIn(appScope, Eagerly)`) — sort+map runs once per library mutation, shared. Call sites use `.collectAsState()` (dropped per-nav `songsNow` initial). `LibraryGroupScreen.GroupMemo` memoises Albums/Artists/Folders by `facet + identity(songs)`. Result: first visit computes (loading bar), re-visits instant (verified: Albums→Artists→Albums = no loading bar).
- **Menu navigation sounds** — `ui/sound/NavSounds.kt` via platform `AudioManager` FX bank (no shipped assets). `LocalNavSounds`; `previewNav`→move tick (only on real target change), `commitNav/Replace`→select. Settings → Display → "Navigation sounds" toggle, persisted (`dink_sound`), default ON.
- **Rail preview snappier** — preview-swap debounce 160ms → 80ms (loads are cheap now).
- **Empty-state CTA** — `LibraryGroupScreen` empty library → "Add a source" (→ SmbShares), takes contentFocus.
- **Untagged nudge** — sampled heuristic (`hasUntaggedTracks`, first 400) shows a clickable banner → Settings on Albums/Artists/Folders when many filename-derived titles. (Confirmed on-device with the "()320kbps" library.)
- **Settings tab nav BUG FIX** — every `SettingsTab` had `left = railReq`, so Left between tabs opened the drawer instead of moving tab. Now only the first tab routes Left→rail (`isFirst`). Verified by adb focus-bounds: Display↔Lyrics↔Library moves correctly; Left on Display still reaches rail.
- **Rail device chip = real** — was a hardcoded mock `"Living Room · 192.168.1.18 · WI-FI"` (never the TV). Now `rememberNetworkStatus()` reads ConnectivityManager: device name · IPv4 · link type, with Wi-Fi/Ethernet/offline icon. (TV is on Ethernet — explains the mismatch.)
- **Remote PLAY key toggles** — Media3 mapped `KEYCODE_MEDIA_PLAY` to resume-only; added `MediaSession.Callback.onMediaButtonEvent` in `PlayerService` → PLAY while playing = pause. Verified via `media_session` state: playing(3)→PLAY→paused(2)→PLAY→playing(3).

## Phase 11 — Audio EQ + Now Playing/queue UX (2026-06-16)

`compileDebugKotlin` + `assembleDebug` green; verified on-device (real 25,774-track library).

- **EQ engine** — `player/EqEngine.kt` wraps `android.media.audiofx.Equalizer` bound to the player's audio session id (pinned in `PlayerService` via `AudioManager.generateAudioSessionId()` + `ExoPlayer.setAudioSessionId`, attached in onCreate, released onDestroy). Process-level singleton (playback in service, control in Settings, one session). Persisted to `dink_eq` SharedPreferences (synchronous → curve applied before first note). Gains in millibels. Presets (`EqPresets`) computed per band centre freq so they work regardless of device band count.
- **Settings → Audio tab** — enable toggle + 3 sections via Seg: **Presets** (Flat/Rock/Vocal/Bass/Custom pills + Reset equalizer button), **Simple** (3 macro bars Bass/Mid/Treble, each shifts its hardware-band group together), **Advanced** (all hardware bands; device gives 5 → 60/230/910/3.6k/14k). Verified: section switch, grab-to-edit, Back release, Left traversal, +5 applied + persisted.
- **Select-to-enter band sliders** — fixes "Up adjusted the EQ so you couldn't navigate out". A bar must be GRABBED with OK before Up/Down adjust it; Back releases (handled by a group-level `BackHandler`, so it never leaves the screen mid-edit). Editing state held in `EqBandGroup` (`editingIndex`); `onFocusChanged` releases on focus loss. Verified on-device.
- **EQ grid Left fix** — only the leftmost bar routes Left → rail; interior bars use spatial Left (same multi-column rule as [[feedback_tab_row_left_routing]]). Verified band4→band0→rail.
- **Play queue purge** — `V5QueueColumn` renders `queue.drop(currentIndex)`: current track at the top, already-played hidden. Underlying queue untouched (prev() still works). Verified ("Feel Good" at top, upcoming below).
- **EQ shortcut on Now Playing** — `GraphicEq` button added to `V5TransportRow`; sets `SettingsNav.initialTab = 1` and navigates to Settings, which opens on the Audio tab (`SettingsNav.consume()`). `NowPlayingScreen` gained an `onNavigate` param (wired from ScreenHost). Renders in transport; deep-link wiring confirmed in code — couldn't be exercised via the adb crawler tonight (focus-on-NowPlaying-open + tiny scaled transport buttons made blind D-pad unreliable), so confirm by pressing it on the remote.
