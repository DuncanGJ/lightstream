# Detached playback: hand the SDK player a window of the queue

The Light SDK shipped its audio API (light-sdk #148) in the **tool-owned** shape ADR-0002 listed
first: `LightAudio.newPlayer(playback = Detached)` gives the tool a media3 `MediaController` onto a
`MediaSessionService` running in the tool's own process, opted into with
`capabilities = ["detached-audio"]` in `lighttool.toml`. This closes the gap ADR-0002 deferred, so
the `MediaPlayer` shim is gone and `LightAudioPlaybackController` is the seam's implementation.

The seam changed shape with it: `PlaybackController.play` now takes a **window** — the current
track plus what follows it — instead of one track. The Player builds that window from the Queue
(`Queue.playbackWindow`), and the session walks it on its own.

## Why a window rather than one track at a time

Keeping the track-at-a-time seam would have been a smaller change: feed the session one item and
advance it from the tool on completion. But then the tool *is* the thing that starts each track,
and detached playback would stop at the end of the current song the moment the tool stopped
running — which is precisely the case detached audio exists to serve. Handing over a window makes
the session self-sufficient: the tool becomes a bookkeeper that follows playback
(`onAdvance` → the queue cursor steps forward, recording History and honouring Repeat ONE), not
its heartbeat.

It also puts the queue where the platform can see it. The session publishes the window and its
metadata, so Bluetooth, headset buttons and the LightOS now-playing surface get transport and
track info for free — the "play/pause must surface on the LightOS home screen" requirement in
CONTEXT.md.

The Queue stays the tool's own (CONTEXT.md: Queue), because it is lazy over a possibly
library-sized [[Source]], supports Play Next, in-place edits and History, and only the tool knows
whether a track resolves to a cache file or a Stream URL. The window is a projection of it, not a
handover of ownership.

## Consequences

- **Edits land at track boundaries.** The SDK player exposes `setMediaQueue`, which re-points
  playback and restarts the current item — there is no insert/append. So a queue edit (Play Next,
  remove, move, repeat-mode change) marks the pushed window stale and is handed over at the next
  advance, where the new track has just started and restarting it costs nothing. A Play Next added
  mid-song therefore takes effect for the *next* song, as its semantics require, without ever
  interrupting the current one.
- **Refills are rare and bounded.** `WINDOW_DEPTH` (50) tracks are pushed; a refill happens once
  `REFILL_THRESHOLD` (10) entries remain, and only when the queue actually has more to give — a
  short queue running out is the session correctly reaching its end.
- **The end of the queue is inferred.** The SDK reports position, duration, index, `isPlaying` and
  errors, but no end-of-queue event. The adapter derives `ENDED` from "last entry, not playing,
  parked at its duration"; `playbackStateOf` isolates that rule so it is testable off-device.
- **Repeat is realized in the window.** Repeat ONE fills the window with the current track and
  Repeat ALL wraps it, so both keep working with no tool process attached — the SDK exposes no
  repeat mode of its own.
- **Playback outlives the screen, not the server.** Releasing a detached handle would leave music
  playing with nothing controlling it, so `release()` stops first. The tool only releases when the
  whole Session is torn down (a credentials change), and that music belongs to the server just
  left.
- **The rolling cache is now a prefetch, not a gate.** Window entries resolve to a cache file or a
  Stream URL *at push time*; a track that finishes downloading afterwards still streams for that
  pass. Bytes are not wasted (the cache is populated by stream-and-save anyway), but the window
  is one more reason the prefetch depth matters.

## Transport controls and failures (added with light-sdk 0.1.2)

The SDK player's transport surface is wider than play/pause/next: `seekTo`, 15-second
`skipBack`/`skipForward`, and an observed, *classified* `LightAudioError` plus a connection
`availability`. The seam grew by exactly one method, `seekTo(positionMs)`; everything else is
derived on the tool's side so the seam stays small and the Player stays the one owner:

- **Nudges are a Player concern, not a seam one.** `Player.skipBack/skipForward` clamp 15 s against
  the position and duration the UI already shows and call `seekTo`. The SDK has its own nudges, but
  routing them through the seam would make the fake in tests encode SDK clamping rules.
- **Errors are typed, not boolean.** `PlaybackState.error` carries a `PlaybackErrorKind`
  (`SOURCE`, `UNSUPPORTED`, `OUTPUT`, `UNAVAILABLE`, `UNKNOWN`) mapped from the SDK's kinds. The UI
  phrases each as product copy that names what the on-screen buttons can do about it.
- **No auto-skip on failure.** The SDK deliberately stops rather than advancing (unplayable content
  would loop); the tool follows suit. Play on an errored track **retries** it: the window is
  re-pushed from the current cursor, which also re-resolves the source, so a track whose download
  finished in the meantime now plays from the cache.
- **A released handle is `UNAVAILABLE`, not idle.** The SDK releases the player itself when the
  detached controller never connects, and throws on any later command. The reducer maps
  `availability == Released` to an `ERROR/UNAVAILABLE` state ahead of everything else, and the
  adapter drops commands on a released handle instead of crashing the tool.
- **Scrubbing** uses the SDK UI's `LightTouchableProgressBar`; a drag maps the fraction onto the
  resolved duration and lands on zero while the duration is still unknown.

## Known gaps

- An external "previous" (headset, Bluetooth) re-syncs the cursor rather than walking the tool's
  History, which is the authority on what "previous" means. External *next* is followed normally.
- Detached playback lives in the tool's process. If Android kills the process, playback and queue
  go with it; restoring a queue across process death is not implemented.
