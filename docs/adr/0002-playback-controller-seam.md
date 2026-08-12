# Playback: a PlaybackController seam, deferring the audio last-mile to LightOS

> **Superseded in part by [ADR-0003](0003-detached-audio-window-seam.md).** The bet paid off: Light
> shipped the tool-owned shape, so the seam's implementation swapped from the `MediaPlayer` shim to
> the SDK's detached player and the seam itself became window-shaped. The reasoning below is kept
> as the record of why everything above the seam was built design-independently.

The Light SDK sanctions no audio primitive today (no `FOREGROUND_SERVICE`; `android.app.*`,
`Service`, and `getSystemService()` are blocked by the build plugin), but Light has publicly
committed (discussions #38, #70) to a first-class audio API as their top priority — with two
candidate shapes still undecided: a **tool-owned foreground service**, or a **centralized
"internal Chromecast"** where the tool hands LightOS a URL/queue and LightOS plays it. We
therefore put **all** playback behind a `PlaybackController` seam, build every design-independent
layer against it (Subsonic client, browse UI, queue, cache), and ship a **disposable
`android.media.MediaPlayer` shim** for foreground sound today. When Light ships the API, only the
seam's implementation changes.

## Why not hack background audio now

The final shape is undecided, and the centralized option would orphan any tool-owned
foreground-service work — so a bespoke background-audio hack is likely throwaway.

## Considered and rejected (as the primary strategy)

- **Fork the light-sdk plugin** to permit `FOREGROUND_SERVICE` + `Service` + Media3 — builds only
  through a patched pipeline, so it is personal-sideload-only and never officially approvable;
  orphaned if Light picks the centralized design.
- **Sideload a full non-SDK Android app** (ExoPlayer + foreground service) — abandons the Light
  Tool premise (no Light UI, no approval) and means maintaining a parallel app.
- **ADB battery-whitelist / doze hacks** — don't beat cached/phantom-process kills; flaky for
  music.

## Consequences

Sound is **foreground-only** until Light ships the API; reliable background/lockscreen playback is
a known gap. The queue lives in the tool behind the seam; under a future centralized design,
home-screen transport while the tool is asleep would additionally need the queue handed to LightOS
and a file-share bridge (LightFileProvider) for cached bytes.
