# Radio Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clicking a Home "Across your sources" song (or the new Now Playing Radio button) starts an endless, auto-refilling queue of similar songs scored by artist, genre, album artist, era and folder.

**Architecture:** Genre is read from tags (Media3 / ID3v1 / APE / MediaStore), stored on `TrackEntity`, mapped to `Song`, and backfilled once for existing SMB rows. A pure `RadioPicker` scores and samples candidates. `PlayerState` owns radio mode (seed, seen-set, single-flight refill) and asks an injected `RadioSource` for picks off the main thread; the session persists `radioSeedId`.

**Tech Stack:** Kotlin, Jetpack Compose for TV, Media3 ExoPlayer, kotlinx.serialization (JSON index), JUnit 4 + mockito-kotlin.

Spec: `docs/superpowers/specs/2026-10-12-radio-queue-design.md`

## Global Constraints

- Package root: `com.example.dink_smb_player` (source dir `app/src/main/java/com/example/dink_smb_player/`, tests `app/src/test/java/com/example/dink_smb_player/`). Installed appId is `com.dink.player` — never `com.example.dink_smb_player` for adb.
- minSdk 31. Never use `URLEncoder`/`URLDecoder` Charset overloads (crash < API 33).
- AGP 9 built-in Kotlin only; do not add plugins or dependencies.
- Keep `org.gradle.configuration-cache=true` and `org.gradle.caching=true`.
- Index JSON compatibility: every new serialized field is nullable with a default; never rename existing fields.
- Heavy work (scoring 25k songs, SMB reads, mapping the library) never runs on Main.
- New `TrackEntity` / `Song` constructor params go at the END of the parameter list (callers/tests may be positional).
- Match surrounding code style: KDoc/`//` comments explain *why*, same density as neighbours.
- Before running gradle in WSL: `printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties` (Android Studio rewrites it to a Windows path).
- Test command: `./gradlew :app:testDebugUnitTest --tests '<FQCN>'`; full suite `./gradlew :app:testDebugUnitTest`.
- Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01U1NnyFScF8dTB5cMiNjELG
  ```
- Do NOT run `tools/deploy.sh`, `adb install` or force-stop; device deploy/verify is done by the controller at the end.

## File Structure

| File | Responsibility |
|---|---|
| `data/index/GenreKeys.kt` (new) | ID3v1 genre table, numeric-genre resolution, normalized match keys |
| `data/index/Entities.kt` | `TrackEntity.genre` |
| `data/model/Models.kt` | `Song.genre`, `Song.year` |
| `data/library/LibraryRepository.kt` | `toSong` mapping, `RETAG_VERSION` 2, retag reads genre, `backfillGenres` |
| `data/index/TrackMerge.kt` | genre in `patchTags` + Walk re-read branch |
| `data/MediaLibrary.kt`, `data/source/local/MediaStoreAudio.kt` | local genre from MediaStore |
| `data/source/TagReader.kt`, `data/source/TagFallbackReader.kt` | read genre |
| `data/source/smb/SmbImporter.kt` | genre on new + re-read rows |
| `player/radio/RadioPicker.kt` (new) | pure scoring + sampling |
| `player/PlayerState.kt` | radio mode, bulk append, persistence, `RadioSource` wiring |
| `player/PlaybackStore.kt` | `Snapshot.radioSeedId` |
| `DinkApp.kt` | launch the genre backfill |
| `ui/screens/home/HomeScreen.kt` | shelf click → radio |
| `ui/screens/nowplaying/NowPlayingScreen.kt` | Radio button, queue eyebrow |

---

### Task 1: GenreKeys (pure genre helpers)

**Files:**
- Create: `app/src/main/java/com/example/dink_smb_player/data/index/GenreKeys.kt`
- Test: `app/src/test/java/com/example/dink_smb_player/data/index/GenreKeysTest.kt`

**Interfaces:**
- Produces: `object GenreKeys { val ID3V1: List<String>; fun id3v1Name(index: Int): String?; fun resolveDisplay(raw: String?): String?; fun keysOf(raw: String?): Set<String> }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.dink_smb_player.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreKeysTest {

    @Test
    fun `id3v1 table has 192 entries with known anchors`() {
        assertEquals(192, GenreKeys.ID3V1.size)
        assertEquals("Blues", GenreKeys.id3v1Name(0))
        assertEquals("Rock", GenreKeys.id3v1Name(17))
        assertEquals("Hard Rock", GenreKeys.id3v1Name(79))
        assertEquals("Psybient", GenreKeys.id3v1Name(191))
        assertNull(GenreKeys.id3v1Name(255))
        assertNull(GenreKeys.id3v1Name(-1))
    }

    @Test
    fun `numeric ID3 forms resolve, text part wins`() {
        assertEquals("Rock", GenreKeys.resolveDisplay("(17)"))
        assertEquals("Rock", GenreKeys.resolveDisplay("17"))
        assertEquals("Indie", GenreKeys.resolveDisplay("(17)Indie"))
        assertEquals("Remix", GenreKeys.resolveDisplay("(RX)"))
        assertEquals("Cover", GenreKeys.resolveDisplay("(CR)"))
        assertEquals("Shoegaze", GenreKeys.resolveDisplay("  Shoegaze "))
        assertNull(GenreKeys.resolveDisplay("(255)"))
        assertNull(GenreKeys.resolveDisplay("   "))
        assertNull(GenreKeys.resolveDisplay(null))
        assertEquals("1990s", GenreKeys.resolveDisplay("1990s"))
    }

    @Test
    fun `keys fold case, spacing and punctuation`() {
        assertEquals(setOf("hiphop"), GenreKeys.keysOf("Hip-Hop"))
        assertEquals(setOf("hiphop"), GenreKeys.keysOf("hip hop"))
        assertEquals(setOf("hiphop"), GenreKeys.keysOf("HipHop"))
        assertEquals(setOf("rb"), GenreKeys.keysOf("R&B"))
    }

    @Test
    fun `keys split multi-genre values`() {
        assertEquals(setOf("rock", "indie", "postpunk", "shoegaze"), GenreKeys.keysOf("Rock; Indie / Post-Punk, Shoegaze"))
        assertEquals(setOf("rock", "pop"), GenreKeys.keysOf("Rock\u0000Pop"))
        assertEquals(setOf("rock"), GenreKeys.keysOf("(17)"))
    }

    @Test
    fun `placeholders and blanks give no keys`() {
        assertTrue(GenreKeys.keysOf(null).isEmpty())
        assertTrue(GenreKeys.keysOf("").isEmpty())
        assertTrue(GenreKeys.keysOf("Other").isEmpty())
        assertTrue(GenreKeys.keysOf("Unknown; Genre").isEmpty())
        assertEquals(setOf("jazz"), GenreKeys.keysOf("misc; Jazz"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.dink_smb_player.data.index.GenreKeysTest'`
Expected: FAIL — compilation error, `GenreKeys` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.example.dink_smb_player.data.index

/**
 * Genre tag helpers. [resolveDisplay] turns the numeric ID3 forms ("(17)", "17", ID3v1's
 * genre byte) into names; [keysOf] reduces a raw tag to normalized match keys for radio
 * similarity — never shown to the user. A multi-genre tag yields several keys and two
 * tracks match on any overlap.
 */
object GenreKeys {

    /** ID3v1 genre table, 0–79 standard plus the Winamp extensions to 191. 255 = none. */
    val ID3V1: List<String> = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop",
        "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap",
        "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks",
        "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance",
        "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock",
        "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream",
        "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
        "Native American", "Cabaret", "New Wave", "Psychadelic", "Rave", "Showtunes", "Trailer", "Lo-Fi",
        "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
        "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebob", "Latin", "Revival",
        "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock",
        "Big Band", "Chorus", "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson", "Opera",
        "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove", "Satire", "Slow Jam",
        "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
        "Duet", "Punk Rock", "Drum Solo", "A capella", "Euro-House", "Dance Hall", "Goa", "Drum & Bass",
        "Club-House", "Hardcore", "Terror", "Indie", "BritPop", "Afro-Punk", "Polsk Punk", "Beat",
        "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover", "Contemporary Christian", "Christian Rock", "Merengue", "Salsa",
        "Thrash Metal", "Anime", "JPop", "Synthpop", "Abstract", "Art Rock", "Baroque", "Bhangra",
        "Big Beat", "Breakbeat", "Chillout", "Downtempo", "Dub", "EBM", "Eclectic", "Electro",
        "Electroclash", "Emo", "Experimental", "Garage", "Global", "IDM", "Illbient", "Industro-Goth",
        "Jam Band", "Krautrock", "Leftfield", "Lounge", "Math Rock", "New Romantic", "Nu-Breakz", "Post-Punk",
        "Post-Rock", "Psytrance", "Shoegaze", "Space Rock", "Trop Rock", "World Music", "Neoclassical", "Audiobook",
        "Audio Theatre", "Neue Deutsche Welle", "Podcast", "Indie Rock", "G-Funk", "Dubstep", "Garage Rock", "Psybient",
    )

    fun id3v1Name(index: Int): String? = ID3V1.getOrNull(index)

    // ID3v2.3 TCON: "(17)", "(17)Indie" (refinement text wins), "(RX)" remix, "(CR)" cover.
    private val PAREN_FORM = Regex("""^\((\d{1,3}|RX|CR)\)(.*)$""")

    /** Display name for a raw genre tag: numeric forms resolved, whitespace trimmed; null when
     *  blank or an unknown number. Plain text passes through unchanged. */
    fun resolveDisplay(raw: String?): String? {
        val s = raw?.trim()?.ifBlank { null } ?: return null
        PAREN_FORM.find(s)?.let { m ->
            val text = m.groupValues[2].trim()
            if (text.isNotEmpty()) return text
            return when (val code = m.groupValues[1]) {
                "RX" -> "Remix"
                "CR" -> "Cover"
                else -> id3v1Name(code.toInt())
            }
        }
        if (s.length <= 3 && s.all { it.isDigit() }) return id3v1Name(s.toInt())
        return s
    }

    // Tags taggers write when they have nothing real to say; matching on them would link
    // unrelated tracks.
    private val PLACEHOLDERS = setOf("other", "unknown", "genre", "misc", "none")

    /** Normalized match keys: split on ; / , NUL, resolve numeric forms, lowercase, keep only
     *  letters/digits, drop placeholders. "Hip-Hop", "hip hop" and "HipHop" all give "hiphop". */
    fun keysOf(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(';', '/', ',', '\u0000')
            .mapNotNull { part ->
                resolveDisplay(part)?.lowercase()?.filter { it.isLetterOrDigit() }?.ifEmpty { null }
            }
            .filterNot { it in PLACEHOLDERS }
            .toSet()
    }
}
```

Note: `"Pop/Funk"` (ID3v1 #62) splits into `pop` + `funk` — intended.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.dink_smb_player.data.index.GenreKeysTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/dink_smb_player/data/index/GenreKeys.kt app/src/test/java/com/example/dink_smb_player/data/index/GenreKeysTest.kt
git commit -m "feat(library): genre key helpers for radio matching"
```

---

### Task 2: Genre + year on the data model and merges

**Files:**
- Modify: `app/src/main/java/com/example/dink_smb_player/data/index/Entities.kt` (end of `TrackEntity`, after `fileMtimeMs`)
- Modify: `app/src/main/java/com/example/dink_smb_player/data/model/Models.kt:27-46` (`Song`)
- Modify: `app/src/main/java/com/example/dink_smb_player/data/library/LibraryRepository.kt:997` (`TrackEntity.toSong`)
- Modify: `app/src/main/java/com/example/dink_smb_player/data/index/TrackMerge.kt` (`patchTags`, Walk re-read branch, class doc)
- Modify: `app/src/main/java/com/example/dink_smb_player/data/source/local/MediaStoreAudio.kt` (projection + Song)
- Modify: `app/src/main/java/com/example/dink_smb_player/data/MediaLibrary.kt:91` (`toLocalTrack`)
- Test: `app/src/test/java/com/example/dink_smb_player/data/index/TrackMergeTest.kt` (append tests)

**Interfaces:**
- Consumes: `GenreKeys.resolveDisplay` (Task 1).
- Produces: `TrackEntity.genre: String? = null` (last param); `Song.genre: String? = null`, `Song.year: Int? = null` (last two params); `toSong()` maps both.

- [ ] **Step 1: Write the failing tests** — append to `TrackMergeTest` (inside the class, reusing its `row()` / `enrichedPlayed()` helpers):

```kotlin
    @Test
    fun `retag patches genre it read`() {
        val base = enrichedPlayed()
        val incoming = base.copy(genre = "Shoegaze", retagAttemptedMs = 50, retagVersion = 2)
        val merged = TrackMerges.retag(mapOf(base.id to base)).merge(base, incoming)!!
        assertEquals("Shoegaze", merged.genre)
    }

    @Test
    fun `retag keeps a genre another writer set meanwhile`() {
        val base = enrichedPlayed()
        val current = base.copy(genre = "Jazz")
        val incoming = base.copy(title = "Retitled", retagAttemptedMs = 50)
        val merged = TrackMerges.retag(mapOf(base.id to base)).merge(current, incoming)!!
        assertEquals("Jazz", merged.genre)
    }

    @Test
    fun `walk re-read with newer stamp patches genre, older keeps it`() {
        val current = enrichedPlayed().copy(genre = "Rock")
        val reread = row().copy(genre = "Indie", retagAttemptedMs = 100, retagVersion = 2)
        assertEquals("Indie", TrackMerges.Walk.merge(current, reread)!!.genre)
        val noGenre = row().copy(genre = null, retagAttemptedMs = 100, retagVersion = 2)
        assertEquals("Rock", TrackMerges.Walk.merge(current, noGenre)!!.genre)
        val stale = row().copy(genre = "Indie")
        assertEquals("Rock", TrackMerges.Walk.merge(current, stale)!!.genre)
    }

    @Test
    fun `toSong maps genre and year`() {
        val song = row().copy(genre = "Rock", year = 1997).toSong()
        assertEquals("Rock", song.genre)
        assertEquals(1997, song.year)
    }
```

Add `import com.example.dink_smb_player.data.library.toSong` to the file's imports.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.dink_smb_player.data.index.TrackMergeTest'`
Expected: FAIL — `genre` / `Song.year` unresolved.

- [ ] **Step 3: Implement**

`Entities.kt` — append after `val fileMtimeMs: Long? = null,`:

```kotlin
    // Genre tag as read (numeric ID3 forms resolved — see GenreKeys.resolveDisplay); null =
    // untagged or not read yet. Radio similarity matches on GenreKeys.keysOf(genre). Defaulted
    // so old snapshots deserialize; existing rows are filled by LibraryRepository.backfillGenres.
    val genre: String? = null,
```

`Models.kt` — append after `val artistLabel: String? = null,`:

```kotlin
    /** Genre tag (display form) and release year, when the index has them. Radio similarity
     *  uses both; null on mock data and untagged files. */
    val genre: String? = null,
    val year: Int? = null,
```

`LibraryRepository.kt` `toSong()` — add after `artistLabel = artistLabel,`:

```kotlin
    genre = genre,
    year = year,
```

`TrackMerge.kt`:
- class doc line ` *  - tags: title, artist, albumArtist, albumTitle, year, trackNumber, durationMs (+ ...` → insert `genre, ` after `year, `.
- In `Walk`'s `else listed.copy(` block add after `year = incoming.year ?: current.year,`:
  ```kotlin
                    genre = incoming.genre?.ifBlank { null } ?: current.genre,
  ```
- In `patchTags` add after the `year = pick(...)` line:
  ```kotlin
        genre = pick(base.genre, incoming.genre, genre),
  ```

`MediaStoreAudio.kt`:
- projection: add `MediaStore.Audio.AudioColumns.GENRE,` after `MediaStore.Audio.Media.MIME_TYPE,`.
- after `val mimeCol = ...`: `val genreCol = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.GENRE)`
- in `out += Song(` add as last argument: `genre = GenreKeys.resolveDisplay(knownTag(c.getString(genreCol))),`
- import `com.example.dink_smb_player.data.index.GenreKeys`.

`MediaLibrary.kt` `toLocalTrack()` — add after `albumTitle = albumTitle,`: `genre = genre,`.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.dink_smb_player.data.index.TrackMergeTest'` → PASS.
Then full suite: `./gradlew :app:testDebugUnitTest` → all PASS (existing tests construct `TrackEntity`/`Song` with named args; new params are last and defaulted).

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/example/dink_smb_player/data app/src/test/java/com/example/dink_smb_player/data/index/TrackMergeTest.kt
git commit -m "feat(library): store genre on tracks, map genre/year to Song"
```

---

### Task 3: Read genre from tags (Media3, ID3v1, APE) and write it at import

**Files:**
- Modify: `app/src/main/java/com/example/dink_smb_player/data/source/TagReader.kt` (`Tags`, `readResult`)
- Modify: `app/src/main/java/com/example/dink_smb_player/data/source/TagFallbackReader.kt` (`APE_TEXT_KEYS`, `parse`, `parseId3v1`, APE item loop)
- Modify: `app/src/main/java/com/example/dink_smb_player/data/source/smb/SmbImporter.kt` (new-row `TrackEntity(...)` ~line 747, `rereadRow` ~line 787)
- Modify: `app/src/main/java/com/example/dink_smb_player/data/library/LibraryRepository.kt` (retag merge block ~line 790)
- Test: `app/src/test/java/com/example/dink_smb_player/data/source/TagFallbackReaderTest.kt` (append), `app/src/test/java/com/example/dink_smb_player/data/source/smb/SmbRereadRowTest.kt` (append)

**Interfaces:**
- Consumes: `GenreKeys.resolveDisplay`, `GenreKeys.id3v1Name` (Task 1); `TrackEntity.genre` (Task 2).
- Produces: `TagReader.Tags.genre: String? = null` (last param).

- [ ] **Step 1: Write failing tests**

In `TagFallbackReaderTest`, the existing `id3v1(...)` helper builds a 128-byte tag. Add a `genre: Int = 255` parameter to it that writes `b[127] = genre.toByte()` (read the helper first; keep its other behaviour). Then append:

```kotlin
    @Test
    fun `id3v1 genre byte maps through the genre table`() {
        val tags = parse(id3v1(title = "Song".toByteArray(), genre = 17))!!
        assertEquals("Rock", tags.genre)
    }

    @Test
    fun `id3v1 genre 255 means none`() {
        val tags = parse(id3v1(title = "Song".toByteArray(), genre = 255))!!
        assertNull(tags.genre)
    }
```

Also append an APE test using the file's existing `apeItem(key, value)` and `ape(vararg items)` helpers. Build the file bytes exactly the way the existing APE tests do (read one first: `ape(...)` may already be the whole file or may need the same audio-padding prefix those tests add):

```kotlin
    @Test
    fun `ape Genre item is read`() {
        val file = ape(apeItem("Title", "Song".toByteArray()), apeItem("Genre", "Shoegaze".toByteArray()))
        assertEquals("Shoegaze", parse(file)!!.genre)
    }
```

In `SmbRereadRowTest` append. The file already declares a `stored` row used by its other tests (see its line ~35 `SmbImporter.rereadRow(stored, ...)`); reuse it:

```kotlin
    @Test
    fun `re-read patches genre, absent genre keeps stored`() {
        val withGenre = stored.copy(genre = "Rock")
        val found = SmbImporter.rereadRow(withGenre, ReadResult.Found(TagReader.Tags(title = "T", genre = "Indie")), 2000, 900, nowMs = 77)
        assertEquals("Indie", found.genre)
        val noGenre = SmbImporter.rereadRow(withGenre, ReadResult.Found(TagReader.Tags(title = "T")), 2000, 900, nowMs = 77)
        assertEquals("Rock", noGenre.genre)
    }
```
If `stored` is a local inside each test rather than a property, copy its construction into this test.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.dink_smb_player.data.source.TagFallbackReaderTest' --tests 'com.example.dink_smb_player.data.source.smb.SmbRereadRowTest'`
Expected: FAIL — `genre` unresolved.

- [ ] **Step 3: Implement**

`TagReader.kt`:
- `Tags`: add after `val durationMs: Long? = null,` → `val genre: String? = null,` with KDoc `/** Genre, numeric ID3 forms resolved (GenreKeys.resolveDisplay). */`.
- In `readResult`'s `var tags = Tags(` add: `genre = GenreKeys.resolveDisplay(mm?.genre?.toString()),`
- In the fallback merge `tags = tags.copy(` add: `genre = tags.genre ?: fb.genre,`
- import `com.example.dink_smb_player.data.index.GenreKeys`.

`TagFallbackReader.kt`:
- `APE_TEXT_KEYS`: add `"genre"`.
- `parse` merged `TagReader.Tags(`: add `genre = ape?.genre ?: id3v1?.genre,`.
- `parseId3v1` returned `TagReader.Tags(`: add `genre = GenreKeys.id3v1Name(b[127].toInt() and 0xFF),`. Update the layout comment (`· 127 genre`) is already there.
- APE loop: declare `var genre: String? = null` next to the other vars; in `when (field)` add `"genre" -> genre = genre ?: GenreKeys.resolveDisplay(value)`; in the final `TagReader.Tags(` add `genre = genre,`.
- import `com.example.dink_smb_player.data.index.GenreKeys`.

`SmbImporter.kt`:
- New-row `TrackEntity(`: add `genre = tags?.genre,` after `year = tags?.year,`.
- `rereadRow` `reused.copy(`: add `genre = tags?.genre?.ifBlank { null } ?: reused.genre,` after the `year` line.

`LibraryRepository.kt` retag merge (`row.copy(` inside `if (tags != null)`): add `genre = tags.genre?.ifBlank { null } ?: row.genre,` after the `year` line.

- [ ] **Step 4: Run tests**

Same command as Step 2 → PASS. Then `./gradlew :app:testDebugUnitTest` → all PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/example/dink_smb_player/data app/src/test/java/com/example/dink_smb_player/data/source
git commit -m "feat(tags): read genre from Media3, ID3v1 and APE tags"
```

---

### Task 4: One-off genre backfill for existing SMB rows

**Files:**
- Modify: `app/src/main/java/com/example/dink_smb_player/data/library/LibraryRepository.kt` (`RETAG_VERSION`, retag `tagsNeeded`, new `needsGenreBackfill`, `genreBackfilled`, `backfillGenres`)
- Modify: `app/src/main/java/com/example/dink_smb_player/DinkApp.kt` (SMB registry `LaunchedEffect`, ~line 196)
- Test: `app/src/test/java/com/example/dink_smb_player/data/library/GenreBackfillTest.kt` (new)

**Interfaces:**
- Consumes: `TagReader.Tags.genre` (Task 3), `TrackEntity.genre` (Task 2).
- Produces: `LibraryRepository.RETAG_VERSION == 2`; `internal fun needsGenreBackfill(row: TrackEntity): Boolean`; `internal fun genreBackfilled(row: TrackEntity, result: ReadResult<TagReader.Tags>, nowMs: Long): TrackEntity?`; `suspend fun backfillGenres(context: Context): Int`.

Rules (from spec, plus a safety refinement):
- Select rows: `sourceType != Local && genre == null && retagAttemptedMs != null && retagVersion < RETAG_VERSION`. Rows never conclusively read (`retagAttemptedMs == null`) are left to the normal retag/walk, which now reads genre too — stamping them here would hide their missing title/duration from `retagAll`.
- Write only `genre` (and `year` when the row has none) + the stamp. Transient Error → no write (stays selected, retried next launch). Found/Absent → stamped `retagVersion = RETAG_VERSION`.
- In `retagAll`, rows still below the new version re-read tags: `tagsNeeded = force || looksFilenameDerived(row) || row.retagVersion < RETAG_VERSION`, so a duration-only retag doesn't stamp v2 without reading genre.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.dink_smb_player.data.library

import com.example.dink_smb_player.data.index.SourceType
import com.example.dink_smb_player.data.index.TrackEntity
import com.example.dink_smb_player.data.source.ReadResult
import com.example.dink_smb_player.data.source.TagReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GenreBackfillTest {

    private fun row(
        type: SourceType = SourceType.Smb,
        genre: String? = null,
        stampedMs: Long? = 10,
        version: Int = 1,
        year: Int? = null,
    ) = TrackEntity(
        id = "t", title = "Real Title", artist = "A", durationMs = 200_000, sourceType = type,
        sourceId = "s", path = "/m/t.mp3", uri = "smb://nas/m/t.mp3", sizeBytes = 1, addedAtMs = 0,
        retagAttemptedMs = stampedMs, retagVersion = version, year = year, genre = genre,
    )

    @Test
    fun `retag version is 2`() = assertEquals(2, LibraryRepository.RETAG_VERSION)

    @Test
    fun `selects stamped old-version SMB rows without genre only`() {
        assertTrue(LibraryRepository.needsGenreBackfill(row()))
        assertFalse(LibraryRepository.needsGenreBackfill(row(genre = "Rock")))
        assertFalse(LibraryRepository.needsGenreBackfill(row(version = 2)))
        assertFalse(LibraryRepository.needsGenreBackfill(row(stampedMs = null)))
        assertFalse(LibraryRepository.needsGenreBackfill(row(type = SourceType.Local)))
    }

    @Test
    fun `found writes genre and missing year only, and stamps`() {
        val r = row(year = null)
        val out = LibraryRepository.genreBackfilled(
            r, ReadResult.Found(TagReader.Tags(title = "Other Title", artist = "B", year = 1999, genre = "Jazz")), 500,
        )!!
        assertEquals("Jazz", out.genre)
        assertEquals(1999, out.year)
        assertEquals("Real Title", out.title)
        assertEquals("A", out.artist)
        assertEquals(500L, out.retagAttemptedMs)
        assertEquals(2, out.retagVersion)
        val keepYear = LibraryRepository.genreBackfilled(
            row(year = 1980), ReadResult.Found(TagReader.Tags(year = 1999, genre = "Jazz")), 500,
        )!!
        assertEquals(1980, keepYear.year)
    }

    @Test
    fun `absent stamps without genre, transient error writes nothing`() {
        val absent = LibraryRepository.genreBackfilled(row(), ReadResult.Absent, 500)!!
        assertNull(absent.genre)
        assertEquals(2, absent.retagVersion)
        assertNull(LibraryRepository.genreBackfilled(row(), ReadResult.Error(IOException("down"), null), 500))
    }
}
```

Check `ReadResult`'s package/constructors in `data/source/ReadResult.kt` and adjust imports/arguments if they differ (e.g. `ReadResult.Error(cause, partial)`).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.dink_smb_player.data.library.GenreBackfillTest'`
Expected: FAIL — unresolved `needsGenreBackfill` / `genreBackfilled`.

- [ ] **Step 3: Implement**

In `LibraryRepository.kt`:

1. Bump the version and extend its KDoc:
```kotlin
     *  v1: transient read failures are no longer stamped (v0 stamped every outcome).
     *  v2: genre is read; rows stamped earlier get it from [backfillGenres]. */
    internal const val RETAG_VERSION = 2
```

2. In `retagAllGated`, change
```kotlin
                                val tagsNeeded = force || looksFilenameDerived(row)
```
to
```kotlin
                                // Below the current version the row was read before genre was —
                                // read tags too, or stamping v2 here would skip it for backfill.
                                val tagsNeeded = force || looksFilenameDerived(row) || row.retagVersion < RETAG_VERSION
```

3. Add next to `retagStamped`:
```kotlin
    /** Whether [backfillGenres] should read [row]: a remote row conclusively read by an older
     *  reader (no genre support) that has no genre. Never-read rows are left to retag/walk,
     *  which read genre now — stamping them here would hide a missing title or duration. */
    internal fun needsGenreBackfill(row: TrackEntity): Boolean =
        row.sourceType != SourceType.Local && row.genre == null &&
            row.retagAttemptedMs != null && row.retagVersion < RETAG_VERSION

    /** The row [backfillGenres] writes for [result]: genre (and year when the row has none)
     *  plus the stamp; every other field untouched. Null on a transient error (retry later). */
    internal fun genreBackfilled(row: TrackEntity, result: ReadResult<TagReader.Tags>, nowMs: Long): TrackEntity? {
        if (result is ReadResult.Error) return null
        val tags = result.valueOrNull()
        return row.copy(
            genre = tags?.genre?.ifBlank { null } ?: row.genre,
            year = row.year ?: tags?.year,
            retagAttemptedMs = nowMs,
            retagVersion = RETAG_VERSION,
        )
    }
```

4. Add the pass after `retagAllGated` (same object). It shares `retagGate` so it never overlaps a manual rescan:
```kotlin
    /**
     * One-off (resumable) genre fill for rows indexed before genre was read: tags-only reads,
     * low concurrency so it doesn't compete with playback streaming, persisted per chunk.
     * Writes only genre/year + stamp via [TrackMerges.retag], so plays and enrichment that land
     * meanwhile survive. Returns the number of rows that gained a genre. Single-flight with
     * [retagAll]; returns 0 if a rescan is running (the next launch picks up the rest).
     */
    suspend fun backfillGenres(context: Context): Int {
        if (!retagGate.compareAndSet(false, true)) return 0
        try {
            ensureRestored(context)
            val dao = dao(context)
            val rows = dao.snapshot().first.filter { needsGenreBackfill(it) && dao.acceptsWrites(it.sourceId) }
            if (rows.isEmpty()) return 0
            android.util.Log.i("LibraryRepository", "genre backfill: ${rows.size} rows")
            val gate = Semaphore(GENRE_BACKFILL_CONCURRENCY)
            val gained = AtomicInteger(0)
            withContext(Dispatchers.IO) {
                val bases = rows.associateBy { it.id }
                rows.chunked(RETAG_CHUNK).forEach { chunk ->
                    val updates = Collections.synchronizedList(ArrayList<TrackEntity>())
                    coroutineScope {
                        chunk.forEach { row ->
                            launch {
                                if (!dao.acceptsWrites(row.sourceId)) return@launch
                                gate.withPermit {
                                    val knownSize = row.sizeBytes.takeIf { it > 0 } ?: -1L
                                    val result = TagReadGate.withPermit(row.path) {
                                        SmbProbe.session(row.uri, knownSize) {
                                            TagReader.readResult(context, row.uri, tagsNeeded = true, durationNeeded = false)
                                        }
                                    }
                                    genreBackfilled(row, result, System.currentTimeMillis())?.let { out ->
                                        if (out.genre != null) gained.incrementAndGet()
                                        updates.add(out)
                                    }
                                }
                            }
                        }
                    }
                    if (updates.isNotEmpty()) {
                        dao.upsertTracks(updates, TrackMerges.retag(bases))
                        persist(context).onFailure { t ->
                            android.util.Log.e("LibraryRepository", "genre backfill: chunk not saved — ${t.message}")
                        }
                    }
                }
            }
            android.util.Log.i("LibraryRepository", "genre backfill: done, ${gained.get()} gained a genre")
            return gained.get()
        } finally {
            retagGate.set(false)
        }
    }
```
and next to the other retag constants:
```kotlin
    /** In-flight reads for [backfillGenres]: well under the rescan's caps — it runs unasked
     *  in the background, often while music streams from the same NAS. */
    private const val GENRE_BACKFILL_CONCURRENCY = 4
```
`TagReadGate`, `SmbProbe`, `Semaphore`, `withPermit`, `AtomicInteger`, `Collections`, `coroutineScope`, `launch` are already imported for `retagAllGated` — reuse them; add any missing import. If `TrackMerges.retag`'s `patchTags` doesn't carry the stamp fields, it does (retag merge picks `retagAttemptedMs`/`retagVersion`) — verified in Task 2's file.

5. `DinkApp.kt` — in the SMB-infrastructure `LaunchedEffect(Unit)` (the one calling `SmbConnectionRegistry.installCredLookup`), right after the `withContext(Dispatchers.IO) { secretStore.purgeLegacyCloudTokens(); ... }` block and BEFORE `sharePrefs.shares.collect { ... }` (collect never returns), launch the backfill on the app scope so it outlives the composition:
```kotlin
        // One-off genre fill for rows indexed before genre was read (radio similarity).
        // Delayed past cold start; resumable, so a kill mid-way just continues next launch.
        (context.applicationContext as? DinkApplication)?.appScope?.launch(Dispatchers.Default) {
            delay(GENRE_BACKFILL_DELAY_MS)
            runCatching { LibraryRepository.backfillGenres(context.applicationContext) }
                .onFailure { if (it is CancellationException) throw it; android.util.Log.e("DinkApp", "genre backfill failed", it) }
        }
```
Define `private const val GENRE_BACKFILL_DELAY_MS = 60_000L` at file top level in `DinkApp.kt`. The registry is populated by the `collect` that starts right after; the 60 s delay covers that. Add imports `kotlinx.coroutines.delay`, `kotlinx.coroutines.launch`, `kotlin.coroutines.cancellation.CancellationException` if missing.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.dink_smb_player.data.library.GenreBackfillTest'` → PASS.
Then `./gradlew :app:testDebugUnitTest` → all PASS. If a test hardcodes `retagVersion = 1` as "current" and now fails, change it to `LibraryRepository.RETAG_VERSION` (only where it means "current version"; leave historical `1` values in TrackMergeTest alone if they pass).
Then `./gradlew :app:assembleDebug -q` → builds.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/example/dink_smb_player app/src/test/java/com/example/dink_smb_player/data/library
git commit -m "feat(library): one-off background genre backfill for existing SMB rows"
```

---

### Task 5: RadioPicker (pure scoring + sampling)

**Files:**
- Create: `app/src/main/java/com/example/dink_smb_player/player/radio/RadioPicker.kt`
- Test: `app/src/test/java/com/example/dink_smb_player/player/radio/RadioPickerTest.kt`

**Interfaces:**
- Consumes: `Song.genre`, `Song.year`, `Song.artistKey`, `Song.albumKey` (Task 2); `GenreKeys.keysOf` (Task 1); `LibraryGrouping.normKey`, `LibraryGrouping.fallbackAlbumKey`, `LibraryGrouping.albumArtistKeyOf` (existing, thread-safe caches).
- Produces:
  ```kotlin
  data class WeightedSeed(val song: Song, val weight: Double)
  object RadioPicker {
      fun pick(seeds: List<WeightedSeed>, library: List<Song>, exclude: Set<String>,
               previous: Song?, count: Int, random: kotlin.random.Random): List<Song>
  }
  ```
  `previous` = the song currently last in the queue (diversity check for the first pick).

Scoring (spec §2) with one refinement replacing "below the share root": a folder signal counts only when that folder is small — parent holds ≤ `MAX_PARENT_FOLDER` (300) library songs, grandparent ≤ `MAX_GRANDPARENT_FOLDER` (1500). A flat dump folder or share root then gives no signal, whatever the mount layout. Generic artist keys (`unknown`, `unknownartist`, `variousartists`, `va`, empty) never score the artist bonus.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.dink_smb_player.player.radio

import com.example.dink_smb_player.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RadioPickerTest {

    private fun song(
        id: String,
        artist: String = "Artist $id",
        album: String? = "Album $id",
        genre: String? = null,
        year: Int? = null,
        // Own folder per song, no grandparent: by default songs share no folder signal.
        path: String = "/$id/$id.mp3",
    ) = Song(
        id = id, title = "T$id", artist = artist, albumId = null, albumTitle = album, durationSec = 200,
        playCount = 0, sourcePath = path, bitrate = "MP3", mediaUri = "smb://nas$path",
        genre = genre, year = year,
    )

    private fun pick(seed: Song, lib: List<Song>, count: Int, exclude: Set<String> = emptySet(), previous: Song? = seed, rnd: Int = 1) =
        RadioPicker.pick(listOf(WeightedSeed(seed, 1.0)), lib, exclude, previous, count, Random(rnd))

    // 300 unrelated filler songs so similarity, not lack of choice, decides.
    private val filler = (1..300).map { song("f$it", genre = "Polka", year = 1950) }

    @Test
    fun `same-artist songs dominate`() {
        val seed = song("s", artist = "Muse", genre = "Rock", year = 2001)
        val museTracks = (1..3).map { song("m$it", artist = "Muse", album = "Album m$it") }
        val picks = pick(seed, listOf(seed) + museTracks + filler, count = 10)
        assertEquals(10, picks.size)
        assertTrue(picks.map { it.id }.containsAll(museTracks.map { it.id }))
    }

    @Test
    fun `genre overlap counts`() {
        val seed = song("s", genre = "Shoegaze")
        val gaze = (1..8).map { song("g$it", genre = "Rock; Shoegaze") }
        val picks = pick(seed, listOf(seed) + gaze + filler, count = 10)
        assertTrue(picks.count { it.genre?.contains("Shoegaze") == true } >= 7)
    }

    @Test
    fun `year window counts when no stronger signal`() {
        val seed = song("s", year = 1994, genre = null)
        val near = (1..8).map { song("y$it", year = 1993 + (it % 3)) }
        val far = (1..300).map { song("z$it", year = 2020) }
        val picks = pick(seed, listOf(seed) + near + far, count = 10)
        assertTrue(picks.count { it.id.startsWith("y") } >= 7)
    }

    @Test
    fun `small folder counts, big flat folder does not`() {
        val seed = song("s", path = "/music/Band/Record/s.mp3")
        val siblings = (1..8).map { song("b$it", path = "/music/Band/Record/b$it.mp3") }
        val flat = (1..400).map { song("x$it", path = "/music/dump/x$it.mp3") }
        val picks = pick(seed, listOf(seed) + siblings + flat, count = 10)
        assertTrue(picks.count { it.id.startsWith("b") } >= 7)
        // seed in the big flat folder: its neighbours get no folder bonus → no scored pool → random
        val flatSeed = flat.first()
        val flatPicks = pick(flatSeed, listOf(seed) + siblings + flat, count = 10)
        assertEquals(10, flatPicks.size)
    }

    @Test
    fun `at most three per artist and never back to back`() {
        val seed = song("s", artist = "Muse", genre = "Rock")
        val muse = (1..20).map { song("m$it", artist = "Muse", album = "A${it % 4}", genre = "Rock") }
        val rock = (1..20).map { song("r$it", genre = "Rock") }
        val picks = pick(seed, listOf(seed) + muse + rock + filler, count = 20)
        assertTrue(picks.count { it.artist == "Muse" } <= 3)
        val keys = (listOf(seed) + picks).map { it.artist }
        keys.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test
    fun `excluded and seed songs never returned, no duplicates`() {
        val seed = song("s", artist = "Muse")
        val muse = (1..5).map { song("m$it", artist = "Muse", album = "A$it") }
        val picks = pick(seed, listOf(seed) + muse + filler, count = 30, exclude = setOf("m1", "m2"))
        val ids = picks.map { it.id }
        assertFalse("s" in ids)
        assertFalse("m1" in ids || "m2" in ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `thin tags still fill the batch from wildcards`() {
        val seed = song("s", artist = "Unknown", album = null)
        val lib = listOf(seed) + (1..50).map { song("u$it", artist = "Unknown", album = null) }
        assertEquals(20, pick(seed, lib, count = 20).size)
    }

    @Test
    fun `returns fewer only when the library runs out`() {
        val seed = song("s")
        val lib = listOf(seed, song("a"), song("b"))
        assertEquals(2, pick(seed, lib, count = 20).size)
        assertTrue(pick(seed, emptyList(), count = 5).isEmpty())
        assertTrue(pick(seed, lib, count = 0).isEmpty())
    }

    @Test
    fun `deterministic for a seeded random`() {
        val seed = song("s", genre = "Rock")
        val lib = listOf(seed) + (1..40).map { song("r$it", genre = "Rock") } + filler
        assertEquals(pick(seed, lib, 20, rnd = 7), pick(seed, lib, 20, rnd = 7))
    }

    @Test
    fun `generic artist names do not match each other`() {
        val seed = song("s", artist = "Various Artists", album = null)
        val va = (1..10).map { song("v$it", artist = "Various Artists", album = null) }
        val others = (1..10).map { song("o$it", genre = null) }
        // no signal at all → pure wildcards; just ensure no crash and full batch
        assertEquals(15, pick(seed, listOf(seed) + va + others, count = 15).size)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.dink_smb_player.player.radio.RadioPickerTest'`
Expected: FAIL — `RadioPicker` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.example.dink_smb_player.player.radio

import com.example.dink_smb_player.data.index.GenreKeys
import com.example.dink_smb_player.data.index.LibraryGrouping
import com.example.dink_smb_player.data.model.Song
import kotlin.math.abs
import kotlin.random.Random

/** A song the station is built around, and how strongly it pulls (original seed 1.0;
 *  recently played tracks less — that's what makes the station drift). */
data class WeightedSeed(val song: Song, val weight: Double)

/**
 * Radio track selection. Pure (no Android, no I/O) — call off the main thread: it scores
 * the whole library against every seed.
 *
 * Score per seed: same artist +5, genre overlap +4, same album artist +3, year within ±3 / ±8
 * +2 / +1, same small parent / grandparent folder +2 / +1; summed with seed weights. The top
 * [POOL_SIZE] are sampled weighted by score², about a tenth of the slots are uniform
 * wildcards (they also fill any shortfall), and diversity rules keep one artist from taking
 * over: ≤ [MAX_PER_ARTIST] per batch, never the same artist or album twice in a row.
 */
object RadioPicker {

    internal const val POOL_SIZE = 200
    internal const val MAX_PER_ARTIST = 3
    /** Folder-signal caps: a parent holding more songs than this is a dump folder or share
     *  root, not an album/artist folder, so sharing it says nothing about similarity. */
    internal const val MAX_PARENT_FOLDER = 300
    internal const val MAX_GRANDPARENT_FOLDER = 1500

    private val GENERIC_ARTISTS = setOf("", "unknown", "unknownartist", "variousartists", "va")

    private class Features(
        val artist: String,
        val album: String?,
        val albumArtist: String?,
        val genres: Set<String>,
        val year: Int?,
        val parent: String?,
        val grandparent: String?,
    )

    private fun featuresOf(song: Song): Features {
        val artist = song.artistKey ?: LibraryGrouping.normKey(song.artist)
        val album = song.albumKey ?: song.albumTitle?.let { LibraryGrouping.fallbackAlbumKey(song.artist, it) }
        val albumArtist = album?.let { LibraryGrouping.albumArtistKeyOf(it) }?.takeUnless { it in GENERIC_ARTISTS }
        val dirs = song.sourcePath.replace('\\', '/').split('/').filter { it.isNotBlank() }.dropLast(1)
        return Features(
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genres = GenreKeys.keysOf(song.genre),
            year = song.year,
            parent = dirs.takeIf { it.isNotEmpty() }?.joinToString("/"),
            grandparent = dirs.takeIf { it.size >= 2 }?.dropLast(1)?.joinToString("/"),
        )
    }

    fun pick(
        seeds: List<WeightedSeed>,
        library: List<Song>,
        exclude: Set<String>,
        previous: Song?,
        count: Int,
        random: Random,
    ): List<Song> {
        if (count <= 0 || library.isEmpty()) return emptyList()
        val seedIds = seeds.mapTo(HashSet()) { it.song.id }
        val features = HashMap<String, Features>(library.size * 2)
        library.forEach { features[it.id] = featuresOf(it) }
        // Folder sizes over the whole library decide which folders are informative.
        val parentSize = HashMap<String, Int>()
        val grandSize = HashMap<String, Int>()
        features.values.forEach { f ->
            f.parent?.let { parentSize.merge(it, 1, Int::plus) }
            f.grandparent?.let { grandSize.merge(it, 1, Int::plus) }
        }
        val candidates = library.filter { it.id !in exclude && it.id !in seedIds }.distinctBy { it.id }
        if (candidates.isEmpty()) return emptyList()

        val seedFeatures = seeds.map { (featuresOf(it.song)) to it.weight }
        fun similarity(s: Features, c: Features): Double {
            var p = 0.0
            if (s.artist !in GENERIC_ARTISTS && s.artist == c.artist) p += 5
            if (s.genres.isNotEmpty() && c.genres.any { it in s.genres }) p += 4
            if (s.albumArtist != null && s.albumArtist == c.albumArtist) p += 3
            if (s.year != null && c.year != null) {
                val d = abs(s.year - c.year)
                if (d <= 3) p += 2 else if (d <= 8) p += 1
            }
            val parentOk = s.parent != null && s.parent == c.parent && (parentSize[s.parent] ?: 0) <= MAX_PARENT_FOLDER
            val grandOk = s.grandparent != null && s.grandparent == c.grandparent &&
                (grandSize[s.grandparent] ?: 0) <= MAX_GRANDPARENT_FOLDER
            if (parentOk) p += 2 else if (grandOk) p += 1
            return p
        }

        val pool = candidates
            .map { c -> val f = features.getValue(c.id); c to seedFeatures.sumOf { (s, w) -> w * similarity(s, f) } }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(POOL_SIZE)
            .toMutableList()

        val out = ArrayList<Song>(count)
        val used = HashSet<String>()
        val perArtist = HashMap<String, Int>()
        var prev: Features? = previous?.let { features[it.id] ?: featuresOf(it) }

        fun fits(f: Features): Boolean {
            if ((perArtist[f.artist] ?: 0) >= MAX_PER_ARTIST) return false
            val p = prev ?: return true
            return f.artist != p.artist && (f.album == null || f.album != p.album)
        }
        fun take(song: Song) {
            val f = features.getValue(song.id)
            out += song
            used += song.id
            perArtist.merge(f.artist, 1, Int::plus)
            prev = f
        }

        fun pickScored(): Song? {
            val eligible = pool.indices.filter { fits(features.getValue(pool[it].first.id)) }
            if (eligible.isEmpty()) return null
            val total = eligible.sumOf { pool[it].second * pool[it].second }
            var r = random.nextDouble() * total
            var chosen = eligible.last()
            for (i in eligible) {
                r -= pool[i].second * pool[i].second
                if (r <= 0.0) { chosen = i; break }
            }
            return pool.removeAt(chosen).first
        }

        val wildOrder = candidates.shuffled(random)
        fun pickWild(): Song? =
            wildOrder.firstOrNull { it.id !in used && fits(features.getValue(it.id)) }
                // Diversity is a preference: break it rather than end the station early.
                ?: wildOrder.firstOrNull { it.id !in used }

        val wildSlots = if (count >= 5) maxOf(1, count / 10) else 0
        val wildAt = (0 until count).shuffled(random).take(wildSlots).toHashSet()
        for (slot in 0 until count) {
            val song = (if (slot in wildAt) null else pickScored()) ?: pickWild() ?: break
            pool.removeAll { it.first.id == song.id }
            take(song)
        }
        return out
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.dink_smb_player.player.radio.RadioPickerTest'` → PASS (10 tests). If a probabilistic assertion (`>= 7`) is flaky for the fixed seed, raise the signal group size, never weaken the scoring.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/dink_smb_player/player/radio app/src/test/java/com/example/dink_smb_player/player/radio
git commit -m "feat(player): RadioPicker similarity scoring and sampling"
```

---

### Task 6: Radio mode in PlayerState + persistence + RadioSource wiring

**Files:**
- Modify: `app/src/main/java/com/example/dink_smb_player/player/PlayerState.kt`
- Modify: `app/src/main/java/com/example/dink_smb_player/player/PlaybackStore.kt:36-49` (`Snapshot`)
- Test: `app/src/test/java/com/example/dink_smb_player/player/PlayerStateRadioTest.kt` (new), `app/src/test/java/com/example/dink_smb_player/player/PlaybackStoreTest.kt` (append)

**Interfaces:**
- Consumes: `RadioPicker.pick`, `WeightedSeed` (Task 5); `LibraryRepository.songsNow(context)` (existing); `DinkApplication.appScope` (existing, Main-dispatched).
- Produces (used by Task 7):
  ```kotlin
  // in PlayerState
  fun interface RadioSource {
      fun request(seeds: List<WeightedSeed>, exclude: Set<String>, previous: Song?, count: Int, onResult: (List<Song>) -> Unit)
  }
  var radioSource: RadioSource?
  var radioSeed: Song?            // Compose state, private set; non-null ⇔ radio on
  fun startRadio(seed: Song)
  fun startRadioFromCurrent()
  fun stopRadio()
  fun addAllToQueue(songs: List<Song>)
  // PlaybackStore.Snapshot
  val radioSeedId: String? = null
  ```

Behaviour:
- Constants in `PlayerState.companion`: `RADIO_INITIAL = 24`, `RADIO_REFILL = 20`, `RADIO_LOW_WATER = 5`, `RADIO_RELAXED_HISTORY = 20`.
- `startRadio(seed)`: `playFrom(listOf(seed), 0)` (plays at once; ends any prior radio), then begin radio with seed, request `RADIO_INITIAL`.
- `startRadioFromCurrent()`: no-op without a current song; drops queue items after `currentIndex` (and the matching engine items) WITHOUT interrupting playback; begins radio seeded by the current song; requests `RADIO_INITIAL`.
- `stopRadio()`: radio off, queue unchanged.
- Refill: after every current-track change (`moveTo`, `syncFromEngine`), when radio is on, no request in flight and `queue.size - 1 - currentIndex <= RADIO_LOW_WATER` → request `RADIO_REFILL`.
- Seeds: original seed 1.0; `queue[currentIndex]` 0.7, `queue[currentIndex-1]` 0.4, `queue[currentIndex-2]` 0.4 (skipping any that IS the seed).
- Exclude: every id queued since radio began. If the result (after filtering seen ids) is empty → one retry excluding only the last `RADIO_RELAXED_HISTORY` played ids; still empty → nothing appended (the next track change tries again).
- Results arriving after `stopRadio`/`startRadio`/`playFrom` (generation changed) are dropped.
- Radio turns off in `playFrom` (top), `clearQueue`, `adoptEngineSession`, and `load` when the loaded song isn't the current one.
- `addToQueue(song)` delegates to `addAllToQueue(listOf(song))`; engine append uses `addMediaItem` for one song (existing tests verify it) and `addMediaItems` for several.
- Persistence: `snapshot()` writes `radioSeedId = radioSeed?.id`; `saveSignature()` appends `|${radioSeed?.id}`; `restore()` re-enables radio when `byId[radioSeedId]` resolves (no request until the next track change).

- [ ] **Step 1: Write the failing tests**

`PlayerStateRadioTest.kt`:

```kotlin
package com.example.dink_smb_player.player

import androidx.media3.common.Player
import com.example.dink_smb_player.data.model.Song
import com.example.dink_smb_player.player.radio.WeightedSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlayerStateRadioTest {

    private fun song(id: String) = Song(
        id = id, title = "T$id", artist = "A$id", albumId = null, albumTitle = null, durationSec = 180,
        playCount = 0, sourcePath = "/music/$id.mp3", bitrate = "320", mediaUri = "smb://nas/music/$id.mp3",
    )

    /** Synchronous fake: records each request and answers with fresh songs r1, r2, … */
    private class FakeSource : PlayerState.RadioSource {
        val requests = mutableListOf<Triple<List<WeightedSeed>, Set<String>, Int>>()
        var next = 1
        var answer: ((Set<String>, Int) -> List<Song>)? = null
        var deferred: (() -> Unit)? = null
        var defer = false
        override fun request(seeds: List<WeightedSeed>, exclude: Set<String>, previous: Song?, count: Int, onResult: (List<Song>) -> Unit) {
            requests += Triple(seeds, exclude, count)
            val result = answer?.invoke(exclude, count) ?: List(count) { mk("r${next++}") }
            if (defer) deferred = { onResult(result) } else onResult(result)
        }
        private fun mk(id: String) = Song(
            id = id, title = id, artist = "X$id", albumId = null, albumTitle = null, durationSec = 180,
            playCount = 0, sourcePath = "/m/$id.mp3", bitrate = "320", mediaUri = "smb://nas/m/$id.mp3",
        )
    }

    private class Rig {
        val engine: Player = mock()
        val state = PlayerState()
        val source = FakeSource()
        val listener: Player.Listener
        init {
            whenever(engine.mediaItemCount).thenReturn(0)
            state.attachEngine(engine)
            val captor = argumentCaptor<Player.Listener>()
            verify(engine).addListener(captor.capture())
            listener = captor.firstValue
            state.radioSource = source
        }
    }

    @Test
    fun `startRadio plays the seed first then appends the initial batch`() {
        val rig = Rig()
        val seed = song("s")
        rig.state.startRadio(seed)
        assertEquals(seed, rig.state.currentSong)
        assertEquals(seed, rig.state.radioSeed)
        assertEquals(1 + PlayerState.RADIO_INITIAL, rig.state.queue.size)
        assertEquals("s", rig.state.queue.first().id)
        val (seeds, exclude, count) = rig.source.requests.single()
        assertEquals(PlayerState.RADIO_INITIAL, count)
        assertEquals(listOf("s"), seeds.map { it.song.id })
        assertEquals(setOf("s"), exclude)
    }

    @Test
    fun `refill fires at low water, once, excluding everything queued`() {
        val rig = Rig()
        rig.state.startRadio(song("s"))
        val size = rig.state.queue.size // 25
        // Jump to the track leaving exactly RADIO_LOW_WATER after it.
        rig.state.jumpTo(size - 1 - PlayerState.RADIO_LOW_WATER)
        assertEquals(2, rig.source.requests.size)
        assertEquals(size + PlayerState.RADIO_REFILL, rig.state.queue.size)
        assertEquals(size, rig.source.requests[1].second.size) // all 25 queued ids excluded
        // Seeds drift: original + current (+ the two before it).
        val seedIds = rig.source.requests[1].first.map { it.song.id }
        assertEquals("s", seedIds.first())
        assertEquals(4, seedIds.size)
    }

    @Test
    fun `no refill above low water`() {
        val rig = Rig()
        rig.state.startRadio(song("s"))
        rig.state.jumpTo(1)
        assertEquals(1, rig.source.requests.size)
    }

    @Test
    fun `in-flight refill is single-flight`() {
        val rig = Rig()
        rig.state.startRadio(song("s"))
        rig.source.defer = true
        val size = rig.state.queue.size
        rig.state.jumpTo(size - 3)
        rig.state.jumpTo(size - 2)
        assertEquals(2, rig.source.requests.size)
        rig.source.deferred!!.invoke()
        assertEquals(size + PlayerState.RADIO_REFILL, rig.state.queue.size)
    }

    @Test
    fun `empty result retries once with relaxed exclusions`() {
        val rig = Rig()
        var calls = 0
        rig.source.answer = { _, _ -> calls++; emptyList() }
        rig.state.startRadio(song("s"))
        assertEquals(2, calls)
        assertEquals(1, rig.state.queue.size)
        assertEquals(song("s"), rig.state.radioSeed)
    }

    @Test
    fun `playFrom turns radio off and drops late results`() {
        val rig = Rig()
        rig.source.defer = true
        rig.state.startRadio(song("s"))
        rig.state.playFrom(listOf(song("a"), song("b")), 0)
        assertNull(rig.state.radioSeed)
        rig.source.deferred!!.invoke()
        assertEquals(listOf("a", "b"), rig.state.queue.map { it.id })
    }

    @Test
    fun `stopRadio keeps the queue and stops refilling`() {
        val rig = Rig()
        rig.state.startRadio(song("s"))
        val q = rig.state.queue.map { it.id }
        rig.state.stopRadio()
        assertNull(rig.state.radioSeed)
        rig.state.jumpTo(q.size - 1)
        assertEquals(q, rig.state.queue.map { it.id })
        assertEquals(1, rig.source.requests.size)
    }

    @Test
    fun `startRadioFromCurrent keeps the current song and replaces upcoming`() {
        val rig = Rig()
        rig.state.playFrom(listOf(song("a"), song("b"), song("c"), song("d")), 1)
        rig.state.startRadioFromCurrent()
        assertEquals("b", rig.state.currentSong?.id)
        assertEquals(listOf("a", "b"), rig.state.queue.take(2).map { it.id })
        assertTrue(rig.state.queue.drop(2).all { it.id.startsWith("r") })
        assertEquals("b", rig.state.radioSeed?.id)
    }

    @Test
    fun `snapshot carries the radio seed and restore re-enables radio`() {
        val rig = Rig()
        rig.state.startRadio(song("s"))
        val snap = rig.state.snapshot()!!
        assertEquals("s", snap.radioSeedId)
        val fresh = PlayerState()
        val byId = rig.state.queue.associateBy { it.id }
        fresh.restore(snap, byId)
        assertEquals("s", fresh.radioSeed?.id)
    }
}
```

Append to `PlaybackStoreTest`:

```kotlin
    @Test
    fun `radio seed round trips and old snapshots load without it`() = runBlocking {
        PlaybackStore.saveIn(dir, snap().copy(radioSeedId = "b"))
        assertEquals("b", PlaybackStore.loadFrom(dir)?.radioSeedId)
        File(dir, "nowplaying.json").writeText("""{"queueIds":["a"],"index":0}""")
        assertNull(PlaybackStore.loadFrom(dir)?.radioSeedId)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.dink_smb_player.player.PlayerStateRadioTest' --tests 'com.example.dink_smb_player.player.PlaybackStoreTest'`
Expected: FAIL — `RadioSource`, `startRadio`, `radioSeedId` unresolved.

- [ ] **Step 3: Implement**

`PlaybackStore.kt` `Snapshot` — add after `baseOrder`:
```kotlin
        /** Seed song id while the session is a radio station (PlayerState.radioSeed), so a
         *  relaunch keeps refilling; null = ordinary queue. */
        val radioSeedId: String? = null,
```

`PlayerState.kt`:

(a) Imports: `com.example.dink_smb_player.player.radio.RadioPicker`, `com.example.dink_smb_player.player.radio.WeightedSeed`, `kotlinx.coroutines.launch`, `kotlinx.coroutines.withContext`, `kotlin.coroutines.cancellation.CancellationException`, `com.example.dink_smb_player.DinkApplication` (check which already exist).

(b) Radio state + API — add a `// --- Radio ---` section after `clearQueue()`:

```kotlin
    // --- Radio -------------------------------------------------------------------

    /** Computes radio picks OFF the main thread and calls [onResult] back ON main. Injected
     *  (see [shared]) so this façade stays free of coroutines and the library. */
    fun interface RadioSource {
        fun request(seeds: List<WeightedSeed>, exclude: Set<String>, previous: Song?, count: Int, onResult: (List<Song>) -> Unit)
    }

    var radioSource: RadioSource? = null

    /** The song the current station was started from; null = not in radio mode. */
    var radioSeed by mutableStateOf<Song?>(null)
        private set

    // Every id queued since the station began — never picked again this session.
    private val radioSeen = HashSet<String>()
    private var radioInFlight = false
    // Bumped on every begin/end so a pick that returns after the user moved on is dropped.
    private var radioGeneration = 0

    /** Start a station from [seed]: plays it at once, similar songs follow. */
    fun startRadio(seed: Song) {
        playFrom(listOf(seed), 0)
        beginRadio(seed)
        requestRadioBatch(RADIO_INITIAL)
    }

    /** Turn the current queue into a station seeded by what's playing; playback continues. */
    fun startRadioFromCurrent() {
        val cur = currentSong ?: return
        if (currentIndex !in _queue.indices) return
        dropUpcoming()
        beginRadio(cur)
        requestRadioBatch(RADIO_INITIAL)
    }

    /** Leave radio mode; the queue stays as it is, it just stops growing. */
    fun stopRadio() = endRadio()

    private fun beginRadio(seed: Song) {
        radioGeneration++
        radioInFlight = false
        radioSeed = seed
        radioSeen.clear()
        _queue.forEach { radioSeen += it.id }
    }

    private fun endRadio() {
        if (radioSeed == null) return
        radioGeneration++
        radioInFlight = false
        radioSeed = null
        radioSeen.clear()
    }

    /** Remove everything after the current track — queue and engine — without touching
     *  what's playing. */
    private fun dropUpcoming() {
        val keep = currentIndex + 1
        if (keep >= _queue.size) return
        val player = engine
        if (player != null && !engineApplyOutstanding() && isQueueAllUri()) {
            val engineCur = currentIndex - engineBase
            val count = player.mediaItemCount
            if (engineCur >= 0 && engineCur + 1 < count) player.removeMediaItems(engineCur + 1, count)
        }
        while (_queue.size > keep) _queue.removeAt(_queue.lastIndex)
        baseOrder = _queue.toList()
        basePos = null
        reorderCount++
        engine?.repeatMode = engineRepeatMode()
    }

    private fun maybeRefillRadio() {
        if (radioSeed == null || radioInFlight) return
        if (_queue.size - 1 - currentIndex > RADIO_LOW_WATER) return
        requestRadioBatch(RADIO_REFILL)
    }

    private fun requestRadioBatch(count: Int, relaxed: Boolean = false) {
        val source = radioSource ?: return
        val seed = radioSeed ?: return
        radioInFlight = true
        val gen = radioGeneration
        val exclude: Set<String> = if (relaxed) recentPlayedIds(RADIO_RELAXED_HISTORY) else HashSet(radioSeen)
        source.request(radioSeeds(seed), exclude, _queue.lastOrNull(), count) { picks ->
            if (gen != radioGeneration || radioSeed == null) return@request
            radioInFlight = false
            val fresh = if (relaxed) picks else picks.filter { it.id !in radioSeen }
            if (fresh.isEmpty()) {
                if (!relaxed) requestRadioBatch(count, relaxed = true)
                return@request
            }
            fresh.forEach { radioSeen += it.id }
            addAllToQueue(fresh)
        }
    }

    /** Original seed at full weight plus the current track and the two before it, so the
     *  station drifts with what's been playing instead of looping on one artist. */
    private fun radioSeeds(seed: Song): List<WeightedSeed> {
        val out = mutableListOf(WeightedSeed(seed, 1.0))
        RADIO_DRIFT_WEIGHTS.forEachIndexed { back, w ->
            val s = _queue.getOrNull(currentIndex - back) ?: return@forEachIndexed
            if (s.id != seed.id) out += WeightedSeed(s, w)
        }
        return out
    }

    private fun recentPlayedIds(n: Int): Set<String> {
        if (currentIndex !in _queue.indices) return emptySet()
        val from = (currentIndex - n + 1).coerceAtLeast(0)
        return _queue.subList(from, currentIndex + 1).mapTo(HashSet()) { it.id }
    }
```

(c) Bulk append — replace the existing `fun addToQueue(song: Song) { ... }` with:

```kotlin
    fun addToQueue(song: Song) = addAllToQueue(listOf(song))

    /** Append [songs] in order. Starts playback when nothing was loaded; otherwise extends the
     *  engine queue in one call when it holds the whole (all-URI, unwindowed) queue. */
    fun addAllToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val wasEmpty = _queue.isEmpty()
        _queue.addAll(songs)
        reorderCount++
        if (baseOrder.isEmpty()) {
            baseOrder = _queue.toList() // adopt the queue (already holding songs) as base
            basePos = null
        } else {
            val firstNew = baseOrder.size
            baseOrder = baseOrder + songs
            basePos?.addAll(firstNew until baseOrder.size)
        }
        if (wasEmpty || currentIndex < 0 || currentSong == null) {
            val song = songs.first()
            currentIndex = _queue.size - songs.size
            clearLyricsOnSongChange(song)
            currentSong = song
            currentAlbum = albumLookup(song.albumId)
            timeSec = 0f
            isPlaying = true
            pendingSeekMs = 0L
            applyQueueToEngine(autoplay = true)
            return
        }
        // <keep the existing explanatory comments from addToQueue here>
        val player = engine
        if (player != null && !pendingEngineApply && !isWindowed && _queue.all { it.mediaUri != null }) {
            if (songs.size == 1) player.addMediaItem(mediaItemFor(songs[0]))
            else player.addMediaItems(songs.map { mediaItemFor(it) })
        }
        engine?.repeatMode = engineRepeatMode()
    }
```

(d) Radio-off hooks:
- `playFrom`: first line after `if (songs.isEmpty()) return` → `endRadio()`.
- `clearQueue`: first line → `endRadio()`.
- `adoptEngineSession`: after `if (songs.isEmpty()) return` → `endRadio()`.
- `load`: first line → `if (song.id != currentSong?.id) endRadio()`.

(e) Refill hooks: last line of `moveTo(...)` (after the if/else) → `maybeRefillRadio()`; last line of `syncFromEngine(...)` → `maybeRefillRadio()`.

(f) Persistence:
- `saveSignature()` → `"${currentSong?.id}|$currentIndex|$shuffle|$repeatMode|${_queue.size}|$reorderCount|${radioSeed?.id}"`
- `snapshot()` → add `radioSeedId = radioSeed?.id,` to the `Snapshot(...)`.
- `restore()` → after `pendingEngineApply = true` add:
  ```kotlin
        // A saved station keeps refilling; the first track change requests the next batch.
        snapshot.radioSeedId?.let { byId[it] }?.let { beginRadio(it) }
  ```

(g) Constants in `companion object`:
```kotlin
        internal const val RADIO_INITIAL = 24
        internal const val RADIO_REFILL = 20
        internal const val RADIO_LOW_WATER = 5
        internal const val RADIO_RELAXED_HISTORY = 20
        private val RADIO_DRIFT_WEIGHTS = doubleArrayOf(0.7, 0.4, 0.4)
```
(`RADIO_DRIFT_WEIGHTS.forEachIndexed` works on `DoubleArray`.)

(h) Wire the real source in `shared(context)` inside `PlayerState().also { state -> ... }` after `songResolver`:
```kotlin
                // Radio picks: score the whole library off main, answer on main (appScope is
                // Main-dispatched). No app scope (tests) → no picks.
                state.radioSource = RadioSource { seeds, exclude, previous, count, onResult ->
                    val app = appCtx as? DinkApplication
                    if (app == null) { onResult(emptyList()); return@RadioSource }
                    app.appScope.launch {
                        val picks = withContext(Dispatchers.Default) {
                            runCatching {
                                RadioPicker.pick(seeds, LibraryRepository.songsNow(appCtx), exclude, previous, count, kotlin.random.Random.Default)
                            }.onFailure {
                                if (it is CancellationException) throw it
                                android.util.Log.e("PlayerState", "radio pick failed", it)
                            }.getOrDefault(emptyList())
                        }
                        onResult(picks)
                    }
                }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.dink_smb_player.player.*'` → PASS (new + all existing PlayerState tests, which pin addToQueue/addMediaItem behaviour).
Then `./gradlew :app:testDebugUnitTest` → all PASS; `./gradlew :app:assembleDebug -q` → builds.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/dink_smb_player/player app/src/test/java/com/example/dink_smb_player/player
git commit -m "feat(player): endless radio mode with auto-refill and session persistence"
```

---

### Task 7: UI — Home shelf starts radio, Now Playing Radio button, queue eyebrow

**Files:**
- Modify: `app/src/main/java/com/example/dink_smb_player/ui/screens/home/HomeScreen.kt:383-405`
- Modify: `app/src/main/java/com/example/dink_smb_player/ui/screens/nowplaying/NowPlayingScreen.kt` (`V5TransportRow` ~line 377, queue panel header ~line 709)

**Interfaces:**
- Consumes: `PlayerState.startRadio`, `startRadioFromCurrent`, `stopRadio`, `radioSeed` (Task 6).

- [ ] **Step 1: Home shelf**

In the "Across your sources" `ShelfRow`:
- `eyebrow = "A spin through everything you've added"` → `eyebrow = "Pick one to start a station"`
- card `onClick` →
  ```kotlin
                            onClick = {
                                // A station built around this song, not just the 10 shelf picks.
                                player.startRadio(song)
                                onNavigate(ScreenId.NowPlaying)
                            },
  ```

- [ ] **Step 2: Now Playing Radio button**

In `V5TransportRow`, after the Repeat `V5TransportButton` and before the equalizer one:
```kotlin
        // Radio: off → turn what's playing into an endless similar-songs station;
        // on → stop growing the queue (it stays as it is).
        V5TransportButton(
            icon = Icons.Outlined.Radio,
            active = player.radioSeed != null,
            onClick = { if (player.radioSeed != null) player.stopRadio() else player.startRadioFromCurrent() },
        )
```
Import `androidx.compose.material.icons.outlined.Radio` (material-icons-extended is already a dependency).

- [ ] **Step 3: Queue eyebrow**

In the queue panel, the `Text(text = "UP NEXT", ...)` → `text = if (player.radioSeed != null) "UP NEXT · RADIO" else "UP NEXT",`. If `player` isn't in scope in that composable, read how `upcoming` is derived from `player` just above and use the same `player` parameter.

- [ ] **Step 4: Build + tests**

Run: `./gradlew :app:assembleDebug -q` → builds. `./gradlew :app:testDebugUnitTest` → all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/dink_smb_player/ui
git commit -m "feat(ui): shelf songs start radio; Radio button on Now Playing"
```

---

### Task 8: Device verification (controller, not a subagent)

- [ ] Check nothing is playing on the TV before installing (media_session state ≠ 3); ask the owner if it is.
- [ ] `bash tools/deploy.sh` (or the `/verify` skill flow) to build, install `com.dink.player`, relaunch.
- [ ] Home → "Across your sources" → click a card: Now Playing shows that song; queue panel "UP NEXT · RADIO", 24 tracks.
- [ ] Skip forward until ≤ 5 remain: queue grows by 20.
- [ ] Radio button: off → upcoming replaced, current keeps playing; on → button un-highlights, queue unchanged.
- [ ] Force-stop + relaunch: session restores with radio still on (eyebrow shows RADIO).
- [ ] `adb logcat -s LibraryRepository` ~60 s after launch: `genre backfill: N rows` then progress to `done`.
- [ ] Update memory (`project_phase_state` / new radio memory) and CHANGELOG as appropriate.
