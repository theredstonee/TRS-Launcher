// Liest CHANGELOG.md: je Version ein Abschnitt „## <version> – <datum>“ mit „### English“ und
// „### Deutsch“. Dieselbe Funktion nutzt der Release-Build (scripts/changelog.mjs) und der
// Launcher („Was ist neu“) – daher ohne Nuxt-Abhängigkeiten.

export interface ChangelogEntry {
  /** z. B. „0.4.4“; der Abschnitt „Unreleased“ hat `null`. */
  version: string | null
  date: string | null
  en: string
  de: string
}

const VERSION_HEADING = /^## +(?:\[?v?(\d+\.\d+\.\d+(?:-[\w.]+)?)\]?|(Unreleased))(?: +[–-] +(\d{4}-\d{2}-\d{2}))? *$/i

/** Alle Abschnitte in Dateireihenfolge (neueste zuerst, wie im Changelog). */
export function parseChangelog(text: string): ChangelogEntry[] {
  const withoutComments = text.replace(/<!--[\s\S]*?-->/g, '')
  const entries: ChangelogEntry[] = []
  let current: ChangelogEntry | null = null
  let lang: 'en' | 'de' | null = null
  const push = () => {
    if (!current) return
    current.en = current.en.trim()
    current.de = current.de.trim()
    entries.push(current)
  }
  for (const line of withoutComments.split(/\r?\n/)) {
    const heading = VERSION_HEADING.exec(line)
    if (heading) {
      push()
      current = { version: heading[1] ?? null, date: heading[3] ?? null, en: '', de: '' }
      lang = null
      continue
    }
    if (/^## /.test(line)) {
      // Andere Überschrift zweiter Ebene: nicht Teil eines Versionsabschnitts.
      push()
      current = null
      lang = null
      continue
    }
    if (!current) continue
    const sub = /^### +(.+?) *$/.exec(line)
    if (sub) {
      const name = sub[1]!.toLowerCase()
      lang = name === 'english' ? 'en' : name === 'deutsch' ? 'de' : null
      continue
    }
    if (lang) current[lang] += `${line}\n`
  }
  push()
  return entries
}

/** Der Abschnitt einer Version (ohne führendes „v“), sonst `null`. */
export function changelogFor(entries: ChangelogEntry[], version: string): ChangelogEntry | null {
  const wanted = version.replace(/^v/, '')
  return entries.find((e) => e.version === wanted) ?? null
}

/** Vergleicht „1.2.3“-Versionen; Zusätze wie „-beta“ zählen als älter als die Version ohne. */
export function compareVersions(a: string, b: string): number {
  const split = (v: string) => {
    const [core = '', pre] = v.replace(/^v/, '').split('-', 2)
    return { parts: core.split('.').map((n) => Number.parseInt(n, 10) || 0), pre: pre ?? null }
  }
  const x = split(a)
  const y = split(b)
  for (let i = 0; i < Math.max(x.parts.length, y.parts.length); i++) {
    const d = (x.parts[i] ?? 0) - (y.parts[i] ?? 0)
    if (d !== 0) return Math.sign(d)
  }
  if (x.pre === y.pre) return 0
  if (x.pre === null) return 1
  if (y.pre === null) return -1
  return x.pre < y.pre ? -1 : 1
}

/**
 * Was ist neu seit `seen`? Alle veröffentlichten Versionen > `seen` und ≤ `current`,
 * neueste zuerst, höchstens `limit` Stück.
 */
export function changesSince(entries: ChangelogEntry[], seen: string, current: string, limit = 5): ChangelogEntry[] {
  return entries
    .filter((e): e is ChangelogEntry & { version: string } => e.version !== null)
    .filter((e) => compareVersions(e.version, seen) > 0 && compareVersions(e.version, current) <= 0)
    .sort((a, b) => compareVersions(b.version, a.version))
    .slice(0, limit)
}

/** Text für GitHub-Release und Auto-Update: erst Englisch, dann Deutsch. */
export function releaseNotes(entry: ChangelogEntry): string {
  return `## What's new\n\n${entry.en}\n\n## Neu in dieser Version\n\n${entry.de}\n`
}
