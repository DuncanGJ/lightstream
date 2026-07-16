# Backend: Subsonic API via Navidrome, a read-only sibling of the beets library

We stream the user's beets-managed library to the Light Phone tool over the **Subsonic /
OpenSubsonic API**, served by **Navidrome** running as an additional read-only consumer of the
same beets music folder (alongside Lyrion, which stays for in-home Squeezebox playback). We
target the *API*, not the server, and add no custom server-side code.

## Why

The forthcoming LightOS audio API may be "centralized" — LightOS fetches a URL and plays it,
adding no auth or headers of its own (see ADR-0002). Subsonic's `stream` endpoint returns a
single, stateless, self-authenticating (`u`/`t`/`s` query params), server-transcoded URL —
exactly that contract. It is also fully open source (Light approval requires it) and reachable
through a dumb reverse proxy. beets already fans out to multiple read-only consumers, so
Navidrome is just one more.

## Considered and rejected

- **Expose Lyrion (LMS) off-LAN directly** — its HTTP is player-centric; transcoding a single
  arbitrary track through a stateless URL fights its design, and its built-in auth is too weak
  to face the internet.
- **Plex / reskin Plexamp** — proprietary and requires a Plex account; can't be a Light Tool
  (closed source, fails the dependency allowlist and the approval gate); Plexamp's own player
  engine is irrelevant once LightOS owns playback.
- **Build a custom streaming service** — reinvents Navidrome; over-builds.

## Consequences

Two music servers run against one library (Lyrion in-home, Navidrome for the phone) — deliberate,
not redundant. The concrete server is swappable (Gonic, Jellyfin) without touching the tool.
beets remains the sole writer; Navidrome is strictly read-only.
