// Pixel-Sprites der Mondnacht (Entwurf `SPR`). Ein Zeichen = ein Pixel, `.` = leer.

export interface Sprite {
  pal: Record<string, string>
  m: string[]
}

export const SPRITES = {
  pumpkin: {
    pal: { o: '#8a3f16', O: '#d9772f', g: '#4d7a45', y: '#ffd27a' },
    m: ['............', '.....gg.....', '..ooOgOoo...', '.oOOOoOOOo..', 'oOyOOoOOyOo.', 'oOyyOoOyyOo.',
      'oOOOOoOOOOo.', 'oOyOyoyOyOo.', 'oOOyOyOyOOo.', '.oOOOoOOOo..', '..ooooooo...', '............'],
  },
  ghost: {
    pal: { w: '#eef1f8', s: '#b8bdcc', k: '#1a1426' },
    m: ['....wwww....', '..wwwwwwww..', '.wwwwwwwwww.', '.wwkkwwkkww.', '.wwkkwwkkww.', '.wwwwwwwwww.',
      '.wwwwkkwwww.', '.wwwwkkwwws.', '.wwwwwwwwws.', '.wwwwwwwwss.', '.ww.ww.ww.s.', '.w...w...w..'],
  },
  bat: {
    pal: { k: '#2a2240', v: '#b9a2ea' },
    m: ['............', '............', 'k....kk....k', 'kk..kkkk..kk', 'kkkkkvkvkkkk', '.kkkkkkkkkk.',
      '..kk.kk.kk..', '..k......k..', '............', '............', '............', '............'],
  },
  moon: {
    pal: { s: '#e6e9f2', d: '#a9afc2' },
    m: ['....ssss....', '..sssss.....', '.ssssd......', '.sssd.......', 'sssd........', 'sssd........',
      'sssd........', 'sssd........', '.sssd.......', '.ssssd......', '..sssss.....', '....ssss....'],
  },
  cauldron: {
    pal: { i: '#4a4560', h: '#6f6890', b: '#9fd6e6' },
    m: ['..b...b.....', '....b...b...', '.hhhhhhhhhh.', 'ibbbbbbbbbbi', '.iiiiiiiiii.', '.ihiiiiiiii.',
      'iihiiiiiiiii', 'iiiiiiiiiiii', '.iiiiiiiiii.', '..iiiiiiii..', '..i......i..', '............'],
  },
  grave: {
    pal: { c: '#7d7a8c', k: '#3b3851', g: '#2f4a35' },
    m: ['....cccc....', '..cccccccc..', '.cccckkcccc.', '.cckkkkkkcc.', '.cccckkcccc.', '.cccckkcccc.',
      '.cccccccccc.', '.cccccccccc.', '.cccccccccc.', 'gggggggggggg', 'gggggggggggg', '............'],
  },
  moonbat: {
    pal: { s: '#e6e9f2', k: '#b9a2ea' },
    m: ['...sss...', '..ss.....', '.ss......', '.ss.k...k', '.ss.kkkkk', '.ss..k.k.', '..ss.....', '...sss...'],
  },
  spider: {
    pal: { k: '#0e0b16', v: '#b9a2ea', l: '#3b3358' },
    m: ['l.kkk.l', '.lkkkl.', 'lkvkvkl', '.kkkkk.', 'l.kkk.l', '.l...l.'],
  },
  pk8: {
    pal: { o: '#8a3f16', O: '#d9772f', g: '#4d7a45', y: '#ffd27a' },
    m: ['....g...', '..oOOo..', '.oOyOyO.', '.oOOOOO.', '.oOyyyO.', '..oOOo..', '........', '........'],
  },
} satisfies Record<string, Sprite>

export type SpriteName = keyof typeof SPRITES

export function spriteSize(name: SpriteName): { w: number; h: number } {
  const s: Sprite = SPRITES[name]
  return { w: s.m[0]?.length ?? 0, h: s.m.length }
}

/** Farbe des Pixels (x, y) oder `null` – für Tests und zum Zeichnen. */
export function spritePixel(name: SpriteName, x: number, y: number): string | null {
  const s: Sprite = SPRITES[name]
  const ch = s.m[y]?.[x]
  return ch ? (s.pal[ch] ?? null) : null
}

/** Zeichnet den Sprite in Originalgröße auf das Canvas (vorher leeren). */
export function drawSprite(cv: HTMLCanvasElement, name: SpriteName): void {
  const g = cv.getContext('2d')
  if (!g) return
  const s: Sprite = SPRITES[name]
  g.clearRect(0, 0, cv.width, cv.height)
  s.m.forEach((row, y) => {
    for (let x = 0; x < row.length; x++) {
      const c = s.pal[row[x] as string]
      if (c) {
        g.fillStyle = c
        g.fillRect(x, y, 1, 1)
      }
    }
  })
}
