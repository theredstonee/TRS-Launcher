// Zuschnitt fürs Partner-Logo (§44.1): Geometrie in Bild-Pixeln, damit sie nicht von der Bühnengröße abhängt.
// `ox`/`oy` = Versatz der Bildmitte zur Rahmenmitte, `zoom` = 1 füllt den Rahmen (kürzere Seite), kleiner passt ein.

export type CropRotation = 0 | 90 | 180 | 270
export type CropBackground = 'none' | 'white' | 'dark'

export interface CropState {
  rot: CropRotation
  zoom: number
  ox: number
  oy: number
}

export const CROP_MAX_ZOOM = 8
export const CROP_MAX_BYTES = 20 * 1024 * 1024
/** Server: quadratisch 32–2048 px, ≤ 1 MB (§44.1). */
export const LOGO_UPLOAD_MAX_BYTES = 1024 * 1024
export const CROP_BACKGROUNDS = { white: '#ffffff', dark: '#14161b' }

/** Maße nach der Drehung. */
export function rotatedSize(w: number, h: number, rot: CropRotation): { w: number, h: number } {
  return rot === 90 || rot === 270 ? { w: h, h: w } : { w, h }
}

/** Kleinster Zoom: das ganze Bild passt in den Rahmen. */
export function minZoom(w: number, h: number): number {
  return Math.min(w, h) / Math.max(w, h)
}

/** Rahmenkante in Bild-Pixeln. */
export function frameSize(w: number, h: number, rot: CropRotation, zoom: number): number {
  const r = rotatedSize(w, h, rot)
  return Math.min(r.w, r.h) / zoom
}

const clampNum = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))

/**
 * Zoom und Versatz begrenzen: größer als der Rahmen → der Rahmen bleibt ganz im Bild; kleiner → das Bild bleibt ganz
 * im Rahmen. Je Achse |Versatz| ≤ |Rahmen − Bild| / 2.
 */
export function clampCrop(s: CropState, w: number, h: number): CropState {
  const zoom = clampNum(Number.isFinite(s.zoom) ? s.zoom : 1, minZoom(w, h), CROP_MAX_ZOOM)
  const r = rotatedSize(w, h, s.rot)
  const f = Math.min(r.w, r.h) / zoom
  const mx = Math.abs(f - r.w) / 2
  const my = Math.abs(f - r.h) / 2
  return { rot: s.rot, zoom, ox: clampNum(s.ox || 0, -mx, mx), oy: clampNum(s.oy || 0, -my, my) }
}

/** Startzustand: fast quadratische Bilder füllen den Kreis, breite Schriftzüge passen ganz hinein. */
export function initialCrop(w: number, h: number): CropState {
  const ratio = Math.max(w, h) / Math.min(w, h)
  return { rot: 0, zoom: ratio <= 1.25 ? 1 : minZoom(w, h), ox: 0, oy: 0 }
}

/** Verschieben um Bildschirm-Pixel (`scale` = Bildschirm-Pixel je Bild-Pixel). */
export function panBy(s: CropState, dx: number, dy: number, scale: number, w: number, h: number): CropState {
  return clampCrop({ ...s, ox: s.ox + dx / scale, oy: s.oy + dy / scale }, w, h)
}

/**
 * Zoomen um einen Punkt (Bildschirm-Pixel relativ zur Rahmenmitte); der Bildpunkt darunter bleibt stehen.
 * `frame` = Rahmenkante in Bildschirm-Pixeln.
 */
export function zoomAt(s: CropState, zoom: number, px: number, py: number, frame: number, w: number, h: number): CropState {
  const z = clampNum(zoom, minZoom(w, h), CROP_MAX_ZOOM)
  const before = frame / frameSize(w, h, s.rot, s.zoom)
  const after = frame / frameSize(w, h, s.rot, z)
  return clampCrop({ rot: s.rot, zoom: z, ox: px / after - px / before + s.ox, oy: py / after - py / before + s.oy }, w, h)
}

/** 90° im Uhrzeigersinn; der Versatz dreht mit. */
export function rotateCrop(s: CropState, w: number, h: number): CropState {
  const rot = ((s.rot + 90) % 360) as CropRotation
  return clampCrop({ rot, zoom: s.zoom, ox: -s.oy, oy: s.ox }, w, h)
}

type Ctx2D = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D

/**
 * Zuschnitt zeichnen: `size` = Kante des Rahmens im Ziel, `at` = linke obere Ecke des Rahmens (für die Bühne mit Rand).
 * Ohne Hintergrund bleibt Transparenz erhalten.
 */
export function drawCrop(c: Ctx2D, img: CanvasImageSource & { width: number, height: number }, s: CropState, size: number, at = 0): void {
  const f = frameSize(img.width, img.height, s.rot, s.zoom)
  const k = size / f
  c.save()
  c.translate(at + size / 2, at + size / 2)
  c.scale(k, k)
  c.translate(s.ox, s.oy)
  c.rotate((s.rot * Math.PI) / 180)
  c.imageSmoothingEnabled = true
  c.imageSmoothingQuality = 'high'
  c.drawImage(img, -img.width / 2, -img.height / 2)
  c.restore()
}

/** Dateityp an den ersten Bytes erkennen (PNG, JPEG, GIF, WebP). */
export function sniffImage(head: Uint8Array): 'png' | 'jpeg' | 'gif' | 'webp' | null {
  const b = (i: number) => head[i] ?? -1
  if (b(0) === 0x89 && b(1) === 0x50 && b(2) === 0x4e && b(3) === 0x47) return 'png'
  if (b(0) === 0xff && b(1) === 0xd8 && b(2) === 0xff) return 'jpeg'
  if (b(0) === 0x47 && b(1) === 0x49 && b(2) === 0x46 && b(3) === 0x38) return 'gif'
  if (b(0) === 0x52 && b(1) === 0x49 && b(2) === 0x46 && b(3) === 0x46 && b(8) === 0x57 && b(9) === 0x45 && b(10) === 0x42 && b(11) === 0x50) return 'webp'
  return null
}

/** Datei prüfen, bevor der Zuschnitt aufgeht → Fehler-Schlüssel oder `null`. */
export async function checkLogoFile(f: File): Promise<'type' | 'size' | null> {
  if (f.size > CROP_MAX_BYTES) return 'size'
  const head = new Uint8Array(await f.slice(0, 12).arrayBuffer())
  return sniffImage(head) ? null : 'type'
}

// Am Dateiende: der Auto-Import-Scanner übersieht sonst den Export nach einem Array-Literal.
export const LOGO_OUTPUT_SIZES: readonly number[] = [512, 384, 256]
