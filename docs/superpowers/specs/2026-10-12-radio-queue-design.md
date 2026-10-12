# Radio queue — design

Date: 2026-10-12
Status: approved (brainstorm), pending implementation plan

## Problem

Clicking a song in Home → "Across your sources" queues the 10 random shelf songs
(`HomeScreen.kt` `sampleStable` + `player.playFrom(shelf, idx)`). The queue is unrelated
to the chosen song and stops after 10 tracks. The owner wants a radio-style queue of
similar songs / artists / albums that keeps playing.

## Decisions (owner)

| Question | Choice |
|---|---|
| Similarity signals | Local signals **plus** add genre tag reading |
| Queue behaviour | Endless, auto-refill |
| Entry points | Home "Across your sources" card click + a "Radio" button on Now Playing |
| Algorithm | Weighted scoring with diversity rules and gentle drift |

Out of scope: online similarity services, "autoplay when any list ends" setting, radio
from album/artist/songs screens.

## 1. Genre data

### Reading
- `TagReader.Tags` gains `genre: String?`, from Media3 `MediaMetadata.genre`.
- `TagFallbackReader` (ID3v1/APEv2 tail tags): ID3v1 genre byte (offset 127) mapped through
  the standard ID3v1 genre table (0–191; 255 = none), and the APE `Genre` item.
- ID3v2 TCON numeric forms (`(17)`, `17`, `(17)Rock`) resolve through the same table; a
  text part wins over the number.
- Local/USB import reads `MediaStore.Audio.AudioColumns.GENRE` (API 30+, minSdk 31).

### Storage
- `TrackEntity.genre: String? = null` — JSON index, nullable default, old snapshots
  deserialize unchanged. `TrackMerge` treats it like `year` (incoming non-null wins,
  otherwise keep current).
- `Song` gains `genre: String? = null` and `year: Int? = null` (year is already on the
  entity but not mapped to `Song`). Mapping added wherever `TrackEntity → Song` happens.

### Normalisation (matching only, never display)
`genreKeys(raw: String?): Set<String>`:
- split on `;`, `/`, `,`, `\u0000`
- lowercase, strip all non-letter/digit chars (`Hip-Hop`, `hip hop`, `HipHop` → `hiphop`)
- drop blanks and the placeholder values `other`, `unknown`, `genre`
- tracks match on any overlap of key sets.

### Backfill of the existing library
- Bump `LibraryRepository.RETAG_VERSION` 1 → 2.
- New automatic background pass `backfillGenres(context)`, launched once per process after
  launch restore completes (appScope, low priority). Rows selected: remote (non-Local),
  `acceptsWrites`, `genre == null`, `retagVersion < 2`.
- Tags-only read (`durationNeeded = false`), lower concurrency than the manual rescan so
  it does not compete with playback streaming.
- Writes **only** `genre` (and `year` when the row has none). Titles/artists untouched.
- Conclusive read (Found/Absent) → stamp `retagAttemptedMs` + `retagVersion = 2`;
  transient Error → leave stamp, retried next launch (same rules as `retagStamped`).
- Persists per chunk (crash-resumable); single-flight with `retagAll` (shares `retagGate`
  so the two never run concurrently). Failures logged, never surfaced as success.
- Local rows: genre arrives from MediaStore on the next local re-scan; no tag read.
- Expected one-off cost: ~15–30 min for ~25k SMB tracks. Radio works throughout; tracks
  with no genre simply score on the other signals.

## 2. Radio engine — `RadioPicker`

New file `player/radio/RadioPicker.kt`, pure Kotlin (no Android), unit-testable.

```kotlin
data class WeightedSeed(val song: Song, val weight: Double)

object RadioPicker {
    fun pick(
        seeds: List<WeightedSeed>,
        library: List<Song>,
        exclude: Set<String>,   // song ids
        previous: Song?,        // last song already queued (diversity check for first pick)
        count: Int,
        random: Random,
    ): List<Song>
}
```

### Score
For candidate `c`, `score(c) = Σ seed.weight × s(seed.song, c)` where `s` sums:

| Signal | Points |
|---|---|
| Same `artistKey` (collabs already fold to primary artist) | +5 |
| Genre key overlap | +4 |
| Same album artist: `albumKey` prefix before `|`, ignoring `variousartists` | +3 |
| Both years known, \|Δ\| ≤ 3 / ≤ 8 | +2 / +1 |
| Same parent folder / same grandparent folder of `sourcePath` | +2 / +1 |

Folder signals only count when the folder is small — parent holds ≤ 300 library songs,
grandparent ≤ 1500 — so a share root or flat dump folder scores nothing, whatever the
mount layout. Generic artist keys (`unknown`, `variousartists`, `va`, …) never earn the
artist bonus. Null `artistKey`/`albumKey` fall
back to `LibraryGrouping` on-the-fly keys, as the Albums/Artists views do.

