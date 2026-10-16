import { z } from 'zod'

// Halloween (Vertrag §32): nur solange `me.events` das Event enthält.
// Die gespeicherte Akzentfarbe (`data-accent`) bleibt unangetastet – das CSS
// überlagert die Farben, solange `data-event="halloween"` auf <html> steht.

export const HALLOWEEN_EVENT = 'halloween'
/** Merker für den Splash, der vor Vue läuft (nächster Start). */
export const EVENT_STORAGE_KEY = 'trs-event'
type ThemeRoot = { dataset: Record<string, string | undefined> }
type ThemeStorage = { setItem(key: string, value: string): void; removeItem(key: string): void }

/**
 * Event-Theme anwenden oder wegnehmen. `target` nur für Tests; sonst Dokument
 * und localStorage. Schreibt nie `data-accent` oder die gespeicherten Einstellungen.
 */
export function applyEventTheme(
  events: readonly string[],
  target?: { root: ThemeRoot | null; storage: ThemeStorage | null },
): void {
  const root = target ? target.root : typeof document === 'undefined' ? null : document.documentElement
  const storage = target ? target.storage : typeof localStorage === 'undefined' ? null : localStorage
  const on = events.includes(HALLOWEEN_EVENT)
  if (root) {
    if (on) root.dataset.event = HALLOWEEN_EVENT
    else delete root.dataset.event
  }
  if (!storage) return
  try {
    if (on) storage.setItem(EVENT_STORAGE_KEY, HALLOWEEN_EVENT)
    else storage.removeItem(EVENT_STORAGE_KEY)
  } catch {
    // Privater Modus: der Splash bleibt dann beim normalen Bild.
  }
}

/** Minecraft-Name oder UUID (mit oder ohne Bindestriche) – wie `validate::target` im Kern. */
export function isEventPlayer(input: string): boolean {
  const s = input.trim()
  if (/^[A-Za-z0-9_]{1,16}$/.test(s)) return true
  if (/^[0-9a-fA-F]{32}$/.test(s)) return true
  return /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(s)
}

// ---------------------------------------------------------------------------------------------
// Halloween 2026 „Nacht der langen Schatten“ (API §36): Kürbis-Suche, Süßes oder Saures,
// Abzeichen, Sticker. Schemas für das, was der Kern liefert, und reine Regeln für die
// Oberfläche. Getestet in tests/halloween.test.ts.

/** Ende der Event-Funktionen (= 3. Nov 00:00 Europe/Berlin). Gegenstände bleiben für immer. */
export const HALLOWEEN_END = '2026-11-02T23:00:00Z'
/** Die 5 Kürbisse des Launchers (Vertrag, Reihenfolge = Rätsel 1–5). */
export const LAUNCHER_PUMPKINS = ['launcher-titlebar', 'launcher-settings', 'launcher-skins', 'launcher-social', 'launcher-logs'] as const
export type LauncherPumpkin = (typeof LAUNCHER_PUMPKINS)[number]
export const PUMPKIN_TOTAL = 13
/** Abzeichen des Events (Bilder unter `/v1/badges/<id>.png`). */
export const HALLOWEEN_BADGES = ['halloween2026', 'pumpkinhunter2026'] as const

const itemId = z.string().regex(/^[a-z0-9][a-z0-9_-]{0,39}$/)
const iso = z.string().max(40).nullable().catch(null).default(null)
const pngData = z.string().startsWith('data:image/png;base64,')

export const localTextSchema = z.object({
  de: z.string().max(200).catch('').default(''),
  en: z.string().max(200).catch('').default(''),
  es: z.string().max(200).catch('').default(''),
})
export type HwText = z.infer<typeof localTextSchema>

export const halloweenRewardSchema = z.object({
  kind: z.enum(['cape', 'emote', 'sticker', 'other']).catch('other'),
  id: itemId,
  name: localTextSchema.catch({ de: '', en: '', es: '' }),
})
export type HalloweenReward = z.infer<typeof halloweenRewardSchema>

const trickResult = z.enum(['sweet', 'sour'])

