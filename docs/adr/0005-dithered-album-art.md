# Album art on Now Playing, as a dithered gen-1 iPod image

ADR-0001 and the CONTEXT.md **Browse** entry made the tool text-only: no album art anywhere. That
stands for Browse. **Now Playing** now shows the current album's cover, once, as a small
grayscale image dithered in the manner of a first-generation iPod — recognisable at arm's length,
unmistakably not a photo. Decided 2026-10-23 from contact sheets over ten covers from the user's
own library (`.scratch/dithered-album-art/`).

## The look

Luminance → autocontrast (1 % clip) → **toe 16** → **Atkinson** error diffusion onto **4 grays**,
scanned serpentine. Server-resized to a **240 px** square (`getCoverArt?size=240`), shown at an
exact 2× device-pixel multiple with nearest-neighbour sampling so every dot is a crisp 2×2 block.

Why each choice, from the sheets:

- **Atkinson over Floyd–Steinberg.** Atkinson drops a quarter of the error, which is what keeps
  edges and cover text crisp (the classic-Mac/iPod look). F–S is more tonal and grainier.
- **4 grays over 1-bit.** Pure 1-bit Atkinson is the most "iPod" but destroys dark covers (SOPHIE,
  Armand Hammer, Merzbow went to black). 4 levels keep them recognisable while still reading as a
  dither; 3 was a close second.
- **Toe 16, no gamma.** Error diffusion turns a near-black radial gradient into a ring wherever it
  crosses a palette threshold. Clipping *during* diffusion cannot fix it: the carried error re-lights
  the band a row later. A black-point curve *before* diffusion does. Stepping toe 12→16 one unit at a
  time, 15 is the first value that cleared the worst cover; 16 is that plus one. Nothing visible
  changes on light covers up to 24, so there is no cost.
- **240 px.** 160 is iPod-tiny; 320 goes nearly photographic. 240 at 4 levels keeps the dither
  visible.

## Where it runs

`art/Dither.kt` is pure Kotlin over an `IntArray` and is unit-tested (toe → black, palette only,
tone preserved, hard edges exact, deterministic). `art/AlbumArt.kt` fetches each cover id once,
dithers once, and stores the PNG under `files/coverart/`; a cover seen once works offline, and it
is cleared with the metadata cache. The only Android code is `AndroidArtCodec` (bitmap decode /
PNG encode), behind an interface so the cache is tested with a fake. Nothing new on the dependency
allowlist.

## Consequences

- **Browse stays text-only.** The glossary's "no album art" is now scoped to rows; Now Playing is
  the exception.
- **Now Playing is one centered column:** art, title, artist, album, scrubber + times, transport.
  Stop dims the art and title; the art stays, because the track is still current.
- **No art on LightOS / car / lock-screen surfaces.** `LightMediaMetadata` has no artwork field;
  if the SDK adds one, the same stored PNG is the obvious payload.
- **Taste knobs are constants, not Settings:** `DITHER_TOE`, `DITHER_LEVELS`, `ART_SIZE_PX`,
  `ART_DISPLAY_SCALE`. The contact-sheet script in `.scratch/dithered-album-art/` is the way to
  revisit them.
