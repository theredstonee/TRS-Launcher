import { readFileSync } from 'node:fs'
import path from 'node:path'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { parseChangelog } from '../server/lib/changelog'
import { blogPost, blogPosts } from '../server/lib/site'

const RAW = 'https://raw.githubusercontent.com/theredstonee/TRS-Launcher/main/public'

const CHANGELOG = `# Changelog

<!--
How to write an entry: ein Kommentar, der nichts bedeutet.
-->

## Unreleased

### English

- Next

### Deutsch

- Nächstes

## 0.6.5 – 2026-10-01 – The Clip Update | Das Clip-Update
<!-- banner: accent=#ff7ab8 motif=/news/0.6.5/banner.png -->
<!-- shots:
/news/0.6.5/gallery.png | The clip gallery | Die Clip-Galerie
/news/0.6.5/trim.png
https://evil.example/x.png | fremd
-->

### English

- **Clip gallery.** All clips as tiles.

![Old style picture](/news/0.6.5/old.png)

### Deutsch

- **Clip-Galerie.** Alle Clips als Kacheln.

## 0.6.4 – 2026-09-26 – The Workshop Update | Das Werkstatt-Update
<!-- banner: accent=#d99a5b motif=/news/0.6.4/banner.png -->

### English

- **Tabs.** New instance page.

### Deutsch

- **Tabs.** Neue Instanzseite.
`

describe('Blog aus CHANGELOG.md', () => {
  beforeAll(() => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(CHANGELOG, { status: 200 })))
  })
  afterAll(() => {
    vi.unstubAllGlobals()
  })

  it('liefert Galerie (Kommentar „shots:“ + ältere Bildzeilen) mit absoluten Adressen und Text ohne Bildzeilen', async () => {
    const post = await blogPost('0.6.5')
    expect(post).not.toBeNull()
    expect(post!.gallery.en).toEqual([
      { src: `${RAW}/news/0.6.5/gallery.png`, caption: 'The clip gallery' },
      { src: `${RAW}/news/0.6.5/trim.png`, caption: '' },
      { src: `${RAW}/news/0.6.5/old.png`, caption: 'Old style picture' },
    ])
    expect(post!.gallery.de.map((s) => s.caption)).toEqual(['Die Clip-Galerie', ''])
    expect(post!.markdown.en).toBe('- **Clip gallery.** All clips as tiles.')
    expect(post!.markdown.de).toBe('- **Clip-Galerie.** Alle Clips als Kacheln.')
    expect(post!.banner).toEqual({ accent: '#ff7ab8', motif: `${RAW}/news/0.6.5/banner.png` })
    // Nie fremde Bildadressen.
    expect(JSON.stringify(post)).not.toContain('evil.example')
  })

  it('Übersicht: jede Version mit Galerie (auch leer), ohne „Unreleased“', async () => {
    const posts = await blogPosts()
    expect(posts.map((p) => p.version)).toEqual(['0.6.5', '0.6.4'])
    expect(posts[0]!.gallery.en).toHaveLength(3)
    expect(posts[1]!.gallery).toEqual({ en: [], de: [] })
    expect(await blogPost('9.9.9')).toBeNull()
  })

  it('der Parser liest den Kommentar „shots:“ und meldet ungültige Einträge', () => {
    const [, entry] = parseChangelog(CHANGELOG)
    expect(entry!.shots).toHaveLength(2)
    expect(entry!.shotIssues).toEqual(['https://evil.example/x.png | fremd'])
  })

  it('beide Kopien des Changelog-Parsers sind gleich (Quelle: Launcher app/utils/changelog.ts)', () => {
    const root = path.resolve(__dirname, '..')
    const server = readFileSync(path.join(root, 'server/lib/changelog.ts'), 'utf8')
    const app = readFileSync(path.join(root, 'app/utils/changelog.ts'), 'utf8')
    expect(app).toBe(server)
  })
})
