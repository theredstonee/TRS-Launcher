// Design-Varianten der Start-Animation als kleine Registry. Ein künftiges Event braucht nur einen weiteren Eintrag
// (Farben + Requisiten + Klangfarbe); die Auswahl läuft automatisch über das aktive Event bzw. das gewählte Design.

export type IntroLight = 'torch' | 'pumpkin' | 'iceLantern' | 'campfire'
export type IntroCameo = 'creeper' | 'bats' | 'penguin' | 'fox'
export type IntroSky = 'cave' | 'moon' | 'aurora' | 'sunset'
export type IntroMood = 'redstone' | 'spooky' | 'frost' | 'fox'

export interface IntroPalette {
  /** Ganz hinten (Leere unter/um das Diorama). */
  void: string
  /** Himmel/Höhle über der Rückwand oben → unten. */
  sky: [string, string]
  /** Glühen aus der Tiefe unter dem Diorama. */
  abyss: string
  /** Grundlicht (wird multipliziert – bestimmt, wie dunkel der Dungeon ist). */
  ambient: string
  /** Stein dunkel → hell (Wände, Boden). */
  stone: [string, string, string, string]
  mortar: string
  /** Akzent im Boden (Moos, Raureif, Purpurmoos). */
  moss: string
  /** Schnee/Raureif auf Oberseiten (leer = keiner). */
  frost: string
  /** Türblöcke. */
  door: [string, string, string]
  doorBand: string
  wireOff: string
  wireOn: string
  wireHot: string
  /** Lichtfarbe der Leitung. */
  wireLight: string
  lampOff: [string, string]
  lampOn: [string, string, string]
  /** Licht der Fackeln/Laternen. */
  light: string
  /** Flamme innen → außen. */
  flame: [string, string, string]
  fog: string
  wood: [string, string]
  iron: string
  /** Farbe des Lampenblitzes. */
  flash: string
  /** Glühen hinter dem Logo, Strahlen, Schriftzug. */
  logoGlow: string
  ray: string
  wordmark: string
  /** Kaltes Licht (Mond, Polarlicht, Fenster). */
  cold: string
}

export interface IntroProps {
  light: IntroLight
  cameo: IntroCameo
  sky: IntroSky
  /** Nebeldichte 0…1. */
  fog: number
  snow: boolean
  bats: boolean
  /** Banner an der Rückwand. */
  banners: boolean
  cobwebs: boolean
}

export interface IntroAudioStyle {
  mood: IntroMood
  /**
   * Sekunde in der Jingle-Datei, auf der der große Schlag liegt – sie wird auf `impact` gelegt. Kürzere Fassungen
   * spielen dieselbe Datei einfach ab einer späteren Stelle (weich eingeblendet).
   */
  jingleHit: number
}

/** Szenen-Set: eigene Welt, eigene Kamerafahrten, eigene Effekte. */
export type IntroSceneKind = 'dungeon' | 'graveyard' | 'frost' | 'foxden'

export interface IntroDesign {
  id: string
  scene: IntroSceneKind
  /** Aktiv, solange dieses Event läuft (hat Vorrang vor dem Design). */
  event?: string
  /** Aktiv bei diesem Design aus den Einstellungen. */
  design?: string
  palette: IntroPalette
  props: IntroProps
  audio: IntroAudioStyle
}

const redstone: IntroDesign = {
  id: 'redstone',
  scene: 'dungeon',
  palette: {
    void: '#050508',
    sky: ['#07070c', '#0e0d14'],
    abyss: '#ff5a2a',
    ambient: '#2f3350',
    stone: ['#1c1c24', '#2b2b36', '#3d3d4b', '#55556a'],
    mortar: '#121218',
    moss: '#3f5a3a',
    frost: '',
    door: ['#2a2a33', '#3a3a46', '#4d4d5c'],
    doorBand: '#6d6f7d',
    wireOff: '#4a0d09',
    wireOn: '#ff2a1a',
    wireHot: '#ffd2b8',
    wireLight: '#ff3020',
    lampOff: ['#3a2416', '#6b4022'],
    lampOn: ['#ffb84d', '#ffe39a', '#fffbe8'],
    light: '#ffa04a',
    flame: ['#fff6c8', '#ffc24a', '#ff6a1a'],
    fog: '#6a5a6a',
    wood: ['#6b4a2a', '#8f6a3c'],
    iron: '#b8bcc8',
    flash: '#fff2dc',
    logoGlow: '#ff3a24',
    ray: '#ffb070',
    wordmark: '#f4ebe6',
    cold: '#7a90d0',
  },
  props: { light: 'torch', cameo: 'creeper', sky: 'cave', fog: 0.35, snow: false, bats: false, banners: true, cobwebs: false },
  audio: { mood: 'redstone', jingleHit: 7.9 },
}