### Seeds (drift)
Original seed weight 1.0; last played track 0.7; the two before it 0.4 each. The initial
batch uses only the original seed.

### Selection
1. Score all non-excluded songs (excluding the seeds); keep those with score > 0, take
   the top 200.
2. Weighted sampling without replacement, weight = score².
3. ~10% of slots (`count / 10`, min 1 when `count ≥ 5`) are wildcards: uniform random from
   the whole non-excluded library. Wildcards also fill any shortfall when the scored pool
   is smaller than needed.
4. Diversity, enforced while building the ordered batch:
   - ≤ 3 songs per `artistKey` per batch
   - never the same `artistKey` or `albumKey` as the previous song (including the last song
     already in the queue, passed as `previous`)
   - a candidate violating a rule is skipped for that slot and stays in the pool.
5. Returns fewer than `count` only when the library cannot supply them.

### Sizes
Initial: seed + 24. Refill: +20 when ≤ 5 tracks remain after the current one.

### Cost
O(library × seeds) cheap comparisons (~25k × 4); genre keys computed once per call.
Always runs on `Dispatchers.Default`, never Main.

## 3. Player integration

### PlayerState
- `var radioSeed: Song?` (Compose state) — non-null ⇔ radio on.
- Library provider injected like `albumLookup`: `radioLibrary: () -> List<Song>` (wired to
  the hot `LibraryRepository.songs()` StateFlow value).
- `startRadio(seed: Song)`:
  1. `playFrom(listOf(seed), 0)` — playback starts immediately.
  2. set `radioSeed = seed`, session exclude set = `{seed.id}`.
  3. launch single-flight pick (24) off Main → append to queue on Main.
- `startRadioFromCurrent()` (Now Playing button when off): keep current song playing,
  drop upcoming items after `currentIndex`, set `radioSeed = current`, append picks.
- `stopRadio()`: `radioSeed = null`, queue kept, refill stops.
- Refill trigger: on every current-track change (`moveTo`, `syncFromEngine`), if radio on
  and `queue.size - 1 - currentIndex ≤ 5` → single-flight refill of 20. Exclude set = all
  ids queued since radio started. If picks come back empty, retry once with exclude = last
  20 played only; still empty → radio stays on, queue just ends.
- Bulk append: `addToQueue(songs: List<Song>)` — one `addMediaItems` call when not
  windowed; windowed queues rely on the existing sliding window. Single-song `addToQueue`
  delegates to it.
- Radio turns off when user starts other playback: `playFrom` (except from `startRadio`),
  `load`, `clearQueue`.
- Shuffle/reshuffle allowed during radio (reorders upcoming). Repeat One unaffected.
  Repeat All irrelevant while refill keeps the queue ahead.
- Refill exceptions are caught + logged; playback continues. Existing consecutive-error
  stop unchanged.

### Persistence
- `PlaybackStore.Snapshot.radioSeedId: String? = null` (ignoreUnknownKeys already on).
- Restore: resolve id → `radioSeed`; refill resumes on the next track change. Recent-seed
  history derives from the restored queue. Missing id → radio off.

### UI
- Home "Across your sources" card click → `player.startRadio(song)` + navigate to
  Now Playing. Eyebrow text → "Pick one to start a station".
- Now Playing `V5TransportRow`: new Radio `V5TransportButton` after Repeat, `active =
  radioSeed != null`. Off → `startRadioFromCurrent()`; on → `stopRadio()`. Focus wiring
  follows the existing row (no new edge panels).
- Queue panel eyebrow: "UP NEXT · RADIO" while radio on.

## 4. Testing

JVM (`app/src/test`, existing junit + mockito-kotlin harness):
- `RadioPickerTest`: same-artist outranks unrelated; genre overlap counts; year windows;
  folder signals ignore share root; per-artist cap; no back-to-back artist/album;
  exclusions honoured; thin-tag library still returns `count` via wildcards; deterministic
  with seeded `Random`; empty library → empty.
- `GenreKeysTest`: splitting, folding, placeholders, numeric TCON / ID3v1 mapping.
- `TagFallbackReader` ID3v1 genre byte + APE Genre.
- `PlayerState` radio: `startRadio` queues seed first then picks; refill fires at ≤ 5
  remaining and is single-flight; `playFrom` turns radio off; `stopRadio` keeps queue.
- `PlaybackStore` round-trip of `radioSeedId`; old snapshot without it loads.
- Genre backfill selection + stamping (`genre == null && retagVersion < 2`, genre-only
  write, transient error not stamped).

Device (`/verify` skill, com.dink.player): shelf click → queue of 25 starting with the
clicked song; skip to ≤ 5 remaining → queue grows by 20; Now Playing Radio button toggles;
force-stop + relaunch keeps radio on; backfill progress visible in logcat, genres appear.
