import type { AppContext } from './context'
import { all, run } from './db'
import { ApiError, badRequest, conflict, notFound } from './errors'
import { hmacHex } from './ids'

/**
 * Automatische Schutzfunktionen des Chats: Text säubern, Links erkennen, optionaler Wortfilter
 * (vom Admin gepflegt, ohne Regex), Spam-Bremse (Wiederholungen, zu viele Links).
 */

// ---------------------------------------------------------------- Text

/**
 * Nachrichtentext säubern: NFC, Zeilenenden vereinheitlichen, Steuer-/Bidi-/unsichtbare Zeichen
 * entfernen (ZWJ und Tag-Zeichen für Emoji-Sequenzen bleiben), Tabs → Leerzeichen, höchstens eine
 * Leerzeile am Stück, Zeilenenden und Ränder getrimmt.
 */
export function sanitizeText(raw: string): string {
  return raw
    .normalize('NFC')
    .replace(/\r\n?/g, '\n')
    .replace(/\t/g, ' ')
    .replace(/[\p{Cc}\p{Co}\u2028\u2029]/gu, (c) => (c === '\n' ? '\n' : ''))
    .replace(/\p{Cf}/gu, (c) => {
      const cp = c.codePointAt(0)!
      return cp === 0x200d || (cp >= 0xe0020 && cp <= 0xe007f) ? c : ''
    })
    .replace(/[ \u00a0]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

/** Länge in Zeichen (Codepoints), wie Nutzer sie zählen würden. */
export function textLength(s: string): number {
  let n = 0
  for (const _ of s) n++
  return n
}

export function assertText(text: string, max: number): void {
  if (textLength(text) > max) throw badRequest('message_too_long', `Messages can be at most ${max} characters`)
}

// ---------------------------------------------------------------- Links

const TLDS = 'com|net|org|de|gg|io|me|xyz|ru|co|tk|ml|ga|cf|gq|info|biz|link|app|dev|site|online|store|shop|top|club|live|tv|eu|at|ch|uk|us|es|fr|it|nl|pl|ly|to|be|cc|sh|so|gl|gift|click|fun|pw|ws|su'
const LINK_PATTERNS = [
  /\b(?:https?|ftp):\/\/[^\s<>]+/gi,
  /\bwww\.[^\s<>]+/gi,
  new RegExp(`\\b[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\\.(?:${TLDS})\\b(?:/[^\\s<>]*)?`, 'gi'),
]

/** Anzahl erkannter Links/Domains im Text (überlappende Treffer zählen einmal). */
export function countLinks(text: string): number {
  const spans: [number, number][] = []
  for (const re of LINK_PATTERNS) {
    for (const m of text.matchAll(re)) {
      spans.push([m.index, m.index + m[0].length])
      if (spans.length > 200) break
    }
  }
  spans.sort((a, b) => a[0] - b[0])
  let n = 0
  let end = -1
  for (const [a, b] of spans) {
    if (a >= end) n++
    end = Math.max(end, b)
  }
  return n
}

// ---------------------------------------------------------------- Wortfilter

export type FilterMode = 'word' | 'contains'
export type FilterAction = 'block' | 'mask'

export interface FilterEntry {
  id: number
  word: string
  mode: FilterMode
  action: FilterAction
  createdAt: string
  createdBy: string
}

const LEET: Record<string, string> = { 0: 'o', 1: 'i', 3: 'e', 4: 'a', 5: 's', 7: 't', 8: 'b', '@': 'a', $: 's' }

/** Für den Vergleich: Kleinbuchstaben, ohne Akzente, einfache Leetspeak-Ersetzung, nur a–z/0–9. */
export function normalizeWord(s: string): string {
  return s
    .normalize('NFKD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/[0134578@$]/g, (c) => LEET[c] ?? c)
    .replace(/ß/g, 'ss')
    .replace(/[^a-z0-9]/g, '')
}

const filterCache = new WeakMap<AppContext['db'], FilterEntry[]>()

export function listFilter(ctx: AppContext): FilterEntry[] {
  let list = filterCache.get(ctx.db)
  if (!list) {
    list = all<{ id: number, word: string, mode: FilterMode, action: FilterAction, created_at: number, created_by: string }>(
      ctx.db, 'SELECT * FROM chat_word_filter ORDER BY word',
    ).map((r) => ({ id: r.id, word: r.word, mode: r.mode, action: r.action, createdAt: new Date(r.created_at).toISOString(), createdBy: r.created_by }))
    filterCache.set(ctx.db, list)
  }
  return list
}

export function addFilterWord(ctx: AppContext, actor: string, raw: string, mode: FilterMode, action: FilterAction): FilterEntry {
  const word = normalizeWord(raw)
  if (word.length < 2 || word.length > 48) throw badRequest('invalid_word', 'The word must have 2–48 letters or digits after normalising')
  try {
    run(ctx.db, 'INSERT INTO chat_word_filter (word, mode, action, created_at, created_by) VALUES (?, ?, ?, ?, ?)', word, mode, action, ctx.now(), actor)
  } catch {
    throw conflict('word_exists', 'This word is already in the filter')
  }
  if (all(ctx.db, 'SELECT 1 FROM chat_word_filter').length > 2000) {
    run(ctx.db, 'DELETE FROM chat_word_filter WHERE word = ?', word)
    throw conflict('filter_full', 'The word filter can hold at most 2000 entries')
  }
  filterCache.delete(ctx.db)
  return listFilter(ctx).find((e) => e.word === word)!
}

export function removeFilterWord(ctx: AppContext, id: number): string {
  const row = all<{ word: string }>(ctx.db, 'SELECT word FROM chat_word_filter WHERE id = ?', id)[0]
  if (!row) throw notFound('word_not_found', 'No such filter entry')
  run(ctx.db, 'DELETE FROM chat_word_filter WHERE id = ?', id)
  filterCache.delete(ctx.db)
  return row.word
}

/**
 * Wendet den Wortfilter an: `block` → 422 `message_blocked`, `mask` → betroffene Wörter werden
 * durch Sternchen ersetzt. Vergleich je Wort (Leerzeichen-getrennt) nach {@link normalizeWord}.
 */
export function applyWordFilter(ctx: AppContext, text: string): string {
  const entries = listFilter(ctx)
  if (entries.length === 0 || text === '') return text
  const tokens = text.split(/(\s+)/)
  let blocked = false
  const out = tokens.map((tok) => {
    if (/^\s+$/.test(tok) || tok === '') return tok
    const n = normalizeWord(tok)
    if (!n) return tok
    for (const e of entries) {
      const hit = e.mode === 'word' ? n === e.word : n.includes(e.word)
      if (!hit) continue
      if (e.action === 'block') blocked = true
      // Satzzeichen am Rand bleiben stehen, nur der Wortkern wird ersetzt.
      const m = /^([^\p{L}\p{N}]*)(.*?)([^\p{L}\p{N}]*)$/u.exec(tok)!
      return `${m[1]}${'*'.repeat(Math.min(12, Math.max(3, textLength(m[2]!))))}${m[3]}`
    }
    return tok
  })
  if (blocked) throw new ApiError(422, 'message_blocked', 'This message contains a blocked word')
  return out.join('')
}

// ---------------------------------------------------------------- Spam-Bremse

/**
 * Merkt sich kurz (nur im RAM) Prüfsummen der letzten Texte je Konto. Gleicher Text ≥ 3× in
 * 2 Minuten (über alle Unterhaltungen) oder mehr als 5 Links in einer Nachricht = Spam.
 * Drei Spam-Treffer in 10 Minuten → automatische Stummschaltung für 10 Minuten (moderation.ts).
 */
export class SpamGuard {
  private recent = new Map<string, { h: string, t: number }[]>()
  private strikes = new Map<string, number[]>()

  constructor(
    private readonly secret: string,
    private readonly now: () => number,
  ) {}

  /** `true`, wenn der Text wie Spam aussieht (zählt ihn nicht, wenn nicht gesendet wird). */
  isSpam(uuid: string, text: string): boolean {
    if (countLinks(text) > 5) return true
    const norm = normalizeWord(text)
    if (norm.length < 4) return false
    const h = hmacHex(this.secret, `spam:${norm}`).slice(0, 16)
    const t = this.now()
    const list = (this.recent.get(uuid) ?? []).filter((x) => t - x.t < 120_000)
    const same = list.filter((x) => x.h === h).length
    list.push({ h, t })
    this.recent.set(uuid, list.slice(-30))
    return same >= 2
  }

  /** Zählt einen Spam-Treffer; Rückgabe = Treffer in den letzten 10 Minuten. */
  strike(uuid: string): number {
    const t = this.now()
    const list = (this.strikes.get(uuid) ?? []).filter((x) => t - x < 600_000)
    list.push(t)
    this.strikes.set(uuid, list)
    return list.length
  }

  forget(uuid: string): void {
    this.recent.delete(uuid)
    this.strikes.delete(uuid)
  }

  sweep(): void {
    const t = this.now()
    for (const [k, v] of this.recent) if (!v.some((x) => t - x.t < 120_000)) this.recent.delete(k)
    for (const [k, v] of this.strikes) if (!v.some((x) => t - x < 600_000)) this.strikes.delete(k)
  }
}
