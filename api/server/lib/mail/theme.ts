import type { AppContext } from '../context'
import { globalEvents } from '../liveevents'

/**
 * Event-Looks der Mails (freigegebener Entwurf `mail-design/gen_mail.py`): Redstone ist Standard, Halloween /
 * Winternacht / Fuchsbau folgen dem aktiven Event der Website (`look = auto`) oder werden im Editor gewählt.
 */

export const LOOKS = ['redstone', 'halloween', 'winter', 'fox'] as const
export type Look = (typeof LOOKS)[number]
export const LOOK_CHOICES = ['auto', ...LOOKS] as const
export type LookChoice = (typeof LOOK_CHOICES)[number]

export interface Theme {
  name: string
  bg: string
  card: string
  card2: string
  line: string
  text: string
  muted: string
  accent: string
  accent2: string
  glow: string
  btnText: string
  badge: string
  badgeText: string
}

export const THEMES: Record<Look, Theme> = {
  redstone: { name: 'Redstone', bg: '#0d0d12', card: '#17171e', card2: '#1d1d26', line: '#2b2b38', text: '#e9e9f2', muted: '#9a9ab0', accent: '#e0281e', accent2: '#ff5a4d', glow: '#ffb84d', btnText: '#ffffff', badge: '#3a0d09', badgeText: '#ff8f85' },
  halloween: { name: 'Halloween', bg: '#0a0710', card: '#151020', card2: '#1d1529', line: '#33243f', text: '#ece6f5', muted: '#a596b8', accent: '#e08a3c', accent2: '#ffb84d', glow: '#8e6cc9', btnText: '#1a0d05', badge: '#2c1a3f', badgeText: '#c7a8ff' },
  winter: { name: 'Winternacht', bg: '#060d19', card: '#0e1a2e', card2: '#132442', line: '#22385c', text: '#e8f3ff', muted: '#90a9c6', accent: '#3aa9cf', accent2: '#6fd0ea', glow: '#e2fbff', btnText: '#04131c', badge: '#0b3a52', badgeText: '#a8ecf8' },
  fox: { name: 'Fuchsbau', bg: '#140c18', card: '#211423', card2: '#2b1a2c', line: '#43283f', text: '#fbeef0', muted: '#c6a3b4', accent: '#ff7a2e', accent2: '#ffa35c', glow: '#f6b8d6', btnText: '#2a0f02', badge: '#4a2340', badgeText: '#ffc4e0' },
}

/** Look-Namen je Sprache (Abzeichen oben rechts). */
export const LOOK_NAMES: Record<Look, Record<'de' | 'en' | 'es', string>> = {
  redstone: { de: 'Redstone', en: 'Redstone', es: 'Redstone' },
  halloween: { de: 'Halloween', en: 'Halloween', es: 'Halloween' },
  winter: { de: 'Winternacht', en: 'Winter Night', es: 'Noche de invierno' },
  fox: { de: 'Fuchsbau', en: 'Fox Den', es: 'Madriguera' },
}

export const isLook = (v: unknown): v is Look => typeof v === 'string' && (LOOKS as readonly string[]).includes(v)

/** Look aus einer Event-ID der Website (`halloween`, `winter…`, `fox…`), sonst `null`. */
export function lookOfEvent(id: string): Look | null {
  if (id.startsWith('halloween')) return 'halloween'
  if (id.startsWith('winter') || id.startsWith('christmas') || id.startsWith('xmas')) return 'winter'
  if (id.startsWith('fox') || id.startsWith('autumn')) return 'fox'
  return null
}

/** Look für „automatisch“: das erste global aktive Event mit eigenem Look, sonst Redstone. */
export function autoLook(ctx: AppContext): Look {
  for (const id of globalEvents(ctx)) {
    const l = lookOfEvent(id)
    if (l) return l
  }
  return 'redstone'
}

export function resolveLook(ctx: AppContext, choice: LookChoice | null | undefined): Look {
  return choice && choice !== 'auto' ? choice : autoLook(ctx)
}
