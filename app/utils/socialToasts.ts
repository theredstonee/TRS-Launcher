// Regeln für Benachrichtigungen aus „Sozial“ – reine Funktionen, getestet in
// tests/social-toasts.test.ts. Wann eine Benachrichtigung erscheint, ob das
// Betriebssystem sie zeigt, ob es einen Ton gibt, und wie die Liste wächst.

export type SocialToastKind = 'message' | 'invite' | 'friendRequest' | 'capeOffer' | 'online' | 'report' | 'moderation'

/** Einstellungen (Rust: `SocialSettings`, alles lokal). */
export interface SocialPrefs {
  toasts: boolean
  corner: 'top-right' | 'top-left' | 'bottom-right' | 'bottom-left'
  durationSecs: number
  sound: boolean
  doNotDisturb: boolean
  quietInFullscreen: boolean
  native: boolean
  quickReply: boolean
  messages: boolean
  invites: boolean
  friendRequests: boolean
  capeOffers: boolean
  friendOnline: boolean
}

export const defaultSocialPrefs: SocialPrefs = {
  toasts: true,
  corner: 'top-right',
  durationSecs: 5,
  sound: true,
  doNotDisturb: false,
  quietInFullscreen: true,
  native: true,
  quickReply: true,
  messages: true,
  invites: true,
  friendRequests: true,
  capeOffers: true,
  friendOnline: true,
}

/** Was gerade los ist, wenn eine Benachrichtigung ansteht. */
export interface Situation {
  /** Launcher-Fenster hat den Fokus. */
  focused: boolean
  /** Launcher-Fenster ist sichtbar (nicht minimiert/verdeckt). */
  visible: boolean
  /** Windows meldet Vollbild/Präsentation/Nicht stören. */
  fullscreen: boolean
  /** Der Nutzer sieht genau diese Unterhaltung gerade. */
  looking: boolean
  /** Diese Unterhaltung ist stummgeschaltet. */
  muted: boolean
  /**
   * Ein Spiel mit verbundenem TRS Client läuft – der zeigt Sozial-Hinweise
   * selbst im Spiel, der Launcher schweigt dann (kein Hinweis, kein Ton, keine
   * Windows-Benachrichtigung; nichts wird nachgeholt, ungelesen bleibt ungelesen).
   */
  clientInGame: boolean
}

export interface Delivery {
  toast: boolean
  native: boolean
  sound: boolean
}

const NONE: Delivery = { toast: false, native: false, sound: false }

/** Rückmeldungen über dich selbst (Meldung geprüft, Stummschaltung) kommen immer. */
function essential(kind: SocialToastKind): boolean {
  return kind === 'report' || kind === 'moderation'
}

function typeEnabled(kind: SocialToastKind, prefs: SocialPrefs): boolean {
  switch (kind) {
    case 'message':
      return prefs.messages
    case 'invite':
      return prefs.invites
    case 'friendRequest':
      return prefs.friendRequests
    case 'capeOffer':
      return prefs.capeOffers
    case 'online':
      return prefs.friendOnline
    default:
      return true
  }
}

/** Nicht stören: von Hand oder automatisch bei Vollbild. */
export function isQuiet(prefs: SocialPrefs, fullscreen: boolean): boolean {
  return prefs.doNotDisturb || (prefs.quietInFullscreen && fullscreen)
}

/** Entscheidet, ob und wie eine Benachrichtigung erscheint. */
export function decide(kind: SocialToastKind, prefs: SocialPrefs, s: Situation): Delivery {
  if (s.clientInGame) return NONE
  if (!essential(kind)) {
    if (!prefs.toasts || !typeEnabled(kind, prefs)) return NONE
    if ((kind === 'message' || kind === 'invite') && (s.muted || (s.looking && s.focused && s.visible))) return NONE
  }
  const quiet = isQuiet(prefs, s.fullscreen)
  if (quiet && !essential(kind)) return NONE
  const away = !s.focused || !s.visible
  return {
    toast: true,
    native: prefs.native && away && !quiet,
    sound: prefs.sound && !quiet,
  }
}

export interface SocialToastAction {
  label: string
  /** Hervorgehoben (Hauptaktion). */
  primary?: boolean
  run: () => void | Promise<void>
}

export interface SocialToast {
  id: number
  kind: SocialToastKind
  /** Gleicher Schlüssel = ersetzt die alte (z. B. mehrere Nachrichten derselben Unterhaltung). */
  key: string
  title: string
  body: string
  /** Gesicht (UUID + Name) – sonst ein Symbol. */
  face: { uuid: string; name: string } | null
  /** Wie viele zusammengefasst sind. */
  count: number
  actions: SocialToastAction[]
  /** Klick auf die Benachrichtigung. */
  open?: () => void
  /** Schnellantwort (nur Nachrichten). */
  reply?: (text: string) => Promise<boolean>
}

export const MAX_SOCIAL_TOASTS = 4

/**
 * Neue Benachrichtigung einreihen: gleicher Schlüssel → ersetzt (Zähler +1)
 * und rückt nach vorn; über der Grenze fliegt die älteste raus.
 */
export function pushSocialToast(
  list: readonly SocialToast[],
  toast: Omit<SocialToast, 'count'>,
  max = MAX_SOCIAL_TOASTS,
): { list: SocialToast[]; dropped: number[]; replaced: number | null } {
  const existing = list.find((t) => t.key === toast.key)
  const count = existing ? existing.count + 1 : 1
  let next = list.filter((t) => t.key !== toast.key)
  next.push({ ...toast, count })
  const dropped: number[] = []
  while (next.length > max) {
    const [oldest, ...rest] = next
    dropped.push(oldest!.id)
    next = rest
  }
  return { list: next, dropped, replaced: existing ? existing.id : null }
}

/** Tailwind-Klassen für die Ecke. */
export function cornerClasses(corner: SocialPrefs['corner']): string {
  switch (corner) {
    case 'top-left':
      return 'top-12 left-16 items-start'
    case 'bottom-right':
      return 'bottom-4 right-4 items-end flex-col-reverse'
    case 'bottom-left':
      return 'bottom-4 left-16 items-start flex-col-reverse'
    default:
      return 'top-12 right-4 items-end'
  }
}

/** Anzeigedauer in ms (3–10 s). Toasts mit Aktionen bleiben etwas länger. */
export function toastDuration(prefs: Pick<SocialPrefs, 'durationSecs'>, hasActions: boolean): number {
  const secs = Math.min(10, Math.max(3, Math.round(prefs.durationSecs || 5)))
  return (secs + (hasActions ? 3 : 0)) * 1000
}
