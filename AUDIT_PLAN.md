# Dink — Bug & Optimisation Audit Plan (2026-09-28)

Six parallel read-only auditors (playback, SMB/ingest, library/persistence, UI, lyrics, build/security) produced 102 findings,
each quoting the code it cites. The lead spot-checked the highest-impact findings against the source (✔ in the index), and all of them held.
Overlapping findings are merged into work packages (WP) below. IDs: PLAY / SRC / LIB / UI / LYR / BLD.

Severity: **P0** crash / data loss / store rejection · **P1** user-visible bug · **P2** perf or latent bug · **P3** cleanup.

---

## Cross-cutting root causes

1. **Stale-snapshot writes.** Monitor, retag, recompute, local refresh and share-prefs all write back whole rows or objects copied minutes earlier. The result is lost plays, lost enrichment, and removed shares that come back. → WP-B, WP-C
2. **"Error" is stored as "absent".** Art `.none` markers, retag stamps, TagReader null, and walk evictions all record transient failures as permanent facts. → WP-E
3. **The engine drifts from the façade.** SEEK transitions, gapless READY, ENDED, buffering, and the activity-gone case never reach PlayerState. → WP-F
4. **One play churns 25k rows.** `markPlayed` fires on every `currentSong` change, remaps and re-sorts the whole list, invalidates GroupMemo, and never persists. → WP-A
5. **The lyrics chain is serial, blocking, uncancellable and uncached, and local-only for sidecars.** → WP-J

---

## Wave 0 — Quick wins (all S effort, high confidence; ~1–2 days)

Each one is a small, isolated change. Batch them into 3–4 commits by area.

| ID | Fix |
|---|---|
| PLAY-2 ✔ | `pollTick`: return early while `pendingEngineApply` or `mediaItemCount == 0`. This stops the restored position being zeroed and fixes the prev()-jumps-back bug. |
| PLAY-3 ✔ | `togglePlayPause`: in `STATE_ENDED`, seek to the current item at 0 (or restart the queue) before `playWhenReady`. |
| PLAY-4 ✔ | `onMediaItemTransition`: also sync on `REASON_SEEK`, so remote ⏭/⏮ and Assistant update the UI. |
| PLAY-5 | Refresh the engine duration in `onMediaItemTransition` and `onTimelineChanged`. Don't clamp a seek when `durationSec == 0`. |
| PLAY-8 | Re-assert `engine.repeatMode = engineRepeatMode()` after any queue-size change. |
| PLAY-14 | Shuffle toggle: set `pendingSeekMs` before `applyQueueToEngine`, then drop the second seek. |
| LYR-2 = BLD-4 ✔ | DarkLyrics `http://` → `https://`. Cleartext is blocked, so the provider is dead today. |
| LYR-3 ✔ | Add a lyrics tri-state (Loading/Loaded/None). Today the pane shows "No lyrics available" while it is loading. |
| LYR-4 ✔ | LRC `[offset:]`: the sign is inverted. Use `t - offset` and `toIntOrNull()`. |
| LYR-11 | Strip the UTF-8 BOM (U+FEFF) and sniff UTF-16 before parsing LRC. |
| LYR-12 | Sidecar match: require normalised-name equality, not `contains` ("Alone.lrc" currently matches "One"). |
| LYR-14 | QQ: unwrap JSONP only when the body doesn't start with `{`. |
| UI-2 ✔ | Settings: bind `contentFocus` to the selected tab, not tab 0. The EQ shortcut currently lands on Display. |
| UI-3 | DinkApp commit: key the effect on a `commitSeq` counter so re-committing the same screen refocuses content. |
| UI-5 + SRC-16 ✔ | Delete SmbSharesScreen's `LaunchedEffect(shares)` (its initial emptyList wipes the registry mid-playback). Make `SmbConnectionRegistry.update` an atomic swap. |
| UI-9 | SmbBrowse: clear `entries` on path change or failure. |
| UI-13 | NowPlaying queue: `items(key = { start + it })`, and `remember` the upcoming list. |
| UI-14 | Playlists: `remember(library) { associateBy }`, not once per row per recomposition. |
| UI-15 | LocalStorage refresh/rescan: move `importSource` off Main. |
| UI-21 | Toast: make `ticket` state so repeats restart the timer, and add an error kind (no green check on errors). |
| UI-23 | Ignore key auto-repeat on toggles and EQ grab (`repeatCount > 0`). |
| LIB-14 | feat-regex: require trailing text (keeps "Little Feat" intact). A comma split with no solo match keeps the whole name. |
| LIB-16 | LibraryGroupScreen: drop `initial = songsNow()` (it defeats GroupMemo). Key the memo by `===`, not identityHashCode. |
| SRC-2 | SmbClient: close the session and connection if `authenticate` or `connectShare` throws (lease leak). |
| SRC-3 | Walk: evict and retry only on `isConnectionError`. Treat ACCESS_DENIED / NOT_FOUND as "skip, walk still complete". |
| SRC-17 | SHA-1 id: use a hex lookup table plus a ThreadLocal digest (currently ~500k `String.format` calls per monitor pass). |
| BLD-9 ✔ | `tools/deploy.sh` default SERIAL → `192.168.255.81:5555`. The Stop-hook deploy points at a dead IP. |

