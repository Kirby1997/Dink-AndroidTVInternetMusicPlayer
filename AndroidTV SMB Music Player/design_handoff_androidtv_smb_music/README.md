# Handoff: Aether — SMB Music Player for Android TV

A high-fidelity design prototype for an Android TV music app that streams from SMB/CIFS shares and major cloud storage providers (Google Drive, Dropbox, OneDrive, iCloud, Box, S3, WebDAV, Jellyfin).

---

## 1. About This Bundle

**The files in `prototype/` are an HTML/React design reference, not production code.** They show the intended look, layout, motion, and behaviour of every screen at 1:1 fidelity. They are not meant to be embedded in a WebView or shipped — your task is to **rebuild them natively in the Android TV codebase**.

The recommended target stack is **Kotlin + Jetpack Compose for TV** (`androidx.tv.material3`, `androidx.tv.foundation`), which is what Google now ships as the first-class TV UI library and what most new Android TV apps use as of 2025–2026. Falling back to the older Leanback library is acceptable only if there's a hard reason (custom hardware quirks, etc.).

This handoff is structured for a Claude Code session in Android Studio: open the project, point Claude Code at this README, and ask it to scaffold the modules screen by screen.

### Fidelity

**Hi-fi.** Colours, type, spacing, radii, shadows, focus styling, motion timings and easing are all final. Pixel measurements in this README are stated in the design's reference resolution of **1920×1080** (16:9 TV canvas). Translate them 1:1 to dp at xhdpi (`1 dp ≈ 1 px` on most TVs), or scale proportionally if you target a different reference dpi.

---

## 2. What's in the Prototype

| File | Role |
|---|---|
| `prototype/index.html` | Entry point. Loads fonts, CSS, React, and the JSX files. |
| `prototype/styles.css` | Base tokens, side rail, mini player, app shell. |
| `prototype/screens.css` | Per-screen styles (Home, Now Playing, Songs, Shares, Cloud, Wizard). |
| `prototype/data.js` | Mock data — albums, songs, SMB shares, cloud providers, synced lyrics. |
| `prototype/components.jsx` | Shared React components — Icons, AlbumArt (procedural SVG), SideRail, MiniPlayer, TopBar. |
| `prototype/screens.jsx` | Every screen component, plus the Add-share wizard and the Cloud-connect modal. |
| `prototype/app.jsx` | Root component, routing state, player tick. |
| `prototype/tweaks-panel.jsx` | A design-time tweaks UI — **not part of the product**, ignore for implementation. |

Open `index.html` in a browser to see the live prototype. The side rail expands on hover; click items to navigate.

---

## 3. Product Surface

Aether is an **Android TV** music app — designed for a 10-foot UI with a D-pad remote, large focusable elements, and a thick focus ring. It pulls music from:

- **SMB / CIFS shares** (Samba, NAS boxes, Tailscale-routed hosts) — first-class.
- **Cloud storage** (Google Drive, Dropbox, OneDrive, iCloud, Box, S3, WebDAV, Jellyfin) — first-class.
- **Local device storage** — minor, treated as just another mount.

Library views show songs unified across all these sources, with the source path surfaced as metadata (`attic-nas/music`, `gdrive/…`).

### Screens implemented

1. **Home** — featured "resume playing" hero with full-bleed blurred album-art bg, plus shelves of recently played and recent syncs.
2. **Now Playing** — three-column layout: art + controls on the left, large lyric-forward synced lyrics in the center, play queue on the right. Mini player hides on this screen.
3. **Songs library** — magazine-style row list, sortable + filterable, with a prominent gradient **Shuffle All** button.
4. **SMB Shares** — stats strip + card grid of mounted shares, with status pills, signal bars, browse/resync/settings actions, and an Add-share placeholder card.
5. **Add SMB Share wizard** — 5-step modal: Discovery (scan vs manual), Address, Credentials, Mount, Verify (with a live connection-test log).
6. **Cloud Storage** — same layout language as SMB Shares but specialized for cloud providers, grouped by status (Connected / Needs attention / Available).
7. **Cloud Connect modal** — OAuth device-code flow with a 4-4 short code the user types on their phone.

### Screens stubbed in the side rail (not implemented yet)

