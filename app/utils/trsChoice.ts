import type { TrsConflict, TrsOffer } from '~/types'

// „Mit oder ohne TRS Client?“ beim Installieren bzw. Importieren von Modpacks
// und Instanzen mit Mods. Der Kern liefert je Pack ein Angebot (TrsOffer:
// passt es, was ist vorausgewählt, welche Mods überschneiden sich) und
// entscheidet am Ende selbst noch einmal (Einstellung „immer …“ gewinnt).
// Hier liegt nur die Dialog-Logik – ohne Vue, damit sie testbar bleibt.

/** Stellt der Dialog die Frage (zwei wählbare Karten)? */
export function trsAsks(offer: TrsOffer | null | undefined): boolean {
  return !!offer && offer.applies && offer.supported && offer.policy === 'ask'
}

/** Karten anzeigen – wählbar oder (ohne Build) ausgegraut mit Begründung. */
export function trsShowsChoice(offer: TrsOffer | null | undefined): boolean {
  return !!offer && offer.applies && offer.policy === 'ask'
}

/**
 * Vorauswahl: „Mit TRS Client“, außer das Pack bringt Mods mit, die sich
 * beißen, oder es gibt keinen Build. Ohne Angebot (Vorschau fehlgeschlagen)
 * gilt die Empfehlung „mit“ – der Kern prüft beim Installieren trotzdem.
 */
export function trsPreselect(offer: TrsOffer | null | undefined): boolean {
  if (!offer) return true
  return offer.supported && offer.recommended
}

/**
 * Wert für den Kern: die Wahl, wenn gefragt wurde; `null` = Kern entscheidet
 * nach Einstellung bzw. Vorauswahl (keine Frage, Vanilla, „immer …“).
 */
export function trsRequest(offer: TrsOffer | null | undefined, choice: boolean): boolean | null {
  if (!offer) return choice
  if (!offer.applies || offer.policy !== 'ask') return null
  if (!offer.supported) return false
  return choice
}

/** Mods, derentwegen „Ohne“ vorausgewählt ist (andere Clients, Minimap, HUD). */
export function trsStrongConflicts(offer: TrsOffer | null | undefined): TrsConflict[] {
  return (offer?.conflicts ?? []).filter((c) => c.kind !== 'zoom')
}

/** Mods, die nur einen Hinweis wert sind (eigener Zoom/Freelook). */
export function trsSoftConflicts(offer: TrsOffer | null | undefined): TrsConflict[] {
  return (offer?.conflicts ?? []).filter((c) => c.kind === 'zoom')
}

/**
 * Einzel-Dialog: Kommt das Angebot erst nach dem Öffnen (Vorschau lädt),
 * übernimmt die Auswahl die Vorauswahl – außer der Nutzer hat schon gewählt.
 */
export function trsAfterPreview(offer: TrsOffer | null, touched: boolean, current: boolean): boolean {
  if (offer && !offer.supported) return false
  return touched ? current : trsPreselect(offer)
}

// --- Sammel-Import: eine Frage für alle, Ausnahmen je Instanz -----------------

export interface TrsBulkItem {
  id: string
  trsClient: TrsOffer | null
}

export interface TrsBulkState {
  /** Wahl für alle: `true` = mit TRS Client. */
  all: boolean
  /** Instanzen, die anders als `all` importiert werden. */
  exceptions: Set<string>
}

/** Instanzen, die überhaupt gefragt werden. */
export function trsBulkAsking<T extends TrsBulkItem>(items: T[]): T[] {
  return items.filter((i) => trsAsks(i.trsClient))
}

/**
 * Ausnahmen zur Wahl „für alle“: Bei „mit“ bleiben Instanzen mit sich beißenden
 * Mods ohne TRS Client (abwählbar). Bei „ohne“ gibt es anfangs keine.
 */
export function trsBulkDefaultExceptions(items: TrsBulkItem[], all: boolean): Set<string> {
  if (!all) return new Set()
  return new Set(trsBulkAsking(items).filter((i) => !i.trsClient!.recommended).map((i) => i.id))
}

/** Start: „mit“ (Empfehlung) – außer keine der gefragten Instanzen passt dazu. */
export function trsBulkInit(items: TrsBulkItem[]): TrsBulkState {
  const asking = trsBulkAsking(items)
  const all = asking.length === 0 || asking.some((i) => i.trsClient!.recommended)
  return { all, exceptions: trsBulkDefaultExceptions(items, all) }
}

/** Neue Wahl für alle setzt die Ausnahmen auf den Standard zurück. */
export function trsBulkSetAll(items: TrsBulkItem[], all: boolean): TrsBulkState {
  return { all, exceptions: trsBulkDefaultExceptions(items, all) }
}

/** Häkchen „hier anders“ an/aus. */
export function trsBulkToggle(state: TrsBulkState, id: string): TrsBulkState {
  const exceptions = new Set(state.exceptions)
  if (exceptions.has(id)) exceptions.delete(id)
  else exceptions.add(id)
  return { all: state.all, exceptions }
}

/** Mit TRS Client für diese Instanz (nur für gefragte Instanzen sinnvoll). */
export function trsBulkWith(state: TrsBulkState, id: string): boolean {
  return state.exceptions.has(id) ? !state.all : state.all
}

/** Wert für den Kern beim Import dieser Instanz. */
export function trsBulkRequest(state: TrsBulkState, item: TrsBulkItem): boolean | null {
  if (!item.trsClient) return null
  if (!trsAsks(item.trsClient)) return trsRequest(item.trsClient, false)
  return trsBulkWith(state, item.id)
}
