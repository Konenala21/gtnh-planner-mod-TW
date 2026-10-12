# Trailer

Footage comes from the game itself, the edit from a shot file, the music from code. `trailer.shot` is the trailer
(88 s, a teaser then a tutorial: a finished plan, two community plans built by the cursor and sped up, each followed by
a tilted pan over the finished plan, then the features one by one with typed captions, the minimap, the name);
`teaser.shot` is the earlier 31 s cut.

## Footage (dev game, GT loaded; gate input on `tools/dev/mc.sh idle`)

A solid board reads best on video: `call 'seethrough?percent=0'` first, and put it back after. Record with
`call 'record?start=<name>&fps=60&width=1920&threads=10&quality=0.95&cursor=0'` and `call 'record?stop=1'`: JPEG frames
in `run/client/recordings/<name>/` with `frames.csv` (each frame's time, the tour's pointer and step), so a shot can name
footage by tour step (`s20` its start, `w20` its note) and the camera can follow the pointer. For footage the edit will
zoom into or speed up, record at 4K (`call 'window?w=3840&h=2160&scale=4'`, the same 960x540 GUI) and 10 to 30 fps.

- **The tour**: `call 'tutorial?quiet=1'` (no notes, no dimming: the edit says it in its own words), then
  `call 'tutorial?start=1'` and `tutorial?next=1` as each step waits; `tutorial?quiet=0` after.
- **A plan built by the cursor** (the time-lapses): `call 'rebuild?file=<plan JSON, code or link>&seed=1'` rebuilds
  any finished plan as a player would (a lookup from a port per card, wires, drawers, rates, Arrange; `&nei=0` adds each
  card straight from its recipe and each generator straight onto the board, with no tooltips, instead of opening
  NEI's pages and the picker, which flash by in a time-lapse), resting,
  zooming out and panning like a person, the same take for the same seed. It plays a beat per card and stage, each
  waiting for next: drive it with a loop that calls `tutorial` and, when it says waiting, `tutorial?next=1`. Plans
  downloaded from the library in a dev run are in `run/client/library-downloads/`. A plan of 25 cards takes about four
  minutes, the 49-card Platline about twelve.
- **A plan's picture** (the hero): open it, `call planpicture` (the Share key's Screenshot, full detail).
- **The minimap in a real world**: `call 'newworld?name=plannh-trailer&seed=8675309'` twice, a few seconds apart (a
  normal-terrain world; plans are per world, so `importff` the plan there, open it once and close), then
  `call 'minimap?on=1&size=3&zoom=3&circle=0&centre=1'`, Shift+Y to hide the placing hints, and its arrow and
  bracket keys to pan and zoom it while recording. `minimap?centre=0` after.

## Music

`node tools/trailer/cafe.mjs build/trailer/cafe.wav` (the trailer's, the owner's pick): a nice café at 112 BPM, a bar
is 2.142857 s (4b): Rhodes on lush chords, a round bass, a soft kick, a brushed backbeat and a shaker, vibes now and
then, a little vinyl. Its `BREAKS` are bars where the bass and drums step out (under the tilted pans, a few in the
tutorial, before the end); the layout (intro, the band under the teaser, an easier half under the tutorial, the end
with a button a bar later for the end card's subtitle) is `kit.mjs`'s `layout`. Every track takes `--demo` (a
29 s piece to choose by) and `kit.mjs` is their shared studio. The others were the owner's demos: `funk.mjs`
(`--relaxed` lays it back in half time), `chip.mjs`, `synthwave.mjs`, `disco.mjs`; `jive.mjs` (a swing band:
"weird café jazz") and `track.mjs` ("corporate") were turned down.

## The cut

`java -Xmx10g tools/trailer/Trailer.java tools/trailer/trailer.shot` writes `build/trailer/trailer.mp4`; `--stills`
renders only the shot file's stills, `--every 1` a still every second, into `build/trailer/stills/`.

A shot file is shots on the bars, each a span of a recording or a still picture (straight, or remapped for speed
ramps and for skipping the tour's waits; times in beats may carry an offset, `72b+0.1`), with a camera (roll, yaw and pitch lean, zoom, framing or following the pointer; positive yaw and negative
pitch look up and to the left), a dissolve in, `shutter` for a time-lapse's motion blur, `caption`/`captiontop` lines
in the game's font (words between asterisks in the brand's cyan), `say`/`saytop` (typed a letter at a time,
`typespeed` letters a second, with a soft blip a letter mixed under the music) and `badge` (a speed), and the end card (the board's
canvas and dots, the name in the game's font, a `logo` when set). Vignette, bloom, depth of field and the grade default
to nearly off: keep them that way unless asked.
