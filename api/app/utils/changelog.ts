// Liest CHANGELOG.md: je Version ein Abschnitt „## <version> – <datum>“ mit „### English“ und
// „### Deutsch“. Dieselbe Funktion nutzt der Release-Build (scripts/changelog.mjs) und der
// Launcher („Was ist neu“, Update-Karte) – daher ohne Nuxt-Abhängigkeiten.
//
// Optional trägt die Überschrift einen Update-Namen (Englisch | Deutsch):
//   ## 0.5.0 – 2026-09-25 – The Clip Update | Das Clip-Update
// Screenshots stehen als eigene Zeile „![Bildunterschrift](/news/<version>/<datei>.png)“ im Text;
// die Dateien liegen in public/news/.

export interface ChangelogEntry {
  /** z. B. „0.4.4“; der Abschnitt „Unreleased“ hat `null`. */
  version: string | null
  date: string | null
  en: string
  de: string
  /** Update-Name wie „The Clip Update“ / „Das Clip-Update“, sonst `null`. */
  title: { en: string; de: string } | null
}

const VERSION_HEADING =
  /^## +(?:\[?v?(\d+\.\d+\.\d+(?:-[\w.]+)?)\]?|(Unreleased))(?: +[–-] +(\d{4}-\d{2}-\d{2}))?(?: +[–-] +(.+?))? *$/i

/** „The Clip Update | Das Clip-Update“ → beide Namen; ohne „|“ gilt der Name für beide Sprachen. */
function parseTitle(raw: string | undefined): ChangelogEntry['title'] {
  if (!raw) return null
  const [en = '', de] = raw.split('|').map((part) => part.trim())
  if (!en) return null
  return { en, de: de || en }
}

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
      current = { version: heading[1] ?? null, date: heading[3] ?? null, en: '', de: '', title: parseTitle(heading[4]) }
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

/** Eigene Bilder eines Beitrags: nur Dateien aus public/news/, nie fremde Adressen. */
export const NEWS_IMAGE = /^!\[([^\]\n]{0,200})\]\((\/news\/[\w.-]+\/[\w.-]+\.(?:png|webp|jpe?g))\) *$/

export type PostBlock = { kind: 'text'; markdown: string } | { kind: 'image'; src: string; caption: string }

/** Teilt einen Changelog-Text in Textblöcke und eigene Screenshots (je eine Zeile „![…](/news/…)“). */
export function splitPost(markdown: string): PostBlock[] {
  const blocks: PostBlock[] = []
  let text: string[] = []
  const flush = () => {
    const md = text.join('\n').trim()
    if (md) blocks.push({ kind: 'text', markdown: md })
    text = []
  }
  for (const line of markdown.split(/\r?\n/)) {
    const image = NEWS_IMAGE.exec(line.trim())
    if (image && !image[2]!.includes('..')) {
      flush()
      blocks.push({ kind: 'image', src: image[2]!, caption: image[1]!.trim() })
    } else text.push(line)
  }
  flush()
  return blocks
}

/** Für GitHub: „/news/…“-Bilder auf die Datei im Repo zum Tag der Version zeigen lassen. */
function githubImages(markdown: string, version: string): string {
  return markdown.replace(
    /\]\((\/news\/[\w.-]+\/[\w.-]+\.(?:png|webp|jpe?g))\)/g,
    (_, path: string) => `](https://raw.githubusercontent.com/theredstonee/TRS-Launcher/v${version}/public${path})`,
  )
}

/** Text für GitHub-Release und Auto-Update: Update-Name, dann Englisch, dann Deutsch. */
export function releaseNotes(entry: ChangelogEntry): string {
  const version = entry.version ?? ''
  const en = githubImages(entry.en, version)
  const de = githubImages(entry.de, version)
  if (entry.title) return `# ${entry.title.en}\n\n${en}\n\n# ${entry.title.de}\n\n${de}\n`
  return `## What's new\n\n${en}\n\n## Neu in dieser Version\n\n${de}\n`
}

/** Fester Startwert für die Redstone-Szene eines Updates – jede Version sieht anders, aber immer gleich aus. */
export function versionSeed(version: string): number {
  let h = 0x7e5
  for (const ch of version) h = (Math.imul(h, 31) + ch.charCodeAt(0)) >>> 0
  return h % 0xffffff
}