---

## Wave 1 — Data integrity + release blockers (P0/P1)

### WP-A · Play tracking (UI-4 ✔, LIB-3 ✔, LIB-15)
- Move `markPlayed` from `DinkApp LaunchedEffect(currentSong.id)` into PlayerState. Fire it on the first real playback (READY + playing, or ≥N s), not on restore, preload or skip.
- Change the DAO to a single-row patch. Move play stats into a small separate map/StateFlow so the 25k list identity doesn't churn and GroupMemo, Search and Songs stop recomputing.
- Add a conflated, ~2 s debounced persist writer with a flush on `ProcessLifecycle ON_STOP` (plays are never persisted today).
- Sort with `String.CASE_INSENSITIVE_ORDER`, not `lowercase()` inside the comparator (also covers UI-16's Songs sort).

### WP-B · Field-level merge writes (LIB-2 ✔, LIB-4 = SRC-6, SRC-8, LIB-12, LIB-13)
- Give `IndexDao.upsertTracks` a merge function applied inside `update{}` that preserves `addedAtMs`, `playCount`, `lastPlayedMs` and enriched fields from the *current* row.
- Local refresh (LIB-2): stop resetting `addedAtMs` and `playCount`. Today every launch wipes local play history and floods Recently added.
- Retag and recompute patch only the fields they own. Monitor upserts only new or changed rows, and skips recompute and persist when there is no delta.
- Store `lastWriteTime` and size on TrackEntity, and re-tag when they change or when `durationMs <= 0` (SRC-8).
- `persist()`: take the snapshot inside `ioLock` and return a Result. Surface failures and delete `.tmp` on failure (LIB-12).
- `enrichTrack`: recompute artistKey, albumKey and label when artist or album changes (LIB-13).

### WP-C · Source lifecycle races (LIB-5, SRC-5, SRC-9, UI-8)
- Tombstone or generation-guard source ids, so upserts for a removed source are dropped inside the DAO update.
- `deleteShare` / remove-from-library cancels in-flight import, monitor and retag jobs for that source.
- Add `SharePrefs.updateShare(id){}` as a read-modify-write inside `DataStore.edit` that no-ops if the share was deleted (the same pattern exists in CloudLibrary).
- Add a process-wide per-source Mutex around monitor, import and retag. MonitorWorker re-checks LAST_PASS_MS in `doWork`, stamps only on success, and returns `Result.retry()` on connection failure.
- UI-8: add a confirm dialog (default focus Cancel) for Delete share and Remove from library.

### WP-D · Persistence hardening (LIB-1, LIB-8, LIB-9, UI-7, LIB-18 = UI-10)
- **LIB-1 (P0):** the EncryptedShareStore recovery never deletes the keystore master-key alias, so a broken key crash-loops on every launch. Delete the alias when recovering. If the retry fails, fall back to a no-op store. Guard `getCloudToken`. Add a "credentials were reset" UI flag.
- LIB-18/UI-10: make EncryptedShareStore a lazy process singleton built off-main (~350 ms per construction; the wizard builds it on Main in composition, and SmbBrowse on every folder).
- LIB-8: PlaylistRepository mutators `ensureRestored()` first. A corrupt load disables persistence. Keep timestamped corrupt copies and a `.bak`.
- LIB-9: `fd.sync()` before the atomic move. Rotate `library_index.bak.json` and fall back to it. Treat OOM/IOException on load as retry, not Corrupt. Only an authoritative reindex re-enables persistence (not any scoped import).
- UI-7: add-to-playlist and create run on `appScope`. Show the toast only after the persist confirms.

### WP-E · Error ≠ absent (LIB-7, SRC-11, ID3v1 residue)
- Make ArtExtractor and TagReader return `Found | Absent | Error`.
- Art: write `.none` only for Absent, with a ~7-day TTL. Add Settings → "Clear art cache".
- Retag: stamp `retagAttemptedMs` only for Found/NoTags. Store the file mtime with the stamp.
- The same attempted-marker design resolves the known ~262-file ID3v1/APE residue.

### WP-K · Store / policy / security (BLD-1, BLD-2, BLD-3, LIB-11 = BLD-5)
These are release blockers because the app is live on Play.
- **BLD-2:** the privacy policy says lyrics are sent "when you enable online lyrics", but 7 providers (incl. NetEase/QQ) default to on. Either add a master "Online lyrics" toggle (default off) or rewrite the policy and Data Safety text.
- **BLD-1:** the Genius bearer token is borrowed from foo_openlyrics and shipped in the AAB, and Musixmatch is reached by impersonating its desktop client. Remove these or make them opt-in. *(Decision needed.)*
- **BLD-3:** the Google OAuth client secret is present in release classes.dex (`-dontobfuscate`) while cloud is parked. Stop injecting it into release builds and **rotate the secret** (it has already been published).
- LIB-11/BLD-5: move `artcache` from filesDir to `cacheDir` with a ~50 MB LRU cap. Exclude artcache, `library_index*.json` and `*.tmp` from backup. Otherwise the 25 MB quota is exceeded and playlists never back up.

---

## Wave 2 — Crash & stability

### WP-F · Service-side playback (PLAY-1, PLAY-6, PLAY-9, PLAY-12, PLAY-7, PLAY-13, PLAY-4 full fix)
- **PLAY-1 (P0):** a cold media-button resume with no usable snapshot never calls startForeground, so the app dies with `ForegroundServiceDidNotStartInTimeException`. Post a minimal foreground notification before resolving, or upgrade Media3 (see WP-L).
- PLAY-6: once the activity is gone, window advance, error-skip, snapshot saves and ImportThrottle all stop. Move queue advance and persistence into a service-side Player.Listener.
- Wrap the session player in a ForwardingPlayer that routes next/prev through PlayerState (the full PLAY-4 fix).
- PLAY-9: drive the UI and ImportThrottle from `playWhenReady && state ∉ {IDLE, ENDED}`, not `isPlaying` (which flickers during buffering and releases the throttle during rebuffer).
- PLAY-12: move position into a tiny separate record, not a 45 KB queue rewrite every 5 s. Save on pause.
- PLAY-7: persist the unshuffled base order (a restored shuffle can't be turned off).
- PLAY-13: moveTo or seek on a deferred session applies the engine once at the target (it currently takes about 1.4 s on Main).

### WP-G · SMB connection robustness (SRC-1, SRC-4, SRC-7, PLAY-10, SRC-13)
- **SRC-1:** `withSoTimeout(30s)` kills smbj's idle packet reader. Combined with leaked leases, that leaves a zombie pooled connection and every request hangs 30 s. This is likely the real "NAS drops idle session". Set soTimeout to 0 (deadlines come from `withTimeout`) or send an ECHO keepalive, and use `close(true)` on error-evict. **Verify on device:** pause >30 s on two shares of the same host, then resume.
- SRC-4: add a walk circuit breaker. After the first connect-level failure, abort queued folders and call `ensureActive()` before each list.
- SRC-7: skip hidden, system and reparse-point entries, `@eaDir`, `#recycle`, `#snapshot`, `$RECYCLE.BIN`, `System Volume Information` and `._*` files.
- PLAY-10: map SMB NOT_FOUND to `FileNotFoundException`. That removes Media3's ~6 s of retries per missing file.
- SRC-13: NsdManager allows one resolve at a time below API 34, so queue resolves. Today only one mDNS host is discovered.

### WP-H · Focus & navigation (UI-1, UI-6, UI-19, UI-22)
- **UI-1 (P0, needs device repro):** Home shelf up/down/enter targets are FocusRequesters on LazyRow item 0, which gets disposed after scrolling. That throws "FocusRequester is not initialized". Guard with `firstVisibleItemIndex == 0`, as the NowPlaying fix does, or use `focusRestorer()`. Add the scenario to `tools/focuscheck.py`.
- UI-6: Search → album → Back lands on Albums and loses the query. Set parent = Search and keep the query and facet in a holder.
- UI-19: Playlists (empty) and Now Playing (nothing playing) have no focus target, so the drawer gets stuck. Add a focusable empty-state CTA.
- UI-22: screen BackHandlers override the drawer's exit dialog. Gate them on drawer state.

---

## Wave 3 — Performance

### WP-J · Lyrics chain (LYR-1, LYR-5 to LYR-10, LYR-13)
- **LYR-1 (P1):** sidecar `.lrc`/`.txt` and ID3 USLT go through `java.io.File`, so they are dead for SMB, the main source. Read sidecars via the SMB registry (list the parent directory once, case-insensitive), and capture USLT/SYLT in the import-time TagReader pass.
- Cache: LRU plus disk, keyed by songId or artist|title|duration. Cache negative results with a TTL of about 7 days. Invalidate when provider toggles change.
- Make `resolve` suspend and cancellable (`ensureActive` between providers, OkHttp `Call.cancel`), with `callTimeout` ≈ 6 s and an overall deadline.
- Query the synced tier in parallel; the best priority result wins after a short grace period. Skip plain-only providers once a plain result exists (LYR-7).
- Validate candidates (LYR-5): normalised artist/title match and duration within ±3 s. Scan the top N hits, not only [0]. Honour LRCLIB `instrumental` (LYR-10).
- Unknown duration: treat lyrics as plain, with no highlight (LYR-13).
- Consider resolving only when the lyrics pane is visible, plus prefetching the next track.

### WP-I · SMB read path & tag I/O (PLAY-11, PLAY-15 = SRC-15, SRC-10, SRC-12, SRC-14)
- PLAY-11: add a 128–256 KB read-ahead in `SmbDataSource.read`. MP3/M4A extractors issue about 1 KB reads, so the current path makes roughly 2 SMB round-trips per 26 ms of audio.
- Open the file once per probe and share it across the duration parse and the head/tail tag reads. Pass in the size known from the listing, and allocate the 512 KB `raBuf` lazily.
- SRC-10: Media3 1.4.1's `MetadataRetriever` ignores cancellation, so threads and handles leak on timeout and a HandlerThread is created per file. The instance-based AutoCloseable retriever comes with the Media3 upgrade (WP-L).
- SRC-12: Mp3DurationParser should loop over stacked ID3 tags, require two consecutive valid frame headers, and use a mid-file bitrate sanity check.
- SRC-14: ID3v1 fields should cut at the first NUL and fall back to windows-1252. APE should stream items and skip binaries, rather than rejecting tags with >1 MB art.

### WP-M · UI recomposition & main-thread (UI-11, UI-12, UI-16, UI-17, UI-20, LIB-10, LIB-19, LIB-17, LIB-6 = UI-18)
- UI-11: MiniPlayer progress should be passed as a `() -> Float` lambda and drawn with `drawBehind`. Today the 250 ms tick recomposes the root content lambda on every screen.
- UI-12: `derivedStateOf` for `currentLyricIndex`.
- UI-16: debounce the Search query (~150 ms), add `ensureActive()` inside the scan, and precompute lowercase keys.
- UI-17: Search groups by artistKey/albumKey, as the library screens do.
- UI-20: preload UiScale and Theme prefs before `setContent`. Today every cold start renders at 85%/System first, then relayouts.
- LIB-10: bound the art LruCache by bytes (about 1/8 of maxMemory) and scale to exactly MAX_DIM. Today 32 × ~4 MB bitmaps can take 128 MB of heap.
- LIB-19: key art on albumArtistKey|albumKey and remove lock entries after use.
- LIB-17: run one Mutex-guarded local refresh per launch (currently 2–3), with an atomic list swap.
- **LIB-6 = UI-18 (behaviour change, decision needed):** albumKey is currently the title only, so "Greatest Hits" merges across artists, as do folder-derived "CD1" albums. Key on albumArtist|album.

---

## Wave 4 — Tooling, deps, tests, cleanup

### WP-L · Dependencies & build (BLD-6, BLD-10, BLD-12, BLD-7, BLD-11)
- Upgrade Media3 1.4.1 → current stable. This enables the PLAY-1 foreground fallback and SRC-10's closeable MetadataRetriever. Do it in its own branch, with a full device verify of resumption, audio focus and EQ.
- Constrain bcprov to ≥ 1.78.1 (CVE-2024-29857/30171/30172/34447 via smbj), then re-verify NTLM and signing.
- R8: drop the blanket `-keep androidx.media3.**`, narrow the bouncycastle keep, and exclude `org/bouncycastle/pqc/**` (~1.2 MB). The APK is 15.9 MB.
- Catalog: align the `kotlin` version with what actually resolves (2.2.x), remove unused coil/media3-ui, and bump tv-material and core-ktx. Plan an exit from the deprecated security-crypto. Keep the config cache on.
- BLD-7: leanback is `required="false"` while the app is D-pad-only. *(Decision: TV-only?)*
- BLD-11 (optional): allowlist controllers in `onConnect`.

### WP-N · Tests & CI (BLD-8 + test-gap list)
- `release.sh`: run `testDebugUnitTest lintRelease` before bundling. Fail if `keystore.properties` is missing (today it silently falls back to debug signing).
- Commit `PlayerStateResumptionTest.kt` (untracked). Add a pollTick-driven test for PLAY-2.
- New pure-logic tests, in priority order:
  1. Mp3DurationParser (Xing/VBRI/CBR, stacked ID3)
  2. The DurationReader `truncated` rule
  3. `durationLooksBogus` / `looksMojibake`
  4. TagFallbackReader (ID3v1/APE/NUL/CP1252)
  5. LibraryGrouping `normKey` + primaryArtistKey (table-driven)
  6. `importScoped` prune gating with a fake DAO
  7. Persist-gate invariants + LibraryStore load (missing vs corrupt)
  8. MediaLibrary empty-cursor prune
  9. LrcParser (offset sign, BOM, multi-stamp)
  10. LyricChain `distributeIfFlat` / LyricHtml
  11. Sidecar matching
  12. `SmbDataSource.isConnectionError`
  13. PlaybackStore round-trip with a corrupt file

### WP-O · Cleanup (P3)
- LYR-15: use `Html.fromHtml` or a one-pass entity decoder (fixes `&amp;` double-decode and supplementary code points) and hoist the regexes.
- SRC-18: delete the legacy `SmbSync.enumerate`, `startSync`, `allSongs` and `enumerateImports` (no callers; this is the old ANR walk).
- UI-24: remove the DinkNav back stack (never read) and the dead rail-preview/gatedSelect code.
- PLAY-16: CloudDataSource should require 206 when position > 0 and use a finite readTimeout (cloud is parked).
- BLD-13: remove root clutter (logcat.txt, mp3s, zip, Plan.txt, the Google logo png), untrack machine-specific `.idea` files, and optionally filter-repo the 37 MB `tools/shots` history before making the repo public.
- BLD-14: back up the `.jks` offline and confirm Play App Signing enrolment. Nothing is exposed in git (verified).

---

## Decisions needed from owner

1. **Album grouping (LIB-6/UI-18):** should albums be keyed by album-artist + title (splitting "Greatest Hits" per artist)? The auditors believe the merge is a bug.
2. **Lyric providers (BLD-1/BLD-2):** remove the borrowed Genius token and Musixmatch impersonation, or keep them behind opt-in? Should the default be master off, or should the policy be rewritten?
3. **TV-only (BLD-7):** make leanback `required="true"`?
4. **Media3 upgrade timing (WP-L):** do it before WP-F (it simplifies PLAY-1 and SRC-10) or after the Wave 0–1 fixes ship?

## Suggested sequencing

- **Release 1.2.5:** Wave 0 + WP-A + WP-D (LIB-1) + WP-K. These are mostly small fixes, and they deliver the biggest correctness gains plus store compliance.
- **1.3.0:** WP-B, WP-C, WP-E, WP-F, WP-G, WP-H, plus tests for each touched unit (WP-N).
- **1.3.x:** WP-J (lyrics), WP-I, WP-M, and WP-L (Media3 upgrade on its own branch).
- Verify each package with unit tests where the logic is pure, and with the `/verify` skill on the TV (192.168.255.81) for playback, focus and SMB behaviour. `tools/focuscheck.py` covers regressions from UI-1 and UI-19.

---

## Appendix — full finding index

✔ = lead spot-checked against source.

**Playback (PLAY)**

| ID | Sev | One-line |
|---|---|---|
| 1 | P0 | Cold media-button resume fails → no startForeground → FGS timeout crash |
| 2 ✔ | P1 | pollTick zeroes restored position; prev() jumps back; position persisted as 0 |
| 3 ✔ | P1 | Play is a no-op after STATE_ENDED |
| 4 ✔ | P1 | Session/remote next/prev (SEEK reason) ignored → UI desync |
| 5 | P1 | Gapless transitions never refresh duration; seek clamps to 0 |
| 6 | P1 | Activity gone → window advance, error skip, persistence all stop |
| 7 | P2 | Restored shuffle can't be turned off (baseOrder = shuffled order) |
| 8 | P2 | Queue grows 100→101 without re-asserting repeat mode; added track never plays |
| 9 | P2 | isPlaying false during buffering → throttle released, icon flicker |
| 10 | P2 | Missing SMB file retried ~6 s by Media3 |
| 11 | P2 | No SMB read-ahead; ~1 KB round-trips |
| 12 | P2 | 45 KB snapshot rewrite every 5 s; no save on pause |
| 13 | P2 | Deferred session double-applies the engine on moveTo/seek |
| 14 | P3 | Shuffle toggle double-opens the file |
| 15 | P3 | Double SMB open per probe; eager 512 KB buffer |
| 16 | P3 | Cloud: 200 accepted on range; infinite read timeout |

**Source ingestion (SRC)**

| ID | Sev | One-line |
|---|---|---|
| 1 | P1 | soTimeout 30 s kills idle reader → zombie pooled connection |
| 2 | P2 | Lease/session leak when auth or connectShare fails |
| 3 | P1 | Any list error evicts the shared connection; denied folder blocks prune forever |
| 4 | P1 | No circuit breaker: NAS down → hours of blocked threads |
| 5 | P1 | Share prefs saved from stale snapshot; delete mid-import resurrects share |
| 6 | P1 | Walk and retag upsert stale rows over plays and enrichment |
| 7 | P2 | No hidden/system folder filter (#recycle, @eaDir, `._*`, junction loops) |
| 8 | P2 | Changed file at same path never re-tagged |
| 9 | P2 | Periodic and catch-up monitor overlap; failed pass stamped done |
| 10 | P2 | MetadataRetriever timeout doesn't release; thread per file |
| 11 | P2 | Retag stamps transient failures as attempted |
| 12 | P2 | Mp3 parser: stacked ID3 / false sync → confident wrong duration |
| 13 | P2 | NSD one-resolve limit → only one mDNS host found |
| 14 | P2 | ID3v1 CP1252, embedded NUL; APE >1 MB rejected |
| 15 | P2 | Up to 4 SMB opens per new file; eager buffers |
| 16 ✔ | P3 | Registry clear() window → "Unknown SMB share id" |
| 17 | P3 | SHA-1 hex via String.format in hot loop |
| 18 | P3 | Dead legacy walk code |

**Library & persistence (LIB)**

| ID | Sev | One-line |
|---|---|---|
| 1 | P0 | Keystore master-key failure → crash loop; creds silently wiped |
| 2 ✔ | P1 | Local refresh resets addedAt/playCount every launch |
| 3 ✔ | P1 | markPlayed never persisted |
| 4 | P1 | Stale-row upserts (monitor/retag/recompute) |
| 5 | P1 | Removed source resurrected by in-flight job |
| 6 ✔ | P1 | albumKey = title only → cross-artist album merge |
| 7 | P1 | Transient art failure → permanent `.none` |
| 8 | P1 | Playlists: no persist gate; corrupt load or early create wipes playlists |
| 9 | P1 | OOM on load = Corrupt; scoped import re-enables persist; no fsync/backup |
| 10 ✔ | P2 | Count-bounded art LRU, up to 1023 px bitmaps (~128 MB) |
| 11 | P2 | Art cache in filesDir, unbounded, breaks Auto Backup quota |
| 12 | P2 | Snapshot taken outside lock; save failure only logged |
| 13 | P2 | enrichTrack doesn't recompute grouping keys |
| 14 | P2 | "Little Feat" → "Little"; comma split truncates names |
| 15 | P2 | One play remaps and re-sorts 25k rows; full JSON per enrich |
| 16 | P2 | LibraryGroupScreen `initial` defeats GroupMemo |
| 17 | P2 | 2–3 local refreshes per launch; non-atomic list |
| 18 | P2 | EncryptedShareStore built repeatedly (~350 ms each) |
| 19 | P3 | Art key uses raw artist; lock map leak |

**UI (UI)**

| ID | Sev | One-line |
|---|---|---|
| 1 | P0 | Home shelf FocusRequester on disposed LazyRow item → crash |
| 2 ✔ | P1 | EQ shortcut opens Display tab |
| 3 | P1 | Re-committing the same screen leaves focus in the drawer |
| 4 ✔ | P1 | markPlayed on every currentSong change (restore/skip inflate plays) |
| 5 ✔ | P1 | SmbShares empty initial wipes the registry mid-playback |
| 6 | P1 | Search → album → Back lands on Albums, query lost |
| 7 | P1 | Playlist add persisted on a dialog scope that gets cancelled |
| 8 | P1 | Delete share with no confirmation |
| 9 | P2 | SmbBrowse shows stale rows on error |
| 10 | P2 | EncryptedShareStore on Main in composition |
| 11 | P2 | 250 ms tick recomposes the root and MiniPlayer |
| 12 | P2 | Lyric index scan recomposes NowPlaying every tick |
| 13 | P2 | Queue rows unkeyed → focus lands on wrong song after advance |
| 14 | P2 | Playlists build a 25k map per row |
| 15 | P2 | LocalStorage importSource on Main |
| 16 | P2 | Search: no debounce/cancel; lowercase in comparator |
| 17 | P2 | Search groups by raw strings |
| 18 | P2 | Album merge (same as LIB-6) |
| 19 | P2 | Empty Playlists/NowPlaying: no focus target |
| 20 | P2 | Scale/theme prefs applied after first frame |
| 21 | P3 | Toast repeat timer; success icon on errors |
| 22 | P3 | Screen BackHandlers override drawer |
| 23 | P3 | Key auto-repeat flips toggles |
| 24 | P3 | Dead nav stack / rail preview code |

**Lyrics (LYR)**

| ID | Sev | One-line |
|---|---|---|
| 1 | P1 | Sidecar/ID3 lyrics dead for SMB (java.io.File) |
| 2 ✔ | P1 | DarkLyrics over http blocked |
| 3 ✔ | P1 | "No lyrics available" shown while loading |
| 4 ✔ | P1 | LRC offset sign inverted; huge offset discards file |
| 5 | P1 | First search hit accepted; wrong song's synced lyrics win |
| 6 | P2 | ~12 serial requests, no deadline/callTimeout |
| 7 | P2 | Plain-only providers queried after plain found |
| 8 | P2 | Chain not cancellable; fast skips pile up |
| 9 | P2 | No cache (positive or negative) |
| 10 | P2 | LRCLIB instrumental flag ignored |
| 11 | P2 | BOM / UTF-16 / GBK LRC breaks |
| 12 | P2 | Sidecar `contains` match picks wrong file |
| 13 | P2 | Unknown duration → last line highlighted |
| 14 | P2 | QQ JSONP unwrap cuts at "(" |
| 15 | P3 | HTML entity decoding gaps; regex per call |

**Build / security / release (BLD)**

| ID | Sev | One-line |
|---|---|---|
| 1 | P1 | Borrowed Genius token + Musixmatch impersonation shipped |
| 2 ✔ | P1 | 7 lyric providers default-on vs privacy policy |
| 3 | P2 | OAuth client secret in release dex; rotate |
| 4 ✔ | P2 | = LYR-2 |
| 5 | P2 | = LIB-11 |
| 6 | P2 | bcprov 1.75 CVEs |
| 7 | P2 | leanback not required on D-pad-only app |
| 8 | P2 | release.sh skips tests/lint; silent debug-sign fallback |
| 9 ✔ | P2 | deploy.sh default IP dead |
| 10 | P3 | Blanket R8 keeps; pqc resources |
| 11 | P3 | Exported MediaSessionService accepts all controllers |
| 12 | P3 | Stale deps / catalog Kotlin mismatch |
| 13 | P3 | Repo clutter; 37 MB screenshot history |
| 14 | P3 | Keystore not in git (OK); keep an offline backup |
