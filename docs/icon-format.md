# Icon editor – data format and reuse

The icon editor (`app/components/icon/EditorDialog.vue`, `<IconEditorDialog>`) builds an icon from a
background and one motif and hands back two things – it never saves by itself:

```ts
interface IconResult {
  png: string        // finished 128×128 PNG, base64 without the data: prefix
  source: IconSource // editable source, see below
}
```

## Where it is used

| Place | How |
| --- | --- |
| New instance | `CreateInstanceDialog` keeps the result and calls `save_instance_icon` after `create_instance` |
| Instance settings → General | `<IconInstanceEditor :instance>` loads `instance_icon_source`, saves with `save_instance_icon` |
| Share pack | `SharePackDialog` opens `<IconInstanceEditor>`; the instance image is written into the `.mrpack` root as `icon.png`/`.jpg`/`.webp` and becomes the instance image when the pack is installed (own exports, pack codes) |

## Instance storage

- `instances/<id>/icon-<8 hex>.png` – the image (same naming as before, so every existing view keeps working).
- `instances/<id>/icon-source.json` – the `IconSource` below (max. 256 KB). Setting an image any other way
  (file dialog, Modrinth pack icon, pack icon) or removing it deletes this file.

Both are validated again in Rust (`trs_core::icon_editor`): PNG magic bytes, square 16–512 px, decodable,
≤ 1 MB; the source must be a JSON object with `"v": 1`.

## `IconSource` (version 1)

Schema: `iconSourceSchema` in `app/utils/iconEditor.ts`.

```jsonc
{
  "v": 1,
  "bg": {
    "fill": "none" | "solid" | "gradient",
    "color": "#rrggbb", "color2": "#rrggbb", "angle": 0-359,
    "pattern": "none" | "wire" | "deepslate" | "dots", "patternColor": "#rrggbb",
    "border": false, "borderColor": "#rrggbb",
    "glow": true, "glowColor": "#rrggbb"
  },
  // one motif
  "layer": { "kind": "pixels", "size": 16 | 32, "palette": ["#rrggbb" | "#rrggbbaa", …], "data": "0001…" }
         | { "kind": "image", "png": "data:image/png;base64,…" }   // ≤ 128 px, ≤ 200 000 chars
         | { "kind": "none" },
  "scale": 0.4-1,                                                   // motif size relative to the tile
  "origin": { "kind": "pixel" | "mc" | "trs" | "upload", "ref": "item/diamond_sword" | "rocket" | null }
}
```

`data` holds two hex digits per pixel, row by row: `00` = transparent, `01` = `palette[0]`, …

## Minecraft textures

Never bundled. `mc_textures` reads `assets/minecraft/textures/{item,block}/*.png` (also the old `items/`,
`blocks/`) from a client jar the launcher already downloaded (`versions/<id>/<id>.jar`), keeps the first
animation frame at 16 or 32 px and caches them under `meta/mc-textures/<id>/`. Without a client jar the tab
asks the player to start an instance once.

## Adopting it for presets

Presets have no icon field yet. To add one:

1. Store `{ png: string /* data URL */, source: IconSource }` (or just the source and render on demand with
   `renderIconPng(source)` from `app/utils/iconRender.ts`) on the preset; keep it small – a pixel motif is
   ~2–4 KB, an image motif up to ~150 KB.
2. Open the editor: `<IconEditorDialog :initial="preset.icon?.source ?? null" @save="(r) => …" @close="…" />`.
3. Validate on the Rust side like `icon_editor::validate_composed` / `validate_source` before saving or syncing.
4. Show it with a plain `<img :src="'data:image/png;base64,' + png">` (rounded like `InstanceIcon`).