Search, Albums, Artists, Playlists, Folders, Settings. These exist as navigation entries that flash a "coming soon" toast — your implementation should build them out with the same design language. The **Shuffle All** pattern from Songs should be **repeated** on Album detail, Artist detail, and Playlist detail when those are built.

---

## 4. Layout & Navigation Skeleton

### Side rail

- **Collapsed width:** 96 px (just icons)
- **Expanded width:** 320 px (icons + labels + badges)
- Expansion is triggered by **hover** on web; on TV use **focus** (D-pad right onto a rail item, or sustained focus on the rail container).
- Top section: Home, Search, Now Playing.
- "Library" group: Songs, Albums, Artists, Playlists, Folders.
- "Sources" group: SMB Shares, Cloud Storage.
- Bottom: Settings + a user/device chip (`Living Room · 192.168.1.18 · WI-FI`).
- Active item gets a vertical accent bar on the left edge and a filled gradient icon background.

### Top bar (per-screen)

- `crumbs` on the left: monospace, uppercased, e.g. `LIBRARY / SONGS`.
- A right-aligned clock + a "share connected" status pill: `• attic-nas connected   17:11`.

### Mini player (bottom, persistent)

- Fixed 96 px tall, full width below the side rail.
- Hidden when the Now Playing screen is active (which has its own transport controls).
- Layout: art (64×64) · title/artist · prev/play/next · scrubber · volume.

---

## 5. Design Tokens

### Colours (dark — default)

| Token | Hex | Use |
|---|---|---|
| `--bg-0` | `#06080d` | App background / canvas |
| `--bg-1` | `#0c0f17` | Cards, rail, mini player |
| `--bg-2` | `#141826` | Hover/elevated surfaces |
| `--bg-3` | `#1c2236` | Selected segments |
| `--line` | `rgba(255,255,255,0.08)` | Dividers, card borders |
| `--line-strong` | `rgba(255,255,255,0.14)` | Buttons, focused borders |
| `--ink-0` | `#f4f5f7` | Primary text |
| `--ink-1` | `#c8cad3` | Secondary text |
| `--ink-2` | `#8b8fa0` | Tertiary text / metadata |
| `--ink-3` | `#5a5d6c` | Disabled / muted |
| `--accent` | `#5b8dff` | Primary brand (blue) |
| `--accent-2` | `#9b6dff` | Secondary brand (violet) |
| `--accent-grad` | `linear-gradient(135deg, #5b8dff 0%, #9b6dff 100%)` | Primary CTAs, active states |
| `--good` | `#3ddc97` | Connected / success |
| `--warn` | `#f0a23a` | Syncing / expired |
| `--bad` | `#ff5577` | Offline / failed |

### Colours (light, for the toggle)

- `--bg-0: #f6f3ec`, `--bg-1: #ebe7dd`, `--bg-2: #e0dccf`, `--bg-3: #d2cdbd`
- `--line: rgba(0,0,0,0.08)`, `--line-strong: rgba(0,0,0,0.18)`
- `--ink-0: #16161a`, `--ink-1: #3a3a44`, `--ink-2: #6a6a78`, `--ink-3: #9b9aa6`
- Accent + status colours unchanged.

### Typography

- **Display (UI titles, button labels):** Sora, weights 400/500/600/700, tight letter-spacing (-0.015em to -0.025em).
- **Body / metadata:** Manrope, weights 400/500/600/700.
- **Editorial (song titles in Songs list, Now Playing track title, album-art labels):** Instrument Serif, regular + italic. **Use sparingly** — it's the editorial accent, not the workhorse.
- **Mono (timestamps, paths, bitrates, technical info, eyebrows):** JetBrains Mono, weights 400/500/600.

All four are Google Fonts. On Android, bundle them as TTF/OTF assets in `res/font/` and declare a Compose `FontFamily` for each.

Sizes you'll see across the design (in the 1920×1080 reference):

- Screen titles (`Songs`, `SMB Shares`): 56 px / -0.025em / weight 700 (Sora)
- Hero title: 84 px / 1.05 line-height / weight 700 (Instrument Serif)
- Now Playing track title: 56 px serif
- Songs row title: 30 px serif (compact density: 22 px)
- Album/share/cloud card titles: 19–22 px / weight 700 (Sora)
- Body / row sub-meta: 13–15 px Manrope
- Eyebrows / labels / paths: 10–12 px JetBrains Mono, letter-spacing 0.14em–0.20em, ALL CAPS

