// Umhang-Hilfen der Website (wie im Launcher, app/utils/trs.ts).

export interface SiteCape {
  id: string
  name: string
  unlock: 'free' | 'code' | 'admin'
  url: string
  scale: number
  frames: number
  frameTimeMs: number | null
}

/** Animierte Umhänge: senkrechter Streifen, Frame = floor(jetzt / frameTimeMs) % frames. */
export function trsFrameIndex(now: number, frames: number, frameTimeMs: number | null): number {
  if (frames <= 1 || !frameTimeMs || frameTimeMs <= 0) return 0
  return Math.floor(now / frameTimeMs) % frames
}

/**
 * Schlichte Schaufensterpuppe als Skin (64×64): Deepslate-grau mit Redstone-Hemd und glimmenden
 * Augen – damit der Umhang im Mittelpunkt steht und kein fremder Skin gezeigt wird.
 */
export function mannequinSkin(): HTMLCanvasElement {
  const c = document.createElement('canvas')
  c.width = 64
  c.height = 64
  const g = c.getContext('2d')!
  const box = (x: number, y: number, w: number, h: number, color: string) => {
    g.fillStyle = color
    g.fillRect(x, y, w, h)
  }
  // Kopf (Grundschicht) mit etwas dunklerer Ober- und Unterseite.
  box(0, 8, 32, 8, '#8b8ba2')
  box(8, 0, 16, 8, '#6f6f86')
  // Gesicht: zwei glimmende Augen.
  box(9, 12, 2, 1, '#ffb84d')
  box(13, 12, 2, 1, '#ffb84d')
  // Rechtes Bein, Körper, rechter Arm (Zeile 16–32).
  box(0, 16, 16, 16, '#4a4a5e')
  box(16, 16, 24, 16, '#b31a12')
  box(20, 16, 16, 4, '#8a140e')
  box(40, 16, 16, 16, '#8b8ba2')
  // Linkes Bein und linker Arm (Zeile 48–64).
  box(16, 48, 16, 16, '#4a4a5e')
  box(32, 48, 16, 16, '#8b8ba2')
  return c
}
