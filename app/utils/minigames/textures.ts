// Texturen der Minispiele: kommen zur Laufzeit aus einer Minecraft-Jar dieses Rechners, nie aus dem Repo.

import { backend } from '~/utils/backend'

export type TextureMap = Record<string, HTMLImageElement>

/** Unterschiede je Launcher-Design. Redstone ist der volle Auftritt, die anderen sind ein Anstrich derselben Maschine. */
export interface GameSkin {
  id: 'redstone' | 'winter' | 'fox'
  wall: { name: string, tint: string }
  frame: string
  well: { name: string, tint: string }
  wireOff: [number, number, number]
  wireOn: [number, number, number]
  ghost: { fill: string, stroke: string }
  glowTint: string
  backdrop: string
  /** Erste Gegend im Loren-Rausch; die übrigen bleiben Tiefenschiefer und Nether. */
  zone: { name: string, floor: string, wall: string, ores: string[], wood: string, roof: string, amb: [number, number, number], torch: [number, number, number] } | null
}

const REDSTONE: GameSkin = {
  id: 'redstone',
  wall: { name: 'polished_blackstone_bricks', tint: 'rgba(0,0,0,.46)' },
  frame: 'deepslate_bricks',
  well: { name: 'deepslate_tiles', tint: 'rgba(4,3,3,.8)' },
  wireOff: [0.42, 0.06, 0.05],
  wireOn: [1.1, 0.2, 0.1],
  ghost: { fill: 'rgba(255,70,50,.17)', stroke: 'rgba(255,90,70,.72)' },
  glowTint: 'rgba(255,190,130,',
  backdrop: 'radial-gradient(ellipse at 50% 35%, #2a1210 0%, #0c0908 62%)',
  zone: null,
}

const WINTER: GameSkin = {
  id: 'winter',
  wall: { name: 'packed_ice', tint: 'rgba(4,16,36,.62)' },
  frame: 'blue_ice',
  well: { name: 'deepslate_tiles', tint: 'rgba(3,8,18,.82)' },
  wireOff: [0.1, 0.22, 0.42],
  wireOn: [0.45, 0.85, 1.15],
  ghost: { fill: 'rgba(120,200,255,.17)', stroke: 'rgba(150,220,255,.72)' },
  glowTint: 'rgba(170,220,255,',
  backdrop: 'radial-gradient(ellipse at 50% 35%, #12233a 0%, #070c14 62%)',
  zone: { name: 'frost', floor: 'snow', wall: 'packed_ice', ores: ['blue_ice', 'iron_ore', 'diamond_ore', 'blue_ice'], wood: 'spruce_log', roof: 'spruce_planks', amb: [52, 62, 78], torch: [250, 225, 190] },
}

const FOX: GameSkin = {
  id: 'fox',
  wall: { name: 'spruce_planks', tint: 'rgba(16,6,4,.6)' },
  frame: 'stripped_spruce_log',
  well: { name: 'deepslate_tiles', tint: 'rgba(10,5,6,.8)' },
  wireOff: [0.45, 0.2, 0.05],
  wireOn: [1.15, 0.55, 0.16],
  ghost: { fill: 'rgba(255,150,70,.17)', stroke: 'rgba(255,175,100,.72)' },
  glowTint: 'rgba(255,190,140,',
  backdrop: 'radial-gradient(ellipse at 50% 35%, #33190f 0%, #0e0806 62%)',
  zone: { name: 'den', floor: 'podzol_top', wall: 'stone', ores: ['coal_ore', 'iron_ore', 'coal_ore', 'redstone_ore'], wood: 'spruce_log', roof: 'spruce_planks', amb: [50, 42, 34], torch: [300, 190, 110] },
}

export function skinFor(design: string): GameSkin {
  if (design === 'winter') return WINTER
  if (design === 'fox') return FOX
  return REDSTONE
}

let loading: Promise<{ version: string, images: TextureMap }> | null = null

function decode(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('texture'))
    image.src = url
  })
}

/** Lädt einmal je Sitzung. Schlägt es fehl (keine passende Version), wird beim nächsten Öffnen neu versucht. */
export function loadGameTextures(): Promise<{ version: string, images: TextureMap }> {
  loading ??= backend.minigameTextures().then(async (set) => {
    const pairs = await Promise.all(set.textures.map(async t => [t.key, await decode(t.dataUrl)] as const))
    return { version: set.version, images: Object.fromEntries(pairs) }
  }).catch((error: unknown) => {
    loading = null
    throw error
  })
  return loading
}
