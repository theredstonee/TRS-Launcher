/**
 * Suchsyntax des Issue-Trackers (§28.3), gemeinsam für Server und Website (reine Daten, keine Importe außer den
 * Werten): `author:alex status:geplant,in-arbeit type:bug area:client priority:hoch assignee:me tag:ui is:open
 * sort:new "freier text"`. Deutsche, englische und spanische Schlüssel/Werte werden akzeptiert. Der Parser benutzt
 * nur feste Muster – aus der Eingabe wird nie eine Regex gebaut. Unbekannte Werte landen in `errors` (die Website
 * zeigt sie an), unbekannte Schlüssel sind freier Text.
 */
import { ISSUE_AREAS, ISSUE_PRIORITIES, ISSUE_SORTS, ISSUE_STATUSES, ISSUE_TAG, ISSUE_TYPES, type IssueArea, type IssuePriority, type IssueSort, type IssueStatus, type IssueType } from './issues'

export const FILTER_MAX = 300
const MAX_TOKENS = 30
const NAME = /^[A-Za-z0-9_]{1,16}$/

export type IssueFilterKey = 'status' | 'type' | 'area' | 'priority' | 'author' | 'assignee' | 'tag' | 'is' | 'sort'

export interface IssueQuery {
  text: string
  status: IssueStatus[]
  type: IssueType[]
  area: IssueArea[]
  priority: (IssuePriority | 'none')[]
  /** Spielernamen (ohne @). */
  author: string[]
  /** Spielernamen, `me` (ich) oder `none` (niemand). */
  assignee: string[]
  tag: string[]
  /** `open` = ohne Erledigt/Abgelehnt/Duplikat, `closed` = nur diese, `all` = alle. */
  is: 'open' | 'closed' | 'all' | null
  sort: IssueSort | null
  /** Nicht verstandene Teile (`status:quatsch`), zur Anzeige. */
  errors: string[]
}

export const emptyIssueQuery = (): IssueQuery => ({
  text: '', status: [], type: [], area: [], priority: [], author: [], assignee: [], tag: [], is: null, sort: null, errors: [],
})

/** Kleinbuchstaben, Umlaute/Akzente ausgeschrieben, `-`/Leerzeichen → `_`. */
function fold(s: string): string {
  const map: Record<string, string> = { 'ä': 'ae', 'ö': 'oe', 'ü': 'ue', 'ß': 'ss', 'á': 'a', 'é': 'e', 'í': 'i', 'ó': 'o', 'ú': 'u', 'ñ': 'n' }
  let out = ''
  for (const ch of s.toLowerCase()) out += map[ch] ?? ch
  return out.replace(/[-\s]+/g, '_')
}

const KEYS: Record<string, IssueFilterKey> = {
  status: 'status', s: 'status', estado: 'status',
  type: 'type', typ: 'type', art: 'type', tipo: 'type',
  area: 'area', bereich: 'area', platform: 'area', plattform: 'area',
  priority: 'priority', prio: 'priority', prioritaet: 'priority', prioritat: 'priority', prioridad: 'priority',
  author: 'author', autor: 'author', von: 'author', by: 'author', ersteller: 'author',
  assignee: 'assignee', zustaendig: 'assignee', zustandig: 'assignee', assigned: 'assignee', responsable: 'assignee',
  tag: 'tag', tags: 'tag', label: 'tag', etiqueta: 'tag',
  is: 'is', ist: 'is', es: 'is',
  sort: 'sort', sortierung: 'sort', orden: 'sort',
}

