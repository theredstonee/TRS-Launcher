#!/usr/bin/env node
// Mitwirkende eines Releases – für den Dank im GitHub-Release, im „Was ist neu“-Dialog des Launchers und im Blog.
//
//   node scripts/contributors.mjs --tag v0.15.0 [--prev v0.14.0] [--repo owner/name] [--exclude a,b] [--format csv|json|lines]
//
// Bereich: vorheriges Release-Tag (v*, sonst das nächstältere per Versionsvergleich) bis --tag; ohne älteres Tag
// (erstes Release) alle Commits bis --tag. --tag darf auch ein Branch oder Commit sein (Vorschau vor dem Tag).
// Gezählt werden (als GitHub-Namen, über die GitHub-API):
//   - Autoren aller Commits im Bereich (author.login; sonst aus einer @users.noreply.github.com-Adresse),
//   - „Co-authored-by:“-Zeilen (noreply-Adresse oder eine Adresse, die im Bereich schon einem Namen gehört),
//   - Autoren der gemergten Pull Requests, deren Nummer im Commit steht („Titel (#12)“, „Merge pull request #12“).
// Nicht gezählt: der Maintainer (theredstonee, per --exclude änderbar) und Bots ([bot], dependabot, github-actions …).
// Adressen, die sich keinem GitHub-Konto zuordnen lassen, fallen weg (es gibt dann keinen Namen zum Danken).
// Ausgabe: sortiert, ohne Duplikate, Standard „a,b,c“ (leer = niemand). Token aus GITHUB_TOKEN/GH_TOKEN (optional,
// ohne gilt das kleine anonyme API-Limit). Fehler der API → Exit-Code 1 (der Release-Workflow macht dann ohne Dank weiter).

import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

/** Wird nie gedankt – der Maintainer selbst. */
export const DEFAULT_EXCLUDE = ['theredstonee']
const GITHUB_LOGIN = /^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/
const BOT_LOGINS = new Set(['dependabot', 'github-actions', 'renovate', 'dependabot-preview', 'web-flow', 'ghost'])
const NOREPLY = /^(?:\d+\+)?([A-Za-z0-9][A-Za-z0-9-]{0,38})@users\.noreply\.github\.com$/i
const RELEASE_TAG = /^v(\d+)\.(\d+)\.(\d+)(?:-([\w.]+))?$/
/** Höchstens so viele Seiten à 100 Commits bzw. PR-Abfragen – Schutz vor Endlosschleifen und API-Limit. */
const MAX_PAGES = 30
const MAX_PULLS = 300

/** Bot- oder Maintainer-Konto (oder kein gültiger GitHub-Name)? */
export function isExcluded(login, exclude = DEFAULT_EXCLUDE) {
  if (typeof login !== 'string' || !GITHUB_LOGIN.test(login.replace(/\[bot\]$/i, ''))) return true
  const lower = login.toLowerCase()
  if (lower.endsWith('[bot]') || lower.endsWith('-bot') || BOT_LOGINS.has(lower)) return true
  return exclude.some((name) => name.toLowerCase() === lower)
}

/** „12345+alice@users.noreply.github.com“ oder „alice@users.noreply.github.com“ → „alice“, sonst `null`. */
export function loginFromEmail(email) {
  return NOREPLY.exec(String(email ?? '').trim())?.[1] ?? null
}

/** Adressen aus „Co-authored-by: Name <adresse>“-Zeilen einer Commit-Nachricht (klein geschrieben). */
export function coAuthorEmails(message) {
  return [...String(message ?? '').matchAll(/^co-authored-by:[^<\n]*<([^>\n]+)>\s*$/gim)].map((m) => m[1].trim().toLowerCase())
}

