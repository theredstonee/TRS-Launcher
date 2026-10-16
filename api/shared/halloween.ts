/**
 * Halloween 2026 „Nacht der langen Schatten“ (API.md §36) – feste Daten für Server und Website (keine Importe).
 * IDs sind dauerhaft: nie umbenennen, nur anhängen.
 */

export type Lang3 = { de: string, en: string, es: string }

/** Event, an dem alles hängt (§32). */
export const HALLOWEEN_EVENT = 'halloween'
/** Schlüssel der Belohnungen (`unlock: "reward"`, `rewardOf`). */
export const HALLOWEEN_REWARD = 'halloween2026'
/** Ende der Funktionen (Abzeichen, Kürbisse, Süßes oder Saures) = 3. Nov. 00:00 Europe/Berlin. Teile bleiben für immer. */
export const HALLOWEEN_END_MS = Date.UTC(2026, 10, 2, 23, 0, 0)
export const HALLOWEEN_END_ISO = new Date(HALLOWEEN_END_MS).toISOString()

/** Die 13 Kürbisse in Hinweis-Reihenfolge (Launcher, Website, TRS Client). */
export const PUMPKINS = [
  'launcher-titlebar',
  'launcher-settings',
  'launcher-skins',
  'launcher-social',
  'launcher-logs',
  'web-home',
  'web-features',
  'web-roadmap',
  'web-creator',
  'client-title',
  'client-wardrobe',
  'client-hud',
  'client-modules',
] as const
export type PumpkinId = (typeof PUMPKINS)[number]
export const PUMPKIN_TOTAL = PUMPKINS.length

export function isPumpkinId(id: string): id is PumpkinId {
  return (PUMPKINS as readonly string[]).includes(id)
}

/** Rätsel je Kürbis (gleiche Reihenfolge). Der Server gibt immer das des ersten noch nicht gefundenen zurück. */
export const RIDDLES: Record<PumpkinId, Lang3> = {
  'launcher-titlebar': {
    de: 'Wo die Fenster schließen, webt die Spinne ihr Netz.',
    en: 'Where windows close, the spider weaves its web.',
    es: 'Donde se cierran las ventanas, la araña teje su red.',
  },
  'launcher-settings': {
    de: 'Ganz unten, wo die Nummer der Version wohnt.',
    en: 'At the very bottom, where the version number lives.',
    es: 'Abajo del todo, donde vive el número de versión.',
  },
  'launcher-skins': {
    de: 'Er steht dort, wo du dich drehst und spiegelst.',
    en: 'It waits where you turn and see yourself.',
    es: 'Espera donde giras y te ves.',
  },
  'launcher-social': {
    de: 'Wo Freunde fehlen, ist Platz für einen Kürbis.',
    en: 'Where friends are missing, there is room for a pumpkin.',
    es: 'Donde faltan amigos, hay sitio para una calabaza.',
  },
  'launcher-logs': {
    de: 'Zwischen Zeilen voller Fehler grinst einer.',
    en: 'Between lines full of errors, one grins.',
    es: 'Entre líneas llenas de errores, una sonríe.',
  },
  'web-home': {
    de: 'Auf der Startseite, zwischen den Gräbern.',
    en: 'On the home page, between the graves.',
    es: 'En la portada, entre las tumbas.',
  },
  'web-features': {
    de: 'Wo alle Funktionen enden.',
    en: 'Where all the features end.',
    es: 'Donde terminan todas las funciones.',
  },
  'web-roadmap': {
    de: 'Auf dem Weg in die Zukunft, über einer Spalte.',
    en: 'On the road to the future, above a column.',
    es: 'En el camino al futuro, sobre una columna.',
  },
  'web-creator': {
    de: 'Bei denen, die Videos machen.',
    en: 'With the ones who make videos.',
    es: 'Con los que hacen vídeos.',
  },
  'client-title': {
    de: 'Im Spiel, wo die Version steht – noch bevor du spielst.',
    en: 'In game, where the version is shown – before you even play.',
    es: 'En el juego, donde se ve la versión, antes de jugar.',
  },
  'client-wardrobe': {
    de: 'Hinter dir, wenn du dich umziehst.',
    en: 'Behind you while you change clothes.',
    es: 'Detrás de ti mientras te cambias.',
  },
  'client-hud': {
    de: 'In der linken unteren Ecke deiner Anzeigen.',
    en: 'In the bottom-left corner of your overlays.',
    es: 'En la esquina inferior izquierda de tus indicadores.',
  },
  'client-modules': {
    de: 'Ganz am Ende aller Module.',
    en: 'At the very end of all modules.',
    es: 'Al final de todos los módulos.',
  },
}