const STATUS: Record<string, IssueStatus> = {
  open: 'open', offen: 'open', abierta: 'open', neu: 'open', new: 'open',
  planned: 'planned', geplant: 'planned', todo: 'planned', to_do: 'planned', planeada: 'planned',
  in_progress: 'in_progress', inprogress: 'in_progress', progress: 'in_progress', in_arbeit: 'in_progress', inarbeit: 'in_progress', arbeit: 'in_progress', en_curso: 'in_progress',
  in_review: 'in_review', review: 'in_review', in_pruefung: 'in_review', pruefung: 'in_review', in_prufung: 'in_review', en_revision: 'in_review',
  done: 'done', erledigt: 'done', fertig: 'done', hecha: 'done', fixed: 'done',
  rejected: 'rejected', abgelehnt: 'rejected', rechazada: 'rejected',
  duplicate: 'duplicate', duplikat: 'duplicate', duplicada: 'duplicate', dup: 'duplicate',
}
const TYPE: Record<string, IssueType> = { bug: 'bug', fehler: 'bug', error: 'bug', feature: 'feature', funktion: 'feature', wunsch: 'feature', idee: 'feature', idea: 'feature', funcion: 'feature' }
const AREA: Record<string, IssueArea> = { launcher: 'launcher', client: 'client', trs_client: 'client', mod: 'client', website: 'website', web: 'website', webseite: 'website' }
const PRIORITY: Record<string, IssuePriority | 'none'> = {
  low: 'low', niedrig: 'low', baja: 'low', medium: 'medium', mittel: 'medium', media: 'medium',
  high: 'high', hoch: 'high', alta: 'high', critical: 'critical', kritisch: 'critical', critica: 'critical',
  none: 'none', keine: 'none', ohne: 'none', ninguna: 'none',
}
const IS: Record<string, 'open' | 'closed' | 'all'> = {
  open: 'open', offen: 'open', abierta: 'open', closed: 'closed', geschlossen: 'closed', cerrada: 'closed', all: 'all', alle: 'all', todas: 'all',
}
const SORT: Record<string, IssueSort> = { top: 'top', score: 'top', beste: 'top', new: 'new', neu: 'new', neueste: 'new', nuevas: 'new', activity: 'activity', aktivitaet: 'activity', actividad: 'activity' }

/** Zerlegt in Wörter; Anführungszeichen halten Leerzeichen zusammen (`"zwei wörter"`, `tag:"x"`). */
function tokens(input: string): string[] {
  const out: string[] = []
  let cur = ''
  let quoted = false
  for (const ch of input) {
    if (ch === '"') {
      quoted = !quoted
      continue
    }
    if (!quoted && /\s/.test(ch)) {
      if (cur) out.push(cur)
      cur = ''
      if (out.length >= MAX_TOKENS) return out
      continue
    }
    cur += ch
  }
  if (cur && out.length < MAX_TOKENS) out.push(cur)
  return out
}

function pushUnique<T>(list: T[], v: T): void {
  if (!list.includes(v)) list.push(v)
}

export function parseIssueQuery(raw: string): IssueQuery {
  const q = emptyIssueQuery()
  const input = [...raw].slice(0, FILTER_MAX).join('')
  const text: string[] = []
  for (const tok of tokens(input)) {
    const colon = tok.indexOf(':')
    const key = colon > 0 ? KEYS[fold(tok.slice(0, colon))] : undefined
    if (!key) {
      text.push(tok)
      continue
    }
    const values = tok.slice(colon + 1).split(',').map((v) => v.trim()).filter(Boolean)
    if (!values.length) continue
    for (const v of values) {
      const f = fold(v)
      let ok = true
      switch (key) {
        case 'status':
          if (STATUS[f]) pushUnique(q.status, STATUS[f]!)
          else ok = false
          break
        case 'type':
          if (TYPE[f]) pushUnique(q.type, TYPE[f]!)
          else ok = false
          break
        case 'area':
          if (AREA[f]) pushUnique(q.area, AREA[f]!)
          else ok = false
          break
        case 'priority':
          if (PRIORITY[f]) pushUnique(q.priority, PRIORITY[f]!)
          else ok = false
          break
        case 'author': {
          const n = v.replace(/^@/, '')
          if (NAME.test(n)) pushUnique(q.author, n)
          else ok = false
          break
        }
        case 'assignee': {
          const n = v.replace(/^@/, '')
          if (['me', 'ich', 'yo'].includes(f)) pushUnique(q.assignee, 'me')
          else if (['none', 'niemand', 'nadie', 'keiner'].includes(f)) pushUnique(q.assignee, 'none')
          else if (NAME.test(n)) pushUnique(q.assignee, n)
          else ok = false
          break
        }
        case 'tag':
          if (ISSUE_TAG.test(v.toLowerCase())) pushUnique(q.tag, v.toLowerCase())
          else ok = false
          break
        case 'is':
          if (IS[f]) q.is = IS[f]!
          else ok = false
          break
        case 'sort':
          if (SORT[f]) q.sort = SORT[f]!
          else ok = false
          break
      }
      if (!ok && q.errors.length < 10) q.errors.push(`${tok.slice(0, colon)}:${v}`.slice(0, 60))
    }
  }
  q.text = text.join(' ').slice(0, 80)
  return q
}

