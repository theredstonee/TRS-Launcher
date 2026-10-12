import { CAPE_MAX_BYTES, CAPE_MAX_SCALE, fillElytraFromFront, rgba, type Rgba } from './capeImage'
import { upscaleTexture } from './capeUpscale'

// Fertiger Upload-Streifen aus der Zeitleiste: jedes Bild einmal hochskalieren
// (Standzeiten teilen sich das Ergebnis), Elytra aus der Außenseite erzeugen,
// untereinander legen, als PNG kodieren.
// Läuft Frame für Frame mit Pausen dazwischen, damit die Oberfläche flüssig bleibt;
// das PNG kodiert der Browser abseits des Haupt-Threads (OffscreenCanvas).

export interface ExportOptions {
  /** Faktor der Bearbeitung (alle Frames 64·base × 32·base). */
  base: number
  /** Faktor im Upload. */
  scale: number
  smooth: boolean
  onProgress?: (done: number, total: number) => void
  /** `true` = abbrechen (neuere Einstellungen, Dialog zu). */
  stale?: () => boolean
}

export class StaleExport extends Error {
  constructor() {
    super('stale')
  }
}

const pause = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

/** Standard-Auflösung: das größte Vielfache des Bearbeitungsfaktors bis 16 (pixelgenau). */
export function defaultOutputScale(base: number): number {
  const b = Math.max(1, Math.min(CAPE_MAX_SCALE, Math.round(base)))
  return Math.floor(CAPE_MAX_SCALE / b) * b
}

/** Wird bei diesem Faktor jeder Bearbeitungs-Pixel ein ganzer Block (pixelgenau)? */
export function isExactScale(base: number, scale: number): boolean {
  return scale >= base && scale % base === 0
}

/** Streifen für den Upload bauen (Frames in Reihenfolge, gleiche Bilder nur einmal skaliert). */
export async function buildUploadStrip(frames: readonly Rgba[], options: ExportOptions): Promise<Rgba> {
  const s = options.scale
  const fw = 64 * s
  const fh = 32 * s
  const out = rgba(fw, fh * frames.length)
  const cache = new Map<Rgba, Rgba>()
  const distinct = new Set(frames).size
  for (let i = 0; i < frames.length; i++) {
    const frame = frames[i]!
    let up = cache.get(frame)
    if (!up) {
      await pause()
      if (options.stale?.()) throw new StaleExport()
      // Elytra immer aus der Außenseite (gleiche Regel wie im TRS Client).
      up = fillElytraFromFront(upscaleTexture(frame, options.base, s, options.smooth), s)
      cache.set(frame, up)
      options.onProgress?.(cache.size, distinct)
    }
    out.data.set(up.data, i * fw * fh * 4)
  }
  return out
}

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const url = String(reader.result)
      resolve(url.slice(url.indexOf(',') + 1))
    }
    reader.onerror = () => reject(reader.error ?? new Error('read'))
    reader.readAsDataURL(blob)
  })
}

/** PNG als Base64 (ohne Präfix). */
export async function encodePng(img: Rgba): Promise<string> {
  const data = new ImageData(new Uint8ClampedArray(img.data), img.width, img.height)
  if (typeof OffscreenCanvas !== 'undefined') {
    const canvas = new OffscreenCanvas(img.width, img.height)
    const ctx = canvas.getContext('2d')
    if (ctx) {
      ctx.putImageData(data, 0, 0)
      return blobToBase64(await canvas.convertToBlob({ type: 'image/png' }))
    }
  }
  const canvas = document.createElement('canvas')
  canvas.width = img.width
  canvas.height = img.height
  canvas.getContext('2d')?.putImageData(data, 0, 0)
  const url = canvas.toDataURL('image/png')
  return url.slice(url.indexOf(',') + 1)
}

/** Bytes hinter einer Base64-Zeichenkette (ohne sie zu dekodieren). */
export function base64Bytes(b64: string): number {
  const pad = b64.endsWith('==') ? 2 : b64.endsWith('=') ? 1 : 0
  return Math.floor((b64.length * 3) / 4) - pad
}

export interface Reduction {
  kind: 'scale' | 'frames'
  /** Neuer Faktor bzw. neue Zahl der Frames. */
  value: number
  /** Geschätzte Größe danach. */
  bytes: number
}

/**
 * Vorschläge, wenn das PNG zu groß ist: kleinerer Faktor (Größe wächst mit etwa
 * Faktor^1,5 – pixelgenaue Blöcke packen gut) oder weniger Frames (linear).
 * Geschätzt mit 5 % Sicherheitsabstand; nach der Wahl wird neu gerechnet.
 */
export function suggestReductions(bytes: number, scale: number, frames: number, base: number, limit = CAPE_MAX_BYTES): Reduction[] {
  if (bytes <= limit) return []
  const budget = limit * 0.95
  const out: Reduction[] = []
  for (let s = scale - 1; s >= 1; s--) {
    // Pixelgenaue Faktoren zuerst (Vielfache des Bearbeitungsfaktors), sonst jeder kleinere.
    if (s > base && s % base !== 0) continue
    const estimate = Math.round(bytes * (s / scale) ** 1.5)
    if (estimate <= budget) {
      out.push({ kind: 'scale', value: s, bytes: estimate })
      break
    }
  }
  if (frames > 1) {
    const keep = Math.floor((frames * budget) / bytes)
    if (keep >= 1) out.push({ kind: 'frames', value: keep, bytes: Math.round((bytes * keep) / frames) })
  }
  return out
}