export const halloweenStateSchema = z.object({
  active: z.boolean(),
  endsAt: iso,
  pumpkins: z.object({
    total: z.number().int().min(1).max(64),
    found: z.array(itemId).max(64),
    nextHint: localTextSchema.nullable().catch(null),
  }),
  trick: z.object({
    usedToday: z.boolean(),
    result: trickResult.nullable().catch(null),
    reward: halloweenRewardSchema.nullable().catch(null),
    next: iso,
  }),
  badges: z.array(itemId).max(16).catch([]),
  effects: z.object({ hits: z.boolean(), trail: z.boolean() }).catch({ hits: true, trail: true }),
  /** Flügel-Wahl nach allen 13 Kürbissen (genau einer, endgültig); ältere Kerne: nichts. */
  wings: z
    .object({ choice: itemId.nullable(), canChoose: z.boolean(), options: z.array(itemId).max(8) })
    .catch({ choice: null, canChoose: false, options: [] })
    .default({ choice: null, canChoose: false, options: [] }),
})
export type HalloweenState = z.infer<typeof halloweenStateSchema>

export const pumpkinFoundSchema = z.object({
  found: z.array(itemId).max(64),
  count: z.number().int().min(0).max(64),
  complete: z.boolean(),
  reward: z.object({ badge: itemId.nullable(), cape: itemId.nullable() }).nullable().catch(null),
})
export type PumpkinFound = z.infer<typeof pumpkinFoundSchema>

export const trickOutcomeSchema = z.object({
  result: trickResult,
  reward: halloweenRewardSchema.nullable().catch(null),
  next: iso,
})
export type TrickOutcome = z.infer<typeof trickOutcomeSchema>

export const stickerSchema = z.object({
  id: itemId,
  name: localTextSchema,
  image: pngData.nullable(),
  owned: z.boolean(),
})
export type Sticker = z.infer<typeof stickerSchema>

export const badgeImageSchema = z.object({ id: itemId, image: pngData })
export type BadgeImage = z.infer<typeof badgeImageSchema>

/** Echtzeit (§36): voller Stand nach Fund, Anklopfen oder Abzeichen – Teil von `liveEventSchema`. */
export const halloweenEventSchemas = [halloweenStateSchema.extend({ type: z.literal('halloween_updated') })] as const
export type HalloweenUpdatedEvent = z.infer<(typeof halloweenEventSchemas)[0]>

/** Text in der Oberflächensprache; fehlt er, Englisch, dann Deutsch, dann Spanisch. */
export function localText(text: HwText | null | undefined, locale: string): string {
  if (!text) return ''
  const base = locale.toLowerCase().split('-')[0]
  const own = base === 'de' ? text.de : base === 'es' ? text.es : base === 'en' ? text.en : ''
  return own || text.en || text.de || text.es
}

/** Laufen Kürbis-Suche und Süßes oder Saures gerade? (Server `active` und vor dem Ende.) */
export function halloweenFeaturesOn(state: Pick<HalloweenState, 'active' | 'endsAt'> | null | undefined, now = Date.now()): boolean {
  if (!state?.active) return false
  const end = Date.parse(state.endsAt ?? HALLOWEEN_END)
  return Number.isFinite(end) ? now < end : now < Date.parse(HALLOWEEN_END)
}

/** Diesen Kürbis zeigen? Nur solange die Suche läuft und er noch nicht gefunden ist. */
export function showPumpkin(state: HalloweenState | null | undefined, id: LauncherPumpkin, now = Date.now()): boolean {
  return !!state && halloweenFeaturesOn(state, now) && !state.pumpkins.found.includes(id)
}

/** Belohnungs-Umhang aus dem Halloween-Event? (`unlock: "reward"`, `rewardOf: "halloween…"`) */
export function isHalloweenReward(unlock: string, rewardOf: string | null | undefined): boolean {
  return unlock === 'reward' && (!rewardOf || rewardOf.startsWith('halloween'))
}

/** Ab wann wieder angeklopft werden darf, als lokale Uhrzeit (oder `null`). */
export function nextTrickAt(state: Pick<HalloweenState, 'trick'> | null | undefined): Date | null {
  const next = state?.trick.next
  if (!next) return null
  const d = new Date(next)
  return Number.isNaN(d.getTime()) ? null : d
}

/** Darf heute (noch) angeklopft werden? `next` in der Vergangenheit hebt `usedToday` auf. */
export function canTrick(state: HalloweenState | null | undefined, now = Date.now()): boolean {
  if (!state || !halloweenFeaturesOn(state, now)) return false
  if (!state.trick.usedToday) return true
  const next = nextTrickAt(state)
  return !!next && next.getTime() <= now
}