// ---------------------------------------------------------------- Schreiben (Aufklapp-Filter → Feld)

type Lang = 'en' | 'de' | 'es'
const OUT_KEYS: Record<'en' | 'de', Record<IssueFilterKey, string>> = {
  en: { status: 'status', type: 'type', area: 'area', priority: 'priority', author: 'author', assignee: 'assignee', tag: 'tag', is: 'is', sort: 'sort' },
  de: { status: 'status', type: 'typ', area: 'bereich', priority: 'prio', author: 'autor', assignee: 'zustaendig', tag: 'tag', is: 'ist', sort: 'sort' },
}
const OUT_VALUES_DE: Record<string, string> = {
  open: 'offen', planned: 'geplant', in_progress: 'in-arbeit', in_review: 'in-pruefung', done: 'erledigt', rejected: 'abgelehnt', duplicate: 'duplikat',
  bug: 'bug', feature: 'feature', launcher: 'launcher', client: 'client', website: 'website',
  low: 'niedrig', medium: 'mittel', high: 'hoch', critical: 'kritisch', none: 'keine',
  closed: 'geschlossen', all: 'alle', me: 'ich', top: 'top', new: 'neu', activity: 'aktivitaet',
}

function outValue(v: string, lang: Lang): string {
  if (lang === 'de') return OUT_VALUES_DE[v] ?? v
  return v.replace(/_/g, '-')
}

/** Feld-Text aus einer Abfrage (Reihenfolge fest, freier Text zuletzt). Deutsch → deutsche Schlüssel, sonst englisch. */
export function formatIssueQuery(q: IssueQuery, lang: Lang = 'en'): string {
  const keys = OUT_KEYS[lang === 'de' ? 'de' : 'en']
  const parts: string[] = []
  const add = (k: IssueFilterKey, vals: readonly string[], translate = true) => {
    if (vals.length) parts.push(`${keys[k]}:${vals.map((v) => (translate ? outValue(v, lang) : v)).join(',')}`)
  }
  add('status', q.status)
  add('type', q.type)
  add('area', q.area)
  add('priority', q.priority)
  add('author', q.author, false)
  add('assignee', q.assignee.map((a) => (a === 'me' || a === 'none' ? outValue(a, lang) : a)), false)
  add('tag', q.tag, false)
  if (q.is) add('is', [q.is])
  if (q.sort) add('sort', [q.sort])
  const text = q.text.trim()
  if (text) parts.push(/\s/.test(text) && !text.includes('"') ? `"${text}"` : text)
  return parts.join(' ')
}

/** Ist ein Wert in der Liste gesetzt? (für die Häkchen der Aufklapp-Filter) */
export const STATUS_VALUES = ISSUE_STATUSES
export const TYPE_VALUES = ISSUE_TYPES
export const AREA_VALUES = ISSUE_AREAS
export const PRIORITY_VALUES = [...ISSUE_PRIORITIES, 'none'] as const
export const SORT_VALUES = ISSUE_SORTS