### Spacing & radii

- Page horizontal padding: **64 px** (outer gutter)
- Card padding: **20–28 px**
- Row gap inside lists: **6 px** compact / **12 px** roomy (a `--row-gap` token in CSS)
- Card border-radius: **14 px** (small), **16–18 px** (medium), **22 px** (modal / wizard panel)
- Tile/album-art radius: **10–14 px**
- Pill / button radius: **24 px** (height 48), or **18 px** for the big Shuffle button (height 64)
- Status pill: 12 px radius, height 24

### Shadows / focus

- Card hover lift: `transform: translateY(-2 to -4px)` + the focus ring.
- **Focus ring** (this is THE Android TV detail — make it strong):
  ```
  box-shadow:
    0 0 0 4px rgba(91,141,255,0.9),
    0 0 0 8px rgba(91,141,255,0.25),
    0 20px 60px rgba(91,141,255,0.3);
  ```
  In Compose, do the equivalent with `Modifier.border` + a glow drawn behind. Every focusable element gets this; don't skimp.
- Primary CTA shadow: `0 8px 24px rgba(91,141,255,0.35)` (gradient buttons), `0 12px 36px rgba(91,141,255,0.45)` (the big play button on Now Playing).

### Motion

- Side rail expand: 260 ms cubic-bezier(.2,.7,.2,1)
- Card hover lift: 220 ms cubic-bezier(.2,.7,.2,1)
- Generic button hover: 160 ms ease
- Lyrics line cross-fade: 400 ms ease (opacity + colour)
- Toast slide-in: 240 ms ease
- Wizard step content fade: 240 ms (the transition you'd build, not in the prototype)

---

## 6. Screen-by-Screen Spec

### 6.1 Home

**Hero** (top, 620 px tall):
- Background: the resumed track's album art, scaled 1.2×, `blur(60px)` `saturate(1.1)`, opacity 0.85, with a horizontal `linear-gradient(90deg, var(--bg-0) 0%, rgba(6,8,13,0.7) 40%, transparent 75%)` veil so the left content stays readable, plus a vertical bottom-fade into `--bg-0`.
- Content (`max-width: 1180px`, left-aligned at the 64 px outer gutter):
  - Eyebrow row: a `• Resume` good-pill + mono `FROM ATTIC-NAS · 24/96 FLAC`.
  - Mono album tag: `AMBIENT / 24-96  ·  2024`.
  - Big serif title: 84 px Instrument Serif, the track name.
  - Meta row: `Artist · Album · 4:02` with bullet-dot separators.
  - 3 buttons: primary gradient **Continue Playing** (with play icon), ghost **Add to Queue**, ghost **View Album**. All 56 px tall, 28 px horizontal padding.

**Shelves** (below the hero, vertical stack of horizontal rows):
- "Recently played" — 5–6 **SongCard**s wide, each 320×96 with a 72×72 album-art tile on the left.
- "New on your shares" — 6 **AlbumCard**s wide, each 240 px wide with a square album-art top and a 14×16 label area below; an overlay round play button slides in on focus.
- "Across your shares" — another row of SongCards.
- Each shelf has a head with title (30 px Sora 700) + mono sub ("ACROSS ALL YOUR SHARES · LAST 7 DAYS") and a right-aligned "View all >" pill.

The horizontal scrolling is implied by `overflow: hidden` on `.shelf-row`. **On TV, make these proper `TvLazyRow`s with focus traversal that auto-scrolls to keep the focused card centred.**

### 6.2 Now Playing

Three-column grid:

| Column | Width | Contains |
|---|---|---|
| Left | 480 px | Eyebrow pill, 360×360 album art (rounded 18 px, big drop shadow), mono `AMBIENT · 24-96 · 24/96 FLAC` tag, 56 px serif track title, 22 px artist, 15 px album+year, progress bar (6 px tall, gradient fill, 14 px circular handle), time row (`0:35` left, `−2:53` right), control row (shuffle, prev, big gradient play 72 px, next, repeat). |
| Centre | flex | Synced lyrics. Big Instrument Serif at **52 px** (NOT smaller — this is the lyric-forward emphasis). Past lines fade to `--ink-3`, future lines to `--ink-2`, current line is `--ink-0` weight 500. Auto-scrolls so the current line is vertically centred (smooth scroll). Top/bottom of the column have a fade-to-background gradient mask. |
| Right | 420 px | Up-Next queue. Header: "UP NEXT" mono eyebrow + "Play Queue" 28 px Sora title + "8 tracks · 39:11" mono count. List of queue items with: track number (or animated 4-bar EQ icon if this is the playing one) · 48×48 art · title + artist · duration. Currently-playing row has an `accent`-tinted background. |

Background: the album art again, scaled 1.2×, blurred 80 px, with a radial veil. Mini player **hidden**.

### 6.3 Songs

Top section (64 px outer padding):
- Title `Songs` 56 px Sora 700.
- Subtitle: `24 tracks across 2 connected shares. Combined library size: 16,507 files indexed.`
- Right-aligned `.songs-actions` column:
  - **Shuffle All** primary gradient button, 64 px tall, 18 px radius. Layout: 40×40 rounded-square icon container (`rgba(255,255,255,0.18)` bg) holding the shuffle SVG, then a two-line label — `Shuffle all` (17 px Sora 700) above `24 tracks · 1:42:12` (11 px mono, 0.08em letter-spacing, opacity 0.82). Picks a random track from the current filtered set and opens Now Playing.
  - **Sort segment**: `Most played · Title · Artist · Length` (4-up segmented control).
  - **Filter segment**: `All · FLAC · Lossy` (3-up).

Rows: A `flex column gap: var(--row-gap)` list. Each row is a 6-column grid:

```
56px (track #)  |  124px (art)  |  1fr (title + sub)  |  200px (source)  |  140px (stats)  |  48px (more)
```

- Row height: 124 px roomy, 92 px compact.
- Hover: background shifts to `--bg-2`, `translateX(4px)`. The row that's currently playing gets `border: 1px solid rgba(91,141,255,0.3)` + a left-to-right accent gradient background and the EQ-bars icon in the # column.
- Title: 30 px Instrument Serif (22 px in compact).
- Sub: `Artist · Album · 2019` with dot separators.
- Source column: `MP3 320` in `--accent` mono on line 1, `attic-nas/music` in `--ink-3` mono on line 2.
- Stats column: `88 plays` (small mono) and `3:31` duration on two lines, right-aligned.
- More button: 44 px round, hover-only background.

**The Shuffle All button pattern should also appear on Album detail, Artist detail, and Playlist detail screens when those are built** — same component, same styling, just bound to a different track set.

### 6.4 SMB Shares

- Header same shape as Songs (title + subtitle on the left, action buttons on the right: ghost **Rescan all**, primary gradient **+ Add Share**).
- Stats strip: 5 equal columns (`Connected`, `Total tracks`, `On disk`, `Protocol`, `Last full sync`). The first one uses the gradient-tinted variant. Each cell is a 14 px-radius card, padding 18×22, monospace 10 px uppercased label + 24 px Sora 700 value.
- 3-column grid of **ShareCard**s + an **AddShareCard** dashed placeholder. Card body:
  - Head row: 48 px rounded-square server icon (accent-tint bg) · share name (22 px Sora 700) + `//host/share` (12 px mono, ellipsized) · status pill (good / warn / bad).
  - Specs grid: 2-column, 6 rows — HOST / USER / PROTO / TRACKS / SIZE / SYNCED.
  - Signal row: mono "SIGNAL" label · 6 px progress bar with gradient fill · % value.
  - Action row: **Browse**, **Resync**, **`…` More** (icon-only).

### 6.5 Add SMB Share wizard

A full-screen overlay (rendered as a "screen" in the routing) with two columns:

| Left rail (420 px) | Right panel (flex) |
|---|---|
| `SMB / CIFS WIZARD` eyebrow, 42 px title `Mount a network share`, supporting copy. Below: ordered 5-step list, each step is a 14 px-radius row with a 32 px bullet (number or check) + label. Done steps: green accent. Active step: accent tint background. | A 22 px-radius card holding the current step content + a sticky footer (Back / "Step N of 5" mono / Continue). |

**Step 1 — Discovery.** Two 130 px-tall radio tiles: "Scan local network" vs "Enter manually". Selecting Scan reveals a panel that streams in mock discovered hosts (one row every 600 ms).

**Step 2 — Address.** `Host` + `Port` field row. `Share name` field with a `//host/` mono prefix slot. Protocol segment (Auto-negotiate / SMB3 only / SMB2 only). A green-tinted "Detected: SMB3 supported" banner with mono technical details (`negotiate.dialect = 3.1.1 · encryption = aes-128-gcm · signing = on`).

**Step 3 — Credentials.** 3 radio tiles: Username & password / Guest / Kerberos. The password form has Username + Domain (optional) inline, then Password full-width, then a checkbox "Save in Android keystore and reuse across reboots".

**Step 4 — Mount.** Display name field. Index-options checkboxes (audio files / artwork / tags / playlists / lyrics). Sync schedule segment (Auto / Hourly / Daily / Manual).

**Step 5 — Verify.** Summary card (MOUNT / AS / PROTOCOL / AUTH key-values). Runner card with a "Run test" button that streams a fake connection log:

```
[dns_lookup]     Resolving 192.168.1.42…                                OK 12ms
[tcp_connect]    Connecting tcp/445…                                    OK 24ms
[negotiate]      NEGOTIATE_PROTOCOL → SMB 3.1.1                          OK
[session_setup]  SESSION_SETUP / NTLMSSP                                  OK
[tree_connect]   TREE_CONNECT \\192.168.1.42\music                      OK
[enumerate]      Listing root directory…                  OK · 247 entries · 4 audio folders
```

Result banner at the bottom turns green on OK. Continue advances; on success the wizard closes and a toast appears.

### 6.6 Cloud Storage

Same overall shape as Shares (header + stats strip), but grouped into three sections:
- **Connected** — providers actively streaming.
- **Needs attention** — expired tokens, re-auth required.
- **Available providers** — sign-in entry points for the rest.

**CloudCard** (4-up grid, smaller than ShareCard):
- Top row: 52 px provider glyph (rounded 14 px) + status pill.
- Name + account (mono, ellipsized).
- If connected: 3-column mini-stat grid (TRACKS / CACHED / SYNC) inside a darker inset card.
- If idle/expired: 2-row AUTH / VIA mono summary.
- Action row: connected → Browse / Resync / `…`; expired → primary **Re-authenticate**; idle → primary **Connect**.

#### Provider glyphs — IMPORTANT

The prototype uses **abstract geometric marks** (triangle, diamond, hexagon, etc.) over a per-provider gradient. **It does not use real vendor logos.** This is deliberate — the design avoids any IP issue around redistributing third-party brand assets.

In your Android implementation, you have two options:
1. Keep the abstract marks (recommended for the design's "house" feel). They live in `screens.jsx` under `ProviderGlyph` as 100×100 SVGs with the per-provider palette.
2. Substitute each provider's official brand mark, but **only if you respect each vendor's brand guidelines** (e.g. Google has explicit rules for the Drive icon; Dropbox has its brand kit; etc.). Don't grab logos from a search engine.

Pick option (1) unless legal has signed off on (2).

### 6.7 Cloud Connect modal

Centred 640 px modal on a `rgba(0,0,0,0.7) backdrop-filter:blur(8px)` scrim. Three phases:

1. **Code:** "Sign into <Provider>" + explainer + a centred card showing a short URL (`aether.tv/code`, big mono) and an 8-character device code (`AGCG-NQGI`, 42 px gradient-text). Bottom: Cancel / "I've entered the code" primary.
2. **Waiting:** big EQ bars spinner, "Waiting for confirmation…".
3. **Success:** big green check (84 px), "Connected to <Provider>", auto-dismisses after 1.6 s with a toast.

This is the **OAuth 2.0 device-flow** pattern — exactly what real TV apps use because typing a password on a remote is awful. Your Android implementation should call into each provider's device-flow endpoint:

| Provider | Endpoint family | Notes |
|---|---|---|
| Google Drive | `https://oauth2.googleapis.com/device/code` | scopes: `https://www.googleapis.com/auth/drive.readonly` |
| Microsoft OneDrive | `https://login.microsoftonline.com/<tenant>/oauth2/v2.0/devicecode` | scopes: `Files.Read offline_access` |
| Dropbox | OAuth code flow (no device flow) — use PKCE + paired-device pattern | |
| Box | `https://account.box.com/api/oauth2/device` | |
| iCloud Drive | App-specific passwords (no OAuth) | enter once via paired device flow you build |
| WebDAV / S3 / Jellyfin | Basic / access-key / API-key respectively | direct form, no OAuth |

Implementation: poll the token endpoint on a 5 s interval after the user dismisses the "I've entered the code" step. Store tokens in **Android EncryptedSharedPreferences** or **DataStore with KeyStore-backed crypto**. Refresh on 401.

---

## 7. Mock Data Reference

The prototype ships fictional album / artist / song / share data. **Don't ship any of it.** Treat the data layer as schema documentation only.

### Schemas

```kotlin
data class Album(
  val id: String, val title: String, val artist: String, val year: Int,
  val palette: List<Color>,   // [c1, c2, c3] for procedural art
  val shape: AlbumArtShape,   // orbits | horizon | wave | grid | rings | diag | paper
  val tag: String,            // "AMBIENT / 24-96", "BOSSA NOVA", etc.
)

data class Song(
  val id: String, val title: String, val albumId: String,
  val durationSec: Int, val playCount: Int,
  val sourcePath: String,     // "attic-nas/music"
  val bitrate: String,        // "24/96 FLAC", "MP3 320"
)

data class SmbShare(
  val id: String, val name: String,
  val host: String, val port: Int, val shareName: String,
  val mountPath: String,      // "//attic-nas/music"
  val user: String, val protocol: SmbProtocol,
  val status: ConnectionStatus,  // Connected | Syncing | Offline
  val trackCount: Int, val sizeBytes: Long,
  val lastSyncRel: String,    // human "2 min ago" (compute on read)
  val signal: Float,          // 0..1
)

data class CloudProvider(
  val id: String, val name: String, val auth: AuthMethod,
  val status: ConnectionStatus,  // Connected | Expired | Idle
  val account: String,
  val trackCount: Int, val cacheSize: String, val lastSyncRel: String,
  val glyph: ProviderGlyph,   // triangle | diamond | cloud | …
)

data class LyricLine(val timeSec: Float, val text: String)
```

### Procedural album art

The prototype generates album art on the fly from a 3-colour palette + a shape kind, so we never need real artwork to look good. Reproduce this in Compose with a `Canvas { … }` per album that paints the gradient + the geometric overlay (orbits = concentric circles in a corner, horizon = sun-over-line, wave = stacked sine curves, grid = checker, rings = bullseye, diag = diagonal bars, paper = stacked text-lines). Fall back to this when ID3 artwork is missing.

---

## 8. State Model

A rough Compose `ViewModel` shape:

```kotlin
class PlayerViewModel : ViewModel() {
  val currentTrack: StateFlow<Song?>
  val playbackTimeSec: StateFlow<Int>
  val isPlaying: StateFlow<Boolean>
  val queue: StateFlow<List<Song>>
  val currentLyricIndex: StateFlow<Int>  // computed from playbackTime + lyrics

  fun play(songId: String, openNowPlaying: Boolean = false)
  fun togglePlayPause()
  fun next()
  fun prev()
  fun seek(sec: Int)
  fun jumpQueue(songId: String)
  fun shuffleAll(songs: List<Song>)  // pick random, play, open now-playing
}

class SourcesViewModel : ViewModel() {
  val shares: StateFlow<List<SmbShare>>
  val cloudProviders: StateFlow<List<CloudProvider>>
  fun rescanShare(id: String)
  fun mountShare(form: AddShareForm)
  fun connectProvider(provider: CloudProvider)   // kicks off device-flow
  fun reauthProvider(provider: CloudProvider)
}
```

The lyrics scroller is the only thing that needs to react every render — keep it as a `LaunchedEffect(currentLyricIndex) { animateScrollTo … }` on a `LazyListState`.

---

## 9. SMB Implementation Notes

Bake in a real SMB client. Options:

- **jcifs-ng** (`eu.agno3.jcifs:jcifs-ng`) — Java SMB2/3 client, mature, what most Android SMB apps use.
- **smbj** (`com.hierynomus:smbj`) — modern, Kotlin-friendly, SMB2/3, good docs.

`smbj` is the better starting point for a new codebase. The Verify step's log lines map cleanly to its API:

| Wizard log | smbj call |
|---|---|
| `dns_lookup` | `InetAddress.getByName(host)` |
| `tcp_connect` | `SMBClient().connect(host, port)` |
| `negotiate` | implicit in the above, log `connection.negotiatedProtocol.dialect` |
| `session_setup` | `connection.authenticate(AuthenticationContext(user, password.toCharArray(), domain))` |
| `tree_connect` | `session.connectShare(shareName)` as `DiskShare` |
| `enumerate` | `share.list("")` and count audio files |

Store credentials with `EncryptedSharedPreferences` (or DataStore + Tink). Never log them.

---

## 10. D-pad / Focus Rules

This is the part most "ported web designs" get wrong on TV. Spell it out:

- Every focusable element gets the focus ring (see §5).
- Side rail items: D-pad **up/down** moves between rail items; **right** moves into the screen content. From the topmost screen element, **left** returns to the rail.
- Shelves on Home: D-pad **left/right** scrolls within a shelf; **up/down** moves between shelves. The focused card stays centred — implement with `bringIntoViewRequester` or `TvLazyRow`'s default centring.
- Songs rows: **up/down** between rows; **right** into the `…` more-menu button.
- Cards (Shares / Cloud): focus the whole card first; **OK / Enter** opens it; D-pad inside the card focuses the action buttons one at a time.
- Wizard: D-pad **down** from a field goes to the next field; **down** from the last field goes to the footer Continue button.

In Compose for TV, lean on `Modifier.focusRequester` + `FocusRestorer` + `tvMaterial3` components, which already do most of this.

---

## 11. What Claude Code Should Do First

A suggested order so each step is independently testable:

1. **Scaffold the project** — Android Studio → New Project → "Android TV" → Compose. Add `androidx.tv:tv-foundation` and `androidx.tv:tv-material`. Bundle the four fonts.
2. **Tokens & theme** — drop the colours, type, radii, spacing from §5 into a `Theme.kt`. Build a `AetherTheme { … }` Composable. Add the light variant behind a `darkMode: Boolean`.
3. **Side rail + scaffold** — a `Row { Rail(); Box { CurrentScreen() } }`, with a `currentScreen: ScreenId` state. Wire up the icons; toast on unimplemented ones.
4. **Mini player** — a sticky composable at the bottom, hidden when `currentScreen == NowPlaying`.
5. **Home** — implement hero + 3 shelves. Use mock data first; wire real data later.
6. **Now Playing** — three-pane layout. Get the lyric scroller working with a synthetic timer before plugging in the real player.
7. **Songs** — magazine list. Add the Shuffle All button. **Same Shuffle All button pattern is then reused on Album / Artist / Playlist detail when those screens come online.**
8. **SMB Shares + Add wizard** — the card grid, then the 5-step wizard, then wire smbj.
9. **Cloud Storage + Connect modal** — the card grid grouped by status, then the device-flow modal, then wire Google Drive (most-used) first, then the rest.
10. **Search, Albums, Artists, Playlists, Folders** — using the same design language. Each gets a Shuffle All button per §3.

For audio playback itself, use **ExoPlayer / Media3** (`androidx.media3:media3-exoplayer`) — it handles MP3/FLAC/OGG/Opus and can read from a `MediaSource` that wraps an `smbj` `InputStream`, or from `https://` URLs for the cloud providers, or from `content://` for local files.

---

## 12. Open Decisions

These were not pinned down in the design and are worth asking the user / making explicit choices on early:

- **Caching policy for cloud files** — full pre-download vs stream-and-cache. The design implies stream-and-cache (the "12.4 GB / 64 GB" cache stat).
- **Gapless playback** — required for some genres (classical, post-rock). Media3 supports it; budget it in.
- **Casting / multi-room** — out of scope for v1 but the mini player has room for a cast pill.
- **Lyrics source** — `.lrc` sidecars vs external (LRCLIB)? The design assumes sidecars on the share/cloud.
- **Account multi-tenancy** — one user per Android TV box, or a profile switcher? The footer chip implies single-user.

---

## 13. Don't Ship

- The Tweaks panel (`tweaks-panel.jsx`).
- Any of the fictional album / artist / song / share names — replace with real data, or scrub.
- The web-only hover-to-expand on the rail; on TV it's focus-driven.
- The HTML/CSS/JSX files themselves. They're a spec, not a port target.

---

If anything in this document is ambiguous, open the matching screen in the prototype and inspect the source — every visible behaviour is in `screens.jsx` and every value is a CSS variable in `styles.css`/`screens.css`. The prototype is the source of truth.