/** PR-Nummern aus einer Commit-Nachricht: Squash „Titel (#12)“ in der ersten Zeile oder „Merge pull request #12 …“. */
export function prNumbers(message) {
  const subject = String(message ?? '').split('\n', 1)[0]
  const out = new Set()
  for (const m of subject.matchAll(/\(#(\d{1,7})\)/g)) out.add(Number(m[1]))
  const merge = /^Merge pull request #(\d{1,7}) /.exec(subject)
  if (merge) out.add(Number(merge[1]))
  return [...out]
}

/** Sortiert (ohne Rücksicht auf Groß-/Kleinschreibung) und ohne Duplikate. */
export function sortUnique(logins) {
  const byKey = new Map()
  for (const login of logins) if (!byKey.has(login.toLowerCase())) byKey.set(login.toLowerCase(), login)
  return [...byKey.values()].sort((a, b) => a.localeCompare(b, 'en', { sensitivity: 'base' }) || a.localeCompare(b))
}

/**
 * Mitwirkende aus GitHub-API-Antworten.
 * @param {Array<{ author?: { login?: string, type?: string } | null, commit?: { author?: { email?: string }, message?: string } }>} commits
 * @param {{ pulls?: Array<{ user?: { login?: string, type?: string } | null }>, exclude?: string[] }} [options]
 * @returns {string[]}
 */
export function contributorsFrom(commits, { pulls = [], exclude = DEFAULT_EXCLUDE } = {}) {
  // Adresse → Name, gelernt aus Commits mit verknüpftem Konto (für Co-Autoren mit normaler Adresse).
  const known = new Map()
  for (const c of commits) {
    const email = c.commit?.author?.email?.toLowerCase()
    if (email && c.author?.login) known.set(email, c.author.login)
  }
  const found = []
  const add = (login, type) => {
    if (login && type !== 'Bot' && !isExcluded(login, exclude)) found.push(login)
  }
  for (const c of commits) {
    const email = c.commit?.author?.email
    add(c.author?.login ?? loginFromEmail(email), c.author?.type)
    for (const co of coAuthorEmails(c.commit?.message)) add(loginFromEmail(co) ?? known.get(co))
  }
  for (const p of pulls) add(p.user?.login, p.user?.type)
  return sortUnique(found)
}

function parseTag(tag) {
  const m = RELEASE_TAG.exec(tag)
  return m ? { parts: [Number(m[1]), Number(m[2]), Number(m[3])], pre: m[4] ?? null } : null
}

/** Vergleicht Release-Tags „v1.2.3(-pre)“; Vorabversionen sind älter als die Version ohne Zusatz. */
export function compareTags(a, b) {
  const x = parseTag(a)
  const y = parseTag(b)
  for (let i = 0; i < 3; i++) if (x.parts[i] !== y.parts[i]) return Math.sign(x.parts[i] - y.parts[i])
  if (x.pre === y.pre) return 0
  if (x.pre === null) return 1
  if (y.pre === null) return -1
  return x.pre < y.pre ? -1 : 1
}

/**
 * Das Release-Tag vor `tag`: das höchste v-Tag, das älter ist. Ist `tag` kein Release-Tag (Branch, Commit),
 * das höchste v-Tag überhaupt. Ohne passendes Tag `null` (erstes Release). Andere Tags („updater“, „client-mod“)
 * zählen nie.
 */
export function previousTag(tags, tag) {
  const releases = tags.filter((t) => parseTag(t))
  const older = parseTag(tag) ? releases.filter((t) => compareTags(t, tag) < 0) : releases
  return older.sort(compareTags).at(-1) ?? null
}

/**
 * Holt Commits (und die PRs dazu) über die GitHub-API und gibt die Mitwirkenden zurück.
 * @param {{ repo: string, tag: string, prev: string | null, exclude?: string[], fetchJson: (url: string) => Promise<any> }} options
 *   `fetchJson` bekommt einen API-Pfad wie „/repos/o/r/compare/a...b?per_page=100&page=1“ und gibt JSON zurück –
 *   bei 404 `null`, bei anderen Fehlern wirft es.
 */
export async function collect({ repo, tag, prev, exclude = DEFAULT_EXCLUDE, fetchJson }) {
  const enc = (ref) => encodeURIComponent(ref)
  const commits = []
  for (let page = 1; page <= MAX_PAGES; page++) {
    if (prev) {
      const res = await fetchJson(`/repos/${repo}/compare/${enc(prev)}...${enc(tag)}?per_page=100&page=${page}`)
      if (!res) throw new Error(`compare ${prev}...${tag} not found`)
      const batch = res.commits ?? []
      commits.push(...batch)
      if (batch.length < 100 || commits.length >= (res.total_commits ?? 0)) break
    } else {
      const batch = await fetchJson(`/repos/${repo}/commits?sha=${enc(tag)}&per_page=100&page=${page}`)
      if (!batch) throw new Error(`commits of ${tag} not found`)
      commits.push(...batch)
      if (batch.length < 100) break
    }
  }
  const numbers = [...new Set(commits.flatMap((c) => prNumbers(c.commit?.message)))].slice(0, MAX_PULLS)
  const pulls = []
  for (const n of numbers) {
    const pull = await fetchJson(`/repos/${repo}/pulls/${n}`)
    // Nur gemergte PRs dieses Repos; „(#12)“ kann auch auf ein Issue oder ein fremdes Repo zeigen.
    if (pull?.merged_at) pulls.push(pull)
  }
  return contributorsFrom(commits, { pulls, exclude })
}

/** Ausgabe: „csv“ (Standard, „a,b“), „lines“ (eine Zeile je Name) oder „json“. */
export function formatList(logins, format = 'csv') {
  if (format === 'json') return JSON.stringify(logins)
  if (format === 'lines') return logins.join('\n')
  return logins.join(',')
}

function args(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i++) {
    const m = /^--([\w-]+)(?:=(.*))?$/.exec(argv[i])
    if (!m) throw new Error(`Unknown argument: ${argv[i]}`)
    out[m[1]] = m[2] ?? argv[++i]
  }
  return out
}

async function githubJson(pathname) {
  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN
  const res = await fetch(`https://api.github.com${pathname}`, {
    headers: {
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'trs-launcher-release',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    signal: AbortSignal.timeout(20_000),
  })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`GitHub API ${res.status} for ${pathname}`)
  return res.json()
}

function localTags() {
  try {
    return execFileSync('git', ['tag', '--list', 'v*'], { encoding: 'utf8' }).split(/\r?\n/).filter(Boolean)
  } catch {
    return []
  }
}

const isCli = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
if (isCli) {
  try {
    const opts = args(process.argv.slice(2))
    const repo = opts.repo ?? process.env.GITHUB_REPOSITORY ?? 'theredstonee/TRS-Launcher'
    if (!opts.tag) throw new Error('Usage: contributors.mjs --tag <tag|ref> [--prev <tag>] [--repo owner/name] [--exclude a,b] [--format csv|json|lines]')
    if (!/^[\w.-]+\/[\w.-]+$/.test(repo)) throw new Error(`Invalid --repo: ${repo}`)
    const prev = opts.prev ?? previousTag(localTags(), opts.tag)
    const exclude = opts.exclude !== undefined ? opts.exclude.split(',').filter(Boolean) : DEFAULT_EXCLUDE
    console.error(`Contributors ${prev ? `${prev}...${opts.tag}` : `up to ${opts.tag} (first release)`} in ${repo}`)
    const logins = await collect({ repo, tag: opts.tag, prev, exclude, fetchJson: githubJson })
    process.stdout.write(`${formatList(logins, opts.format)}\n`)
  } catch (e) {
    console.error(e instanceof Error ? e.message : String(e))
    process.exit(1)
  }
}