const halloween: IntroDesign = {
  id: 'halloween',
  scene: 'graveyard',
  event: 'halloween',
  palette: {
    void: '#06040c',
    sky: ['#0b0818', '#1d1533'],
    abyss: '#7a3cff',
    ambient: '#3a3060',
    stone: ['#1b1726', '#2a2338', '#3c3350', '#56496e'],
    mortar: '#110d1a',
    moss: '#4b2f6e',
    frost: '',
    door: ['#2a2036', '#3a2d4a', '#4f3f63'],
    doorBand: '#9a93b0',
    wireOff: '#2e1250',
    wireOn: '#b45cff',
    wireHot: '#f0dcff',
    wireLight: '#a050ff',
    lampOff: ['#2c2238', '#4a3a5c'],
    lampOn: ['#cfd5e3', '#eef1f8', '#ffffff'],
    light: '#ff9a3a',
    flame: ['#fff2b0', '#ffb040', '#e85a10'],
    fog: '#7a6a9a',
    wood: ['#3f2a1e', '#5a3d28'],
    iron: '#cfd5e3',
    flash: '#f3f5fb',
    logoGlow: '#b9a2ea',
    ray: '#e6e9f2',
    wordmark: '#f3f5fb',
    cold: '#cfd5e3',
  },
  props: { light: 'pumpkin', cameo: 'bats', sky: 'moon', fog: 0.7, snow: false, bats: true, banners: false, cobwebs: true },
  audio: { mood: 'spooky', jingleHit: 7.9 },
}

const winter: IntroDesign = {
  id: 'winter',
  scene: 'frost',
  design: 'winter',
  palette: {
    void: '#04070e',
    sky: ['#070d1c', '#15203a'],
    abyss: '#3a9cff',
    ambient: '#3f5578',
    stone: ['#1a2234', '#273249', '#3a4862', '#57698a'],
    mortar: '#0f1522',
    moss: '#5a7aa0',
    frost: '#e6f2ff',
    door: ['#5a8cc0', '#7fb2e0', '#b5dcff'],
    doorBand: '#eaf4ff',
    wireOff: '#4a0d09',
    wireOn: '#ff3324',
    wireHot: '#ffe0d0',
    wireLight: '#ff4030',
    lampOff: ['#2a3040', '#4a5068'],
    lampOn: ['#ffd27a', '#fff0c0', '#ffffff'],
    light: '#9fdcff',
    flame: ['#ffffff', '#bfe9ff', '#5ab8ff'],
    fog: '#9fb4d6',
    wood: ['#4a3a30', '#6a5444'],
    iron: '#dfe8f4',
    flash: '#eef8ff',
    logoGlow: '#7fd3ff',
    ray: '#cfeeff',
    wordmark: '#eaf4ff',
    cold: '#7fe0c8',
  },
  props: { light: 'iceLantern', cameo: 'penguin', sky: 'aurora', fog: 0.45, snow: true, bats: false, banners: false, cobwebs: false },
  audio: { mood: 'frost', jingleHit: 2.7 },
}

// Fuchsbau (Arbeitsname): Minecraft-Wald in der Abenddämmerung mit Fuchsfamilie. Jingle app/assets/intro/fox.ogg/.mp3
// (Suno, 101 BPM, ganze 12 s): `jingleHit` = zweiter Höhepunkt bei 9,72 s (Bass-Einsatz nach kurzer Senke), danach
// klingt das Stück genau zur Übergabe aus. Anderer Jingle: Datei ersetzen, hier die Schlag-Sekunde eintragen und
// FOXDEN_BEAT (foxden.ts) aufs neue Tempo setzen.
const fox: IntroDesign = {
  id: 'fox',
  scene: 'foxden',
  design: 'fox',
  palette: {
    void: '#0e0810',
    sky: ['#2a1a3a', '#ffc27a'],
    abyss: '#e0785a',
    ambient: '#f2cdb8',
    stone: ['#3a2a26', '#6e4630', '#8a5a3a', '#9a6a44'],
    mortar: '#2c1c0e',
    moss: '#2f5a40',
    frost: '',
    door: ['#2c1c0e', '#3b2614', '#4e3420'],
    doorBand: '#e3823a',
    wireOff: '#7a3a5a',
    wireOn: '#e3823a',
    wireHot: '#fff0d8',
    wireLight: '#ffb060',
    lampOff: ['#2a2a32', '#4a3a30'],
    lampOn: ['#ffb347', '#fff0b0', '#ffffff'],
    light: '#ff9a40',
    flame: ['#fff2a8', '#ffb340', '#ff6a1a'],
    fog: '#e0785a',
    wood: ['#3b2614', '#4e3420'],
    iron: '#2a2a32',
    flash: '#fff0d8',
    logoGlow: '#ffb060',
    ray: '#ffd8a0',
    wordmark: '#fff4ea',
    cold: '#f3a6c8',
  },
  props: { light: 'campfire', cameo: 'fox', sky: 'sunset', fog: 0.2, snow: false, bats: false, banners: false, cobwebs: false },
  audio: { mood: 'fox', jingleHit: 9.72 },
}

/** Reihenfolge = Vorrang: Events zuerst, dann Designs, Redstone ist der Rückfall. */
export const INTRO_DESIGNS: readonly IntroDesign[] = [halloween, winter, fox, redstone]

export const DEFAULT_INTRO_DESIGN = redstone

export function introDesignById(id: string | null | undefined): IntroDesign {
  return INTRO_DESIGNS.find((d) => d.id === id) ?? DEFAULT_INTRO_DESIGN
}

/** Wählt die Variante: erst ein laufendes Event, dann das eingestellte Design, sonst Redstone. */
export function pickIntroDesign(state: { events?: readonly string[]; design?: string | null }): IntroDesign {
  const events = state.events ?? []
  const byEvent = INTRO_DESIGNS.find((d) => d.event && events.includes(d.event))
  if (byEvent) return byEvent
  const byDesign = INTRO_DESIGNS.find((d) => d.design && d.design === state.design)
  return byDesign ?? DEFAULT_INTRO_DESIGN
}
