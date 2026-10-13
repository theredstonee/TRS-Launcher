import { blit, resample, rgba, type Rgba } from './capeImage'
import { CAPE_REGIONS, scaleRect } from './capeLayout'
import { xbrzScale, type XbrzFactor } from './xbrz'

// Umhang-Textur von Faktor `from` (Bearbeitung) auf Faktor `to` (Upload) bringen:
// pixelgenau (nächster Nachbar) oder geglättet mit xBRZ. Geglättet wird jeder Teil
// des Umhangs für sich (Außen, Innen, Kanten), damit keine Farben über die
// Kanten der UV-Inseln laufen; Alpha bleibt sauber (kein Saum an durchsichtigen Rändern).

/** Ganzzahlig vergrößern, jeder Pixel wird ein k×k-Block. */
export function nearestScale(src: Rgba, k: number): Rgba {
  if (k === 1) return { width: src.width, height: src.height, data: src.data.slice() }
  const out = rgba(src.width * k, src.height * k)
  const row = new Uint32Array(out.width)
  const data = src.data.byteOffset % 4 ? src.data.slice() : src.data
  const src32 = new Uint32Array(data.buffer, data.byteOffset, src.width * src.height)
  const out32 = new Uint32Array(out.data.buffer, out.data.byteOffset, out.width * out.height)
  for (let y = 0; y < src.height; y++) {
    for (let x = 0; x < src.width; x++) row.fill(src32[y * src.width + x]!, x * k, x * k + k)
    for (let r = 0; r < k; r++) out32.set(row, (y * k + r) * out.width)
  }
  return out
}

/** Auf genau `w`×`h`: ganzzahlig per Block, sonst nächster Nachbar bzw. Flächenmittel beim Verkleinern. */
export function fitTo(src: Rgba, w: number, h: number): Rgba {
  if (src.width === w && src.height === h) return src
  const k = w / src.width
  if (Number.isInteger(k) && k >= 1 && h === src.height * k) return nearestScale(src, k)
  return resample(src, { x: 0, y: 0, w: src.width, h: src.height }, w, h)
}

/**
 * xBRZ-Stufe für den Faktor `f` (Ziel / Quelle): größte Stufe 4, 3 oder 2, die `f`
 * teilt; sonst die größte bis `f` (den Rest erledigt der nächste Nachbar). `null` = nicht glätten.
 */
export function xbrzStep(f: number): XbrzFactor | null {
  if (f < 2) return null
  if (Number.isInteger(f)) {
    for (const k of [4, 3, 2] as const) if (f % k === 0) return k
  }
  return Math.min(4, Math.floor(f)) as XbrzFactor
}

/** Hat das Bild nur ganz deckende oder ganz durchsichtige Pixel? */
export function binaryAlpha(img: Rgba): boolean {
  for (let i = 3; i < img.data.length; i += 4) {
    const a = img.data[i]!
    if (a !== 0 && a !== 255) return false
  }
  return true
}

/**
 * Alpha aufräumen: bei harter Quelle wieder hart (Schwelle 128); durchsichtige Pixel
 * bekommen die Farbe des pixelgenauen Bildes (für Darstellungen ohne Transparenz).
 */
export function cleanAlpha(smooth: Rgba, nearest: Rgba, hard: boolean): Rgba {
  const d = smooth.data
  const n = nearest.data
  for (let i = 0; i < d.length; i += 4) {
    let a = d[i + 3]!
    if (hard) a = a >= 128 ? 255 : 0
    if (a === 0) {
      d[i] = n[i]!
      d[i + 1] = n[i + 1]!
      d[i + 2] = n[i + 2]!
    }
    d[i + 3] = a
  }
  return smooth
}

/** Teil (x, y, w, h) aus `src` kopieren. */
function crop(src: Rgba, x: number, y: number, w: number, h: number): Rgba {
  const out = rgba(w, h)
  blit(src, x, y, w, h, out, 0, 0)
  return out
}

/** Einen Teil glätten: xBRZ, dann auf die Zielgröße, Alpha aufräumen. */
export function smoothPart(part: Rgba, f: number): Rgba {
  const w = Math.round(part.width * f)
  const h = Math.round(part.height * f)
  const nearest = fitTo(part, w, h)
  const step = xbrzStep(f)
  if (!step) return nearest
  const up = fitTo(xbrzScale(part, step), w, h)
  return cleanAlpha(up, nearest, binaryAlpha(part))
}

/** Ganze Textur (64·from × 32·from) auf Faktor `to` – pixelgenau oder geglättet. */
export function upscaleTexture(tex: Rgba, from: number, to: number, smooth: boolean): Rgba {
  const w = 64 * to
  const h = 32 * to
  if (from === to) return { width: tex.width, height: tex.height, data: tex.data.slice() }
  const base = fitTo(tex, w, h)
  const f = to / from
  if (!smooth || f < 2) return base === tex ? { ...base, data: base.data.slice() } : base
  for (const region of CAPE_REGIONS) {
    const src = scaleRect(region, from)
    const dst = scaleRect(region, to)
    const part = smoothPart(crop(tex, src.x, src.y, src.w, src.h), f)
    blit(part, 0, 0, Math.min(part.width, dst.w), Math.min(part.height, dst.h), base, dst.x, dst.y)
  }
  return base
}
