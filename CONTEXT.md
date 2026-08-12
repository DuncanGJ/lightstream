# Context: Personal Music Streaming Tool

A Light Phone III **Tool** (SDK app) that streams a user's own beets-managed music library
from a self-hosted server (over the Subsonic API) and plays it on the phone.

This is a glossary, not a spec. Terms below are the canonical vocabulary for the
project. Implementation decisions live in `docs/adr/`.

## Glossary

### Tool
An app built with `light-sdk` in the `tool/` module. Compiles against `:sdk:client`,
constrained by the plugin allowlist (deps, permissions, blocked Android APIs). Distinct
from a plain sideloaded Android app, which is *not* a Tool.

### beets library (canonical source)
The user's music library is managed by **beets** — beets owns tagging/organization and is the
sole writer of the on-disk files. Everything else is a read-only *consumer* of that folder.
This fan-out already exists in the user's setup (Lyrion, podkit, …); the Tool's backend is
simply one more consumer of the same folder.

### Lyrion (in-home)
Lyrion Media Server (aka Logitech / "LMS") consumes the [[beets library (canonical source)]]
for **in-home** streaming to Squeezebox-style players. It stays as-is and is **not** the
Tool's backend — its player-centric HTTP fights the stateless [[Stream URL]] contract. In this
project's language, "LMS" means Lyrion-the-in-home-consumer, never the Tool's backend.

### Subsonic API (integration surface)
The Tool integrates with the library over the **Subsonic / OpenSubsonic API** — the de-facto
open standard for self-hosted music. Its `stream` endpoint natively yields a stateless,
self-authenticating, server-transcoded [[Stream URL]] — exactly what the
[[Playback API (shipped: detached audio)]] needs, since every [[Playback window]] entry is one of
these URLs handed straight to the media session. The Tool targets the
*API*, not any one server, so the concrete server can change without touching the Tool. That
server is added as another read-only consumer of the [[beets library (canonical source)]].
Concrete server choice lives in the ADR.

### Player
The role that decodes audio and drives the speaker/headphones. **Resolved: the Light
Phone III is the Player** — audio comes out of the phone itself, for listening on the go.
(Contrast with the Controller role, which the phone does *not* take here.)

### Controller
The role that issues transport commands (play/pause/skip) to a *separate* Player device.
Considered and rejected as the primary model: it would forbid on-the-go listening.

### Audio Sink
Where sound physically emerges. **Resolved: the phone's own output** (speaker/headphones).

### Stream URL
A plain HTTP(S) URL that resolves a library item to playable audio bytes (ideally
transcodable to a codec the device decodes, e.g. MP3/AAC), with any auth embeddable in the
URL. **The design-independent contract**: whoever ends up being the audio engine (the Tool,
or LightOS under the centralized model) needs one of these. Producing it from the Media
Server is the durable core of the project. The Media Server that yields it most simply and
openly is a primary selection criterion.

### Playback API (shipped: detached audio)
Light's sanctioned audio surface, **shipped** in the **tool-owned** shape (light-sdk #148): the
Tool decodes audio itself, in a `MediaSessionService` the SDK owns and the Gradle plugin declares
in the Tool's manifest. The Tool opts in with `capabilities = ["detached-audio"]` and asks for a
`LightAudioPlayer` in `Detached` mode. The rejected alternative — a centralized "internal
Chromecast" where LightOS is the audio engine — is no longer a live concern for this project.

"Detached" is an *ownership* word, not a visibility one: playback belongs to the session rather
than to the screen that started it, so releasing the Tool's handle leaves music playing. The
[[Playback window]] is what makes that self-sufficient. Play/pause reaches the LightOS home screen
because the session is a platform `MediaSession`, which LightOS discovers on its own.

### Playback window
The slice of the [[Queue]] handed to the detached player: the current track plus what follows it,
resolved to cache files or [[Stream URL]]s and carrying the metadata the phone's now-playing
surfaces display. The session plays through it without the Tool, and the Tool follows along —
its cursor steps forward as the session advances. Repeat lives *inside* the window (Repeat One
fills it with one track, Repeat All wraps it), because the session has no repeat mode of its own.
Queue edits reach the player at the next track boundary, never mid-song. See ADR-0003.

### Browse
The navigation model: **iPod-style** hub-and-stack. A drill-down menu hierarchy (Music →
Artists → artist → album → song), ascend with back. Maps onto the SDK's single push/pop
screen stack (no tabs, no global chrome). Text-only rows — **no album art**. The only concept
borrowed from Spotify is the [[Queue]].

### Search
An in-context filter available *at each* [[Browse]] level — type a few characters to narrow the
current list (artists, albums, or songs) rather than scrolling. Counters the slow-typing +
giant-type friction of long lists. Bounded, already-loaded lists filter client-side and
instantly; large lists query the server via Subsonic `search3`. The flat "Songs" level is backed
by a **paged empty-query `search3`** (Navidrome returns the whole library paginated), realized
lazily as a sliding-window [[Source]].

### Now Playing
The tool's home base — the current track plus transport controls. Shown first when the tool
opens if something is playing. A dedicated [[Browse]] screen (not a modal), reachable from
anywhere via a small shared bottom-bar affordance.

### Source
The ordered list a play action was launched from: an album's tracks, an artist's songs, "all
songs A→Z", a [[Playlist]]. The [[Queue]] is a **cursor over a Source** — playing item *i*
means current = `Source[i]` and up-next is `Source[i+1], Source[i+2], …`. Realized as a
**sliding window** around the cursor, never materialized in full (a Source can be the entire
library). This is the implicit playback context; there is no separate Spotify-style "context"
object.

