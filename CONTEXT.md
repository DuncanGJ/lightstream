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
self-authenticating, server-transcoded [[Stream URL]] — exactly what the forthcoming
[[Playback API (forthcoming)]] (especially the centralized shape) needs. The Tool targets the
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

### Playback API (forthcoming)
Light's sanctioned audio surface, not yet shipped. Light has publicly committed to it as
their #1 priority (discussions #38, #70) but given no timeline. Two candidate shapes are
under evaluation, and they have opposite client implications:
- **Tool-owned foreground service** — the Tool decodes audio itself.
- **Centralized audio server** ("internal Chromecast") — the Tool hands LightOS a
  [[Stream URL]] / queue and LightOS is the audio engine. Under this shape the phone's
  Player role effectively moves into LightOS; the Tool becomes a browser + queue manager.
Either way, play/pause must surface on the LightOS home screen.

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
independent, behind the playback seam), not by the server or by LightOS. Its upcoming window
is editable **in place**: removing a Source-derived item adds it to a skip-set the cursor scans
past; moving one materializes the touched prefix into the overlay (insert semantics), leaving
everything beyond it lazy. The Queue is a **temporary playlist**: navigation — skip, previous,
starting a new [[Source]] — never drops songs; only explicit removal does.

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

### Metadata cache
Stale-while-revalidate persistence for browse metadata (artists, albums, tracks, playlists):
every list renders instantly from the last-seen copy while a background refresh updates it in
place — and only re-renders if something actually changed. Makes browsing seamless on slow
links and read-only-offline for anything seen before. Holds names and ids, not audio — audio
bytes live in the [[Rolling cache]].

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
and evictable, never guaranteed. Lives in the Tool's private storage; a future centralized
[[Playback API (forthcoming)]] would need the SDK file-share bridge for LightOS to read it.

## Standing constraint

Today the SDK sanctions **no audio-playback primitive** and grants **no
`FOREGROUND_SERVICE`**; `android.app.*`, `Service`, and `getSystemService()` are hard-blocked
by the build plugin. So reliable **background** playback is not achievable as an approved
Tool right now. `android.media.MediaPlayer(url)` compiles and plays foreground-only.

**But this is temporary and imminent-to-change** (see [[Playback API (forthcoming)]]).
Because its final shape is undecided — and the centralized shape would orphan any tool-owned
foreground-service work — the project **builds everything that is design-independent now**
(auth, browsing, resolving a [[Stream URL]], UI) behind a swappable playback seam, and defers
the audio last-mile to Light's API. Self-hacked background audio (plugin fork / sideload) is
treated as disposable, not the strategy. See the ADR.

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
