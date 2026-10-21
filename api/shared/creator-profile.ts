/**
 * Creator-Profil (API.md §35.11) – Regeln für Server und Website (keine Importe): Beschreibung als reiner Text,
 * Links mit fester Art und erlaubten Hosts je Art. Die Website prüft damit schon beim Tippen, der Server entscheidet.
 */

export const PROFILE_LINK_TYPES = ['youtube', 'twitch', 'tiktok', 'discord', 'instagram', 'x', 'website'] as const
export type ProfileLinkType = (typeof PROFILE_LINK_TYPES)[number]

export interface ProfileLink {
  type: ProfileLinkType
  url: string
}

export const PROFILE_MAX_LINKS = 7
export const PROFILE_DESCRIPTION_MAX = 1500
export const PROFILE_URL_MAX = 300

/** Erlaubte Hosts je Art (auch Subdomains wie `www.` oder `m.`). `website` = jeder öffentliche Host. */
export const PROFILE_LINK_HOSTS: Record<Exclude<ProfileLinkType, 'website'>, readonly string[]> = {
  youtube: ['youtube.com', 'youtu.be'],
  twitch: ['twitch.tv'],
  tiktok: ['tiktok.com'],
  discord: ['discord.gg', 'discord.com'],
  instagram: ['instagram.com'],
  x: ['x.com', 'twitter.com'],
}

const IPV4 = /^\d{1,3}(?:\.\d{1,3}){3}$/
/** Interne/lokale Namen, die nie als Website gelten. */
const LOCAL_SUFFIXES = ['localhost', 'local', 'internal', 'lan', 'home', 'intranet', 'test', 'invalid', 'example', 'onion']

function hostMatches(host: string, domains: readonly string[]): boolean {
  return domains.some((d) => host === d || host.endsWith(`.${d}`))
}

/** Öffentlicher Host: kein IP (v4/v6), kein localhost/lokaler Name, mindestens ein Punkt, gültige Labels. */
export function isPublicHost(host: string): boolean {
  const h = host.toLowerCase().replace(/\.$/, '')
  if (!h || h.length > 253 || h.startsWith('[') || h.includes(':') || IPV4.test(h)) return false
  const labels = h.split('.')
  if (labels.length < 2) return false
  if (!labels.every((l) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(l))) return false
  // Reine Zahlen als letzte Stelle (z. B. „1.2“) sind keine Domain.
  if (/^\d+$/.test(labels.at(-1)!)) return false
  return !LOCAL_SUFFIXES.includes(labels.at(-1)!)
}

export type ProfileLinkProblem = 'invalid_url' | 'not_https' | 'wrong_host' | 'too_long'

/**
 * Link prüfen und vereinheitlichen. Nur `https:`, ohne Benutzer/Passwort und ohne eigenen Port; Host passend zur Art.
 * Ergebnis: `{ url }` (normalisiert, ohne `#…`) oder `{ problem }`.
 */
export function checkProfileLink(type: ProfileLinkType, raw: string): { url: string } | { problem: ProfileLinkProblem } {
  const s = raw.trim()
  if (s.length > PROFILE_URL_MAX) return { problem: 'too_long' }
  let u: URL
  try {
    u = new URL(s)
  } catch {
    return { problem: 'invalid_url' }
  }
  if (u.protocol !== 'https:') return { problem: 'not_https' }
  if (u.username || u.password || u.port) return { problem: 'invalid_url' }
  const host = u.hostname.toLowerCase()
  if (!isPublicHost(host)) return { problem: type === 'website' ? 'invalid_url' : 'wrong_host' }
  if (type !== 'website' && !hostMatches(host, PROFILE_LINK_HOSTS[type])) return { problem: 'wrong_host' }
  u.hash = ''
  const url = u.toString()
  return url.length > PROFILE_URL_MAX ? { problem: 'too_long' } : { url }
}

/**
 * Beschreibung säubern: Zeilenumbrüche vereinheitlichen, Tabs → Leerzeichen, unsichtbare Steuer-/Format-Zeichen
 * (z. B. Richtungs-Überschreibungen, Null-Breite) entfernen, Leerzeichen am Zeilenende weg, höchstens eine Leerzeile
 * am Stück. HTML bleibt Text – die Website zeigt die Beschreibung nie als HTML.
 */
export function cleanProfileDescription(raw: string): string {
  return raw
    .replace(/\r\n?/g, '\n')
    .replace(/\t/g, ' ')
    .replace(/[\p{Cc}\p{Cf}\p{Co}\p{Cs}]/gu, (c) => (c === '\n' ? c : ''))
    .split('\n')
    .map((l) => l.replace(/\s+$/u, ''))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

export type DescriptionBlock = { kind: 'p', text: string } | { kind: 'ul', items: string[] }

/** Beschreibung → Absätze und einfache Listen (Zeilen mit „- “, „* “ oder „• “). Nur Text, kein Markup. */
export function descriptionBlocks(text: string): DescriptionBlock[] {
  const out: DescriptionBlock[] = []
  for (const chunk of text.split(/\n{2,}/)) {
    let para: string[] = []
    let list: string[] = []
    const flushPara = () => {
      if (para.length) out.push({ kind: 'p', text: para.join('\n') })
      para = []
    }
    const flushList = () => {
      if (list.length) out.push({ kind: 'ul', items: list })
      list = []
    }
    for (const line of chunk.split('\n')) {
      const m = /^\s*[-*•]\s+(.+)$/u.exec(line)
      if (m) {
        flushPara()
        list.push(m[1]!.trim())
      } else if (line.trim()) {
        flushList()
        para.push(line.trim())
      }
    }
    flushPara()
    flushList()
  }
  return out
}