/** Profil-Abzeichen (`GET /v1/badges/{id}.png`, Pixel ×8). `icon` = Sprite in halloween-art. */
export const BADGES = {
  halloween2026: { icon: 'moonbat', name: { de: 'Halloween 2026', en: 'Halloween 2026', es: 'Halloween 2026' } },
  pumpkinhunter2026: { icon: 'pk8', name: { de: 'Kürbisjäger 2026', en: 'Pumpkin Hunter 2026', es: 'Cazador de calabazas 2026' } },
} as const satisfies Record<string, { icon: string, name: Lang3 }>
export type BadgeId = keyof typeof BADGES
export const BADGE_IDS = Object.keys(BADGES) as BadgeId[]

export function isBadgeId(id: string): id is BadgeId {
  return Object.prototype.hasOwnProperty.call(BADGES, id)
}

/** Chat-Sticker (12×12-Sprite ×8 = 96×96 PNG). */
export const STICKERS = [
  { id: 'pumpkin_hihi', sprite: 'pumpkin', name: { de: 'Hihi', en: 'Hehe', es: 'Jiji' } },
  { id: 'ghost_boo', sprite: 'ghost', name: { de: 'Buh!', en: 'Boo!', es: '¡Bu!' } },
  { id: 'bat_flutter', sprite: 'bat', name: { de: 'Flatter', en: 'Flutter', es: 'Aleteo' } },
  { id: 'moon_goodnight', sprite: 'moon', name: { de: 'Gute Nacht', en: 'Good night', es: 'Buenas noches' } },
  { id: 'cauldron_blubb', sprite: 'cauldron', name: { de: 'Blubb', en: 'Blub', es: 'Glu glu' } },
  { id: 'grave_riplag', sprite: 'grave', name: { de: 'RIP Lag', en: 'RIP Lag', es: 'RIP Lag' } },
] as const
export type StickerId = (typeof STICKERS)[number]['id']
export const STICKER_IDS = STICKERS.map((s) => s.id) as StickerId[]

export function isStickerId(id: string): id is StickerId {
  return (STICKER_IDS as string[]).includes(id)
}

/** Umhang „Mondnacht“ in sechs Farben (Süßes) + exklusiver Kürbisjäger-Umhang (alle 13 Kürbisse). */
export const HALLOWEEN_CAPES = [
  { id: 'halloween_night_violet', name: { de: 'Mondnacht · Nachtviolett', en: 'Moon Night · Night Violet', es: 'Noche de luna · Violeta nocturno' } },
  { id: 'halloween_moon_silver', name: { de: 'Mondnacht · Mondsilber', en: 'Moon Night · Moon Silver', es: 'Noche de luna · Plata lunar' } },
  { id: 'halloween_ghost_white', name: { de: 'Mondnacht · Geisterweiß', en: 'Moon Night · Ghost White', es: 'Noche de luna · Blanco fantasma' } },
  { id: 'halloween_poison_green', name: { de: 'Mondnacht · Giftgrün', en: 'Moon Night · Poison Green', es: 'Noche de luna · Verde veneno' } },
  { id: 'halloween_blood_moon', name: { de: 'Mondnacht · Blutmond', en: 'Moon Night · Blood Moon', es: 'Noche de luna · Luna de sangre' } },
  { id: 'halloween_pumpkin_glow', name: { de: 'Mondnacht · Kürbisglut', en: 'Moon Night · Pumpkin Glow', es: 'Noche de luna · Brasa de calabaza' } },
] as const
export const HUNTER_CAPE = { id: 'halloween_hunter_2026', name: { de: 'Kürbisjäger 2026', en: 'Pumpkin Hunter 2026', es: 'Cazador de calabazas 2026' } } as const

