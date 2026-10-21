// Gemeinsamer Rahmen aller Szenen: Bühne (16:9, 480×270 Kunst-Pixel), Kamera mit Parallaxe, Text, Chat-Blasen.
import type { BeatAt, WaitTimeline } from '#shared/wait-song'
import type { ChatEvent } from '../waitChat'
import type { Assets } from './assets'
import { clamp, easeOutBack, range, rgba, roundRect, type Cv } from './kit'
import type { Shot } from './plan'

export const STAGE_W = 480
export const STAGE_H = 270
export const FONT = '"Pixelify Sans Variable", "Pixelify Sans", "Segoe UI", system-ui, sans-serif'

export interface Cam {
  x: number
  y: number
  z: number
  r: number
}

export interface Frame {
  g: CanvasRenderingContext2D
  /** Liedzeit (s). */
  t: number
  tl: WaitTimeline
  shot: Shot
  beat: BeatAt
  /** Puls auf jedem Schlag 1→0. */
  pulse: number
  /** Puls auf jeder Eins 1→0. */
  bar: number
  /** Lautstärke-Hüllkurve aus dem Analyser 0…1 (0 ohne Analyser). */
  energy: number
  reduced: boolean
  assets: Assets
  chat: readonly ChatEvent[]
  name: string
  /** Gerätepixel je Kunst-Pixel (Zoom 1). */
  S: number
  /** Bildschirm in Gerätepixeln. */
  W: number
  H: number
  /** Mitte der Bühne in Gerätepixeln. */
  cx: number
  cy: number
  /** Zoom-Faktor des Formats (Hochformat < 1: mehr Bild sichtbar). */
  zk: number
  /** Wackeln in Kunst-Pixeln (Szenen addieren). */
  shake: number
  cam: Cam
  /** Cues (absolute Sekunden). */
  cue: { finalStop: number, finalHit: number, launch: number, fade: number, swell: number }
}

/** Kamera setzen (Kunst-Koordinaten im Bildmittelpunkt, Zoom, Drehung). */
export function setCam(f: Frame, x: number, y: number, z = 1, r = 0) {
  f.cam = { x, y, z, r }
}

/**
 * Transformation für eine Ebene: `depth` 1 = Fokus-Ebene, 0 = unendlich fern (bewegt sich nicht), > 1 = Vordergrund.
 */
export function at(f: Frame, depth = 1) {
  const { cam } = f
  const z = cam.z ** depth
  const s = f.S * z * f.zk
  const sh = f.reduced ? 0 : f.shake * f.S
  const ox = sh ? (Math.sin(f.t * 61.3) + Math.sin(f.t * 37.1) * 0.6) * sh * 0.6 : 0
  const oy = sh ? (Math.cos(f.t * 53.7) + Math.sin(f.t * 29.3) * 0.6) * sh * 0.6 : 0
  const px = STAGE_W / 2 + (cam.x - STAGE_W / 2) * depth
  const py = STAGE_H / 2 + (cam.y - STAGE_H / 2) * depth
  const c = Math.cos(cam.r * depth)
  const si = Math.sin(cam.r * depth)
  // Bildschirm = Mitte + R·s·(p − cam)
  f.g.setTransform(c * s, si * s, -si * s, c * s, f.cx + ox - (c * px - si * py) * s, f.cy + oy - (si * px + c * py) * s)
}

/** Sichtbarer Bereich (Kunst-Koordinaten) einer Ebene, großzügig (Drehung/Wackeln). */
export function visible(f: Frame, depth = 1) {
  const z = f.cam.z ** depth
  const s = f.S * z * f.zk
  const hw = (f.W / 2) / s * (1 + Math.abs(f.cam.r) * 1.2) + 8
  const hh = (f.H / 2) / s * (1 + Math.abs(f.cam.r) * 1.2) + 8
  const px = STAGE_W / 2 + (f.cam.x - STAGE_W / 2) * depth
  const py = STAGE_H / 2 + (f.cam.y - STAGE_H / 2) * depth
  return { x0: px - hw, y0: py - hh, x1: px + hw, y1: py + hh, w: hw * 2, h: hh * 2 }
}

/** Bildschirm-Koordinaten (Gerätepixel). */
export function screen(f: Frame) {
  f.g.setTransform(1, 0, 0, 1, 0, 0)
}

/** Bühnen-Koordinaten ohne Kamera (0…480 × 0…270, mittig). */
export function stage(f: Frame) {
  f.g.setTransform(f.S, 0, 0, f.S, f.cx - STAGE_W / 2 * f.S, f.cy - STAGE_H / 2 * f.S)
}

