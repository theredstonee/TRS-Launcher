# Map v3 – creator preview

Map v3 is the new look and loading of the TRS Client minimap and world map. It ships first only as a
**preview build** (creators from the Partner tier up and the TRS team). Normal builds keep the current map
unchanged.

## What changes with map v3

- **Relief:** shading from the heights of all four neighbours (light from the top left), clearly stronger than
  before, height-based brightness, also correct across region borders (borders are re-shaded as soon as the
  neighbouring region is loaded or changes).
- **Water:** depth and seabed colour are stored per pixel and shaded when drawing – shallow water shows the seabed
  with its own relief, deep water gets darker over ~30 blocks instead of ~8.
- **Colours:** livelier biome tint for vanilla map colours (no more dull fixed multipliers); texture colours as before.
- **Sharpness:** the world map uses an image pyramid – one texel is never larger than one screen pixel
  (1 block = 1 texel from 1 px/block, 2 blocks per texel down to ½ px/block, 4 down to ¼, …). Region textures are
  drawn individually up to ~480 regions at once.
- **Loading:** scanning reaches the whole render distance (up to 32 chunks, before: 12), adaptive budget
  (1 ms per tick, 2 ms with a backlog or when moving fast, 3 ms with the world map open) plus a small per-frame budget
  while chunks are missing; regions kept in memory scale with the Java heap (256–1024); the disk cache limit is
  4× the setting (default 256 MB → 1 GB) and cave/indoor layers are trimmed before any surface map. Saved regions
  that are still loading show a subtle placeholder instead of looking unexplored.

## The switch

There is exactly one switch, the preview feature `map-v3`. It is read once at game start
(`core/map/MapPreview`):

1. System property `trsclient.preview` (comma list): `map-v3` turns it on, `-map-v3` turns it off.
   Overrides the build. Example: `-Dtrsclient.preview=map-v3`.
2. Otherwise `assets/trsclient/preview-features.txt` inside the mod jar. Only preview builds contain this file.

Default: off.

## Building the preview

Add `-PtrsPreview=map-v3` to the normal mod build, for example

```
cd client-mod
./gradlew collectLauncherJars -PtrsPreview=map-v3 -x test -x compileTestJava
```

Every loader tree applies `gradle/preview-features.gradle`, which writes
`assets/trsclient/preview-features.txt` (one feature per line) into each jar. Without the property the file is not
written. Check a jar with `unzip -p <jar> assets/trsclient/preview-features.txt` → `map-v3`.

Give the preview jars a distinct version (for example `mod_version=0.21.0-preview.1`) so they are never mixed up
with the release channel. Distribution to creators/team is not part of the mod.

## Map files and switching back and forth

- Map v3 stores its maps in `config/trsclient/maps-v3/` (file format 2: adds water depth/seabed planes and a 64×64
  summary). It never writes to or deletes from `config/trsclient/maps/`.
- Regions that exist only in the old `maps/` folder are read from there (read-only) and saved to `maps-v3/` once
  they change. Explored areas therefore carry over to the preview.
- Turning the preview off again uses `maps/` exactly as before; areas explored only with map v3 are not in it.
- The current code reads both file formats.

## Testing

- Unit tests: `./gradlew :common:test --tests "dev.theredstonee.trsclient.core.map.*"` (`MapV3Test`).
- In game: `-PtrsAutotest -PtrsAutotestOnly=maps -PtrsSys.trsclient.preview=map-v3` (adds `worldmap-half`).
- Image comparison: `-PtrsAutotestOnly=mapcompare` (fixed seed and spots, minimap and world map at several zooms,
  fast flight, cave; `-PtrsSys.trsclient.autotest.cmp.label=<name>`). On Fabric `runProdClient`,
  `-PtrsExtraMods=<folder>` adds other mod jars for local comparisons only.