### Queue
A **cursor over a [[Source]]** plus a small **Play-Next overlay**. Next-track drains the
overlay first, then advances the Source cursor. The Queue is owned by the Tool (design-
independent, behind the playback seam), not by the server or by LightOS — and within the Tool
it has **exactly one owner, the Player**: the UI never touches the Queue directly, it renders
the Player's observable upcoming window and calls Player methods, which also re-aim the
[[Rolling cache]] prefetch after every edit. Its upcoming window is editable **in place**:
removing a Source-derived item adds it to a skip-set the cursor scans past; moving one
materializes the touched prefix into the overlay (insert semantics), leaving everything beyond
it lazy. The Queue is a **temporary playlist**: navigation — skip, previous, starting a new
[[Source]] — never drops songs; only explicit removal does. Repeat One repeats on *natural
completion only* — the next button always advances.

### History
The capped stack of previously-played tracks, each stored with its full [[Queue]] context
(source + cursor). "Previous" pops History — returning to the last track *actually played*,
not the album neighbour — and restores that context so "next" resumes from there. History is
what **did** play; the [[Queue]] is what **will**.

### Play Next
The single manual queue operation: insert one track into the [[Queue]] overlay to play
immediately after the current track. After it plays, the [[Source]] resumes. (No separate
"add to end of queue".) With nothing playing, the track goes to the front of the queue and
playback starts immediately.

### Playlist
A named, ordered, server-side list of tracks from the [[Subsonic API (integration surface)]]
(`getPlaylists` / `getPlaylist`). Can serve as a [[Source]]. Read-only from the Tool in v1.

### Library
The one seam for library data: every screen reads artists/albums/tracks/playlists/search
through the Library module, never through the Subsonic client directly, so "is this cached /
does it work offline?" is answered in one place. Three policies live behind it: browse lists
are [[Metadata cache]] stale-while-revalidate flows; decisions and pages that must not act on
stale data (the single-album tap-through, Songs pages) are **network-first with cache
fallback**; [[Search]] is live and deliberately uncached (results are query-shaped).

### Metadata cache
Stale-while-revalidate persistence for browse metadata (artists, albums, tracks, playlists):
every list renders instantly from the last-seen copy while a background refresh updates it in
place — and only re-renders if something actually changed. Makes browsing seamless on slow
links and read-only-offline for anything seen before. Holds names and ids, not audio — audio
bytes live in the [[Rolling cache]]. Accessed only through the [[Library]].

### Session
One configured connection to the server and everything wired to it: Subsonic client,
[[Library]], Player, [[Rolling cache]]. Rebuilt **as a unit** whenever credentials change.
Screens beyond Home capture it at construction — safe because Settings (the only place a
Session is rebuilt) is reachable only from Home, so no browse screen outlives a Session swap.
Home is the single readiness gate ("is there a Session yet?"); no other screen null-checks.

### Rolling cache
A bounded, self-evicting on-device store of transcoded audio — recently-played plus
prefetched-ahead — mirroring the [[Queue]]'s sliding window. Populated by *stream-and-save*
during playback (free — those bytes were streamed anyway, retained generously) plus a
*rolling prefetch* of ~15 tracks ahead in the [[Source]], fetched **one at a time as the cursor
advances** (never bursted at play-start) so it never slams a big download and stops pulling the
moment you skip past or stop. No Wi-Fi/cellular branching in v1 — bounded depth + lazy fetch
keep data in check. The over-cache bias lives in **retention** as much as prefetch. LRU
eviction under a ~500 MB budget. Deeper dead-zone resilience (cache a whole album before going
offline) is the future **pinned-download** feature — **not** this cache, which is opportunistic
and evictable, never guaranteed. Lives in the Tool's private storage, which the SDK's audio
service reads directly — it runs in the Tool's own process, so no file-share bridge is needed.
A cache hit is decided when a track enters the [[Playback window]]: one that lands later still
streams for that pass.

## Standing constraint (lifted)

The SDK used to sanction no audio-playback primitive and no `FOREGROUND_SERVICE`, so background
playback was unreachable for an approved Tool and the project shipped a foreground-only
`android.media.MediaPlayer` shim behind a swappable seam. That bet is settled: the
[[Playback API (shipped: detached audio)]] arrived in the tool-owned shape, the plugin grants the
foreground-service permissions to Tools that declare the capability, and the shim is gone.

What survives is the seam and everything built design-independently above it (auth, browsing,
resolving a [[Stream URL]], [[Queue]], UI) — the swap cost one adapter and a
[[Playback window]]-shaped interface. `android.app.*`, `Service` and `getSystemService()` remain
blocked; the Tool never touches them, the SDK's service does.

## Flagged ambiguities

- **"LMS"** — overloaded. Resolved: in this project it means **Lyrion Media Server**
  (Logitech / Squeezebox), an *in-home* consumer of the [[beets library (canonical source)]] —
  **not** the tool's backend, and **not** epoupon's "Lightweight Music Server." The tool's
  backend is Navidrome via the [[Subsonic API (integration surface)]].
- **"Queue"** — Spotify bundles several concepts under this word (context, up-next, add-to-queue
  vs play-next, shuffle, repeat). Resolved to a minimal model: a cursor over a [[Source]] plus a
  [[Play Next]] overlay. No separate "playback context" object; the Source *is* the context.
- **"streaming"** — the user's phrasing "stream music" means the phone is the [[Player]] (audio
  out of the phone, on the go), not a [[Controller]] casting to a home speaker.