/** Bild pixelgenau (ohne Glättung) zeichnen. */
export function blit(g: CanvasRenderingContext2D, img: CanvasImageSource, x: number, y: number, w?: number, h?: number) {
  g.imageSmoothingEnabled = false
  if (w === undefined) g.drawImage(img, x, y)
  else g.drawImage(img, x, y, w, h!)
}

/** Bild weich (Fotos, Licht). */
export function soft(g: CanvasRenderingContext2D, img: CanvasImageSource, x: number, y: number, w: number, h: number) {
  g.imageSmoothingEnabled = true
  g.imageSmoothingQuality = 'high'
  g.drawImage(img, x, y, w, h)
  g.imageSmoothingEnabled = false
}

/** Foto als Hintergrund, deckend über den sichtbaren Bereich (cover) mit leichter Bewegung. */
export function photoCover(f: Frame, img: Cv | null, depth: number, drift = 0, scaleExtra = 1.15) {
  if (!img) return false
  at(f, depth)
  const v = visible(f, depth)
  const k = Math.max(v.w / img.width, v.h / img.height) * scaleExtra
  const w = img.width * k
  const h = img.height * k
  const cx = (v.x0 + v.x1) / 2 + drift
  const cy = (v.y0 + v.y1) / 2
  soft(f.g, img, cx - w / 2, cy - h / 2, w, h)
  return true
}

/** Kopf des Fans (Skin oder lila Ersatz) als 8×8-Leinwand. */
export function fanFace(f: Frame): Cv {
  return f.assets.face
}

/**
 * Chat-Blase in Kunst-Koordinaten (aktuelle Transformation). `pop` 0…1 = Einblenden (federnd), `alpha` = Deckkraft.
 * Gibt die Breite zurück.
 */
export function bubble(f: Frame, ev: Pick<ChatEvent, 'from' | 'text'>, x: number, y: number, opts: { pop?: number, alpha?: number, size?: number, align?: 'left' | 'right' | 'center', glowColor?: string } = {}): number {
  const g = f.g
  const pop = clamp(opts.pop ?? 1)
  const alpha = clamp(opts.alpha ?? 1)
  if (alpha <= 0.01 || pop <= 0.01) return 0
  const size = opts.size ?? 7
  const sc = easeOutBack(pop, 2.2)
  g.save()
  g.font = `600 ${size}px ${FONT}`
  const tw = Math.ceil(g.measureText(ev.text).width)
  const pad = size * 0.55
  const av = size * 1.5
  const w = tw + pad * 3 + av
  const h = av + pad * 1.2
  const ax = opts.align === 'right' ? x - w : opts.align === 'center' ? x - w / 2 : x
  g.translate(ax + w / 2, y + h / 2)
  g.scale(sc, sc)
  g.translate(-w / 2, -h / 2)
  g.globalAlpha = alpha
  const team = ev.from === 'team'
  // Glanz/Schatten
  g.fillStyle = 'rgba(0,0,0,0.35)'
  roundRect(g, 1, 2, w, h, h / 2)
  g.fill()
  g.fillStyle = team ? 'rgba(42,16,14,0.94)' : 'rgba(30,22,58,0.94)'
  roundRect(g, 0, 0, w, h, h / 2)
  g.fill()
  g.strokeStyle = team ? rgba('#ff5a4d', 0.75) : rgba('#b9a6ff', 0.7)
  g.lineWidth = Math.max(0.6, size * 0.09)
  g.stroke()
  // Kopf
  const hx = pad * 0.8
  const hy = (h - av) / 2
  g.imageSmoothingEnabled = false
  if (team) {
    g.fillStyle = '#b31a12'
    g.fillRect(hx, hy, av, av)
    g.fillStyle = '#ff8f85'
    g.fillRect(hx + av * 0.25, hy + av * 0.25, av * 0.5, av * 0.5)
    g.fillStyle = '#e0281e'
    g.fillRect(hx + av * 0.375, hy + av * 0.375, av * 0.25, av * 0.25)
  } else {
    g.drawImage(fanFace(f), hx, hy, av, av)
  }
  g.fillStyle = team ? '#ffd9d4' : '#efeaff'
  g.textBaseline = 'middle'
  g.fillText(ev.text, hx + av + pad, h / 2 + size * 0.05)
  g.restore()
  return w * sc
}

/** Pop-Fortschritt einer Nachricht (0 vor dem Erscheinen). */
export const popOf = (t: number, at: number, d = 0.35) => range(t, at, at + d)