/** Neue Emotes (Süßes). Animationen stecken in Launcher und Mod. */
export const HALLOWEEN_EMOTES = [
  { id: 'count_bow', name: { de: 'Graf-Verbeugung', en: 'Count’s Bow', es: 'Reverencia del conde' } },
  { id: 'ghost_float', name: { de: 'Spuk-Schweben', en: 'Ghost Float', es: 'Flotar fantasmal' } },
  { id: 'zombie_walk', name: { de: 'Zombie-Gang', en: 'Zombie Walk', es: 'Paso zombi' } },
  { id: 'moon_howl', name: { de: 'Mondheulen', en: 'Moon Howl', es: 'Aullido a la luna' } },
] as const

/** Belohnungs-Schlüssel der Kürbisjäger-Flügel. */
export const HUNTER_REWARD = 'pumpkinhunter2026'

/** Halloween-Flügel (Platz `wings`, Format v2): nach allen 13 Kürbissen wählt jeder genau EINEN. */
export const HALLOWEEN_WINGS = [
  { id: 'bat_wings', name: { de: 'Fledermausflügel', en: 'Bat Wings', es: 'Alas de murciélago' } },
  { id: 'demon_wings', name: { de: 'Dämonenflügel', en: 'Demon Wings', es: 'Alas de demonio' } },
  { id: 'raven_wings', name: { de: 'Rabenflügel', en: 'Raven Wings', es: 'Alas de cuervo' } },
  { id: 'ghost_wings', name: { de: 'Geisterflügel', en: 'Ghost Wings', es: 'Alas fantasmales' } },
] as const
export const HALLOWEEN_WING_IDS: readonly string[] = HALLOWEEN_WINGS.map((w) => w.id)
export function isHalloweenWing(id: string): boolean {
  return HALLOWEEN_WING_IDS.includes(id)
}

export type RewardKind = 'cape' | 'emote' | 'sticker'

/** IDs der sechs Mondnacht-Farben. Jeder Spieler darf höchstens EINE davon besitzen. */
export const MONDNACHT_CAPE_IDS: readonly string[] = HALLOWEEN_CAPES.map((c) => c.id)
export function isMondnachtCape(id: string): boolean {
  return MONDNACHT_CAPE_IDS.includes(id)
}
/** Topf-Eintrag „Mondnacht-Umhang“: zählt als EIN Teil, die Farbe würfelt der Server beim Gewinn. */
export const MONDNACHT_POOL_ID = 'mondnacht'
export const MONDNACHT_NAME: Lang3 = {
  de: 'Mondnacht-Umhang – zufällige Farbe, jeder nur einen',
  en: 'Moon Night cape – random colour, only one per player',
  es: 'Capa Noche de luna – color aleatorio, solo una por jugador',
}

/** Topf für „Süßes oder Saures“ (11 Teile: 1 Mondnacht-Umhang + 4 Emotes + 6 Sticker). */
export const TRICK_POOL: readonly { kind: RewardKind, id: string, name: Lang3 }[] = [
  { kind: 'cape', id: MONDNACHT_POOL_ID, name: MONDNACHT_NAME },
  ...HALLOWEEN_EMOTES.map((e) => ({ kind: 'emote' as const, id: e.id, name: e.name })),
  ...STICKERS.map((s) => ({ kind: 'sticker' as const, id: s.id, name: s.name })),
]

/** Anteil „Saures“ (1 von 4). */
export const SOUR_ONE_IN = 4

/** Belohnungs-Schlüssel → Event, solange dessen Belohnungen im Katalog gezeigt werden. */
export const REWARD_EVENTS: Record<string, string> = { [HALLOWEEN_REWARD]: HALLOWEEN_EVENT, [HUNTER_REWARD]: HALLOWEEN_EVENT }

/** Name eines Belohnungs-Teils (für Antworten), sonst `null`. */
export function rewardName(kind: RewardKind, id: string): Lang3 | null {
  if (kind === 'cape' && id === HUNTER_CAPE.id) return HUNTER_CAPE.name
  if (kind === 'cape') {
    const c = HALLOWEEN_CAPES.find((x) => x.id === id)
    if (c) return c.name
  }
  return TRICK_POOL.find((p) => p.kind === kind && p.id === id)?.name ?? null
}
