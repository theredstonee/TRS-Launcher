/**
 * Feste Emote-Liste. Die Animationen selbst stecken in Launcher und Mod; die API
 * kennt nur IDs, Freischaltung und Dauer. IDs sind dauerhaft – nie umbenennen,
 * nur neue anhängen (unbekannte IDs ignorieren Clients).
 */
export interface EmoteDef {
  id: string
  name: string
  unlock: 'free' | 'code' | 'admin'
  /** Wie lange das Emote läuft (ms). Bei `loop` wird die Animation so lange wiederholt. */
  durationMs: number
  loop: boolean
}

export const EMOTES: readonly EmoteDef[] = [
  { id: 'winken', name: 'Winken', unlock: 'free', durationMs: 2000, loop: false },
  { id: 'klatschen', name: 'Klatschen', unlock: 'free', durationMs: 2500, loop: false },
  { id: 'jubeln', name: 'Jubeln', unlock: 'free', durationMs: 2500, loop: false },
  { id: 'verbeugen', name: 'Verbeugen', unlock: 'free', durationMs: 2000, loop: false },
  { id: 'facepalm', name: 'Facepalm', unlock: 'free', durationMs: 2000, loop: false },
  { id: 'schulterzucken', name: 'Schulterzucken', unlock: 'free', durationMs: 1500, loop: false },
  { id: 'daumen_hoch', name: 'Daumen hoch', unlock: 'free', durationMs: 1500, loop: false },
  { id: 'tanzen', name: 'Tanzen', unlock: 'code', durationMs: 6000, loop: true },
  { id: 'salutieren', name: 'Salutieren', unlock: 'code', durationMs: 2000, loop: false },
  { id: 'luftgitarre', name: 'Luftgitarre', unlock: 'code', durationMs: 5000, loop: true },
  { id: 'redstone_tanz', name: 'Redstone-Tanz', unlock: 'admin', durationMs: 6000, loop: true },
]

export const EMOTE_BY_ID: ReadonlyMap<string, EmoteDef> = new Map(EMOTES.map((e) => [e.id, e]))
