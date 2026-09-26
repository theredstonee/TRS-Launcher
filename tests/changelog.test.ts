import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  SHOTS_MAX,
  changelogFor,
  changesSince,
  compareVersions,
  parseBanner,
  parseChangelog,
  parseShot,
  postContent,
  releaseNotes,
  splitPost,
  versionSeed,
} from '../app/utils/changelog'
import { checkShots, imageInfo } from '../scripts/news-shots.mjs'

const root = path.resolve(__dirname, '..')

const sample = `# Changelog

<!-- ## 9.9.9 – kommentiert, zählt nicht -->

## Unreleased

### English

- Next thing

### Deutsch

- Nächstes

## 0.4.4 – 2026-09-25

### English

- Fixed **capes**.

### Deutsch

- **Umhänge** repariert.

## [0.4.3] - 2026-09-24

### English
- Languages

### Deutsch
- Sprachen
`

describe('Changelog', () => {
  it('liest Versionen mit beiden Sprachen', () => {
    const entries = parseChangelog(sample)
    expect(entries.map((e) => e.version)).toEqual([null, '0.4.4', '0.4.3'])
    const v = changelogFor(entries, 'v0.4.4')!
    expect(v.date).toBe('2026-09-25')
    expect(v.en).toBe('- Fixed **capes**.')
    expect(v.de).toBe('- **Umhänge** repariert.')
    expect(changelogFor(entries, '9.9.9')).toBeNull()
    expect(releaseNotes(v)).toContain("## What's new\n\n- Fixed **capes**.\n\n## Neu in dieser Version\n\n- **Umhänge** repariert.")
  })

  it('liest Update-Namen und eigene Screenshots', () => {
    const post = `## 0.5.0 – 2026-09-25 – The Clip Update | Das Clip-Update

### English

- **Clips.** Save the last 30 seconds.

![Emote wheel](/news/0.5.0/emote-wheel.png)

![Fremd](https://evil.example/x.png)
![Ausbruch](/news/../secret.png)

### Deutsch

- **Clips.** Die letzten 30 Sekunden speichern.
`
    const [entry] = parseChangelog(post)
    expect(entry!.title).toEqual({ en: 'The Clip Update', de: 'Das Clip-Update' })
    const blocks = splitPost(entry!.en)
    expect(blocks[1]).toEqual({ kind: 'image', src: '/news/0.5.0/emote-wheel.png', caption: 'Emote wheel' })
    // Fremde Adressen und Pfad-Ausbrüche bleiben normaler (bereinigter) Markdown-Text.
    expect(blocks.filter((b) => b.kind === 'image')).toHaveLength(1)
    const notes = releaseNotes(entry!)
    expect(notes.startsWith('# The Clip Update')).toBe(true)
    expect(notes).toContain('# Das Clip-Update')
    expect(notes).toContain('https://raw.githubusercontent.com/theredstonee/TRS-Launcher/v0.5.0/public/news/0.5.0/emote-wheel.png')
    // Ohne „|“ gilt der Name für beide Sprachen; ohne Namen bleibt es null.
    expect(parseChangelog('## 1.0.0 – 2026-01-01 – Big One\n### English\n- a\n### Deutsch\n- b')[0]!.title).toEqual({ en: 'Big One', de: 'Big One' })
    expect(parseChangelog('## 1.0.0 – 2026-01-01\n### English\n- a\n### Deutsch\n- b')[0]!.title).toBeNull()
    expect(versionSeed('0.5.0')).toBe(versionSeed('0.5.0'))
    expect(versionSeed('0.5.0')).not.toBe(versionSeed('0.5.1'))
  })

  it('liest das Update-Banner (Akzentfarbe + Motiv) und ignoriert Unsinn', () => {
    const [a, b, c] = parseChangelog(
      [
        '## 0.4.4 – 2026-09-25 – The Clip Update | Das Clip-Update',
        '<!-- banner: accent=#FF7AB8 motif=/news/0.4.4/banner.png -->',
        '### English',
        '- A',
        '### Deutsch',
        '- A',
        '## 0.4.3 – 2026-09-24',
        '<!-- banner: accent=red motif=https://evil.example/x.png -->',
        '### English',
        '- B',
        '## 0.4.2 – 2026-09-23',
        '<!-- banner: accent=#00ff00 motif=/news/../secret.png -->',
        '### English',
        '- C',
      ].join('\n'),
    )
    expect(a!.banner).toEqual({ accent: '#ff7ab8', motif: '/news/0.4.4/banner.png' })
    expect(a!.en).toBe('- A')
    expect(b!.banner).toBeNull()
    expect(c!.banner).toEqual({ accent: '#00ff00', motif: null })
    expect(parseBanner('motif=/news/1/x.png')).toBeNull()
  })

  it('vergleicht Versionen', () => {
    expect(compareVersions('0.4.10', '0.4.9')).toBe(1)
    expect(compareVersions('v1.0.0', '1.0.0')).toBe(0)
    expect(compareVersions('1.0.0-beta', '1.0.0')).toBe(-1)
    expect(compareVersions('0.3.0', '0.4.0')).toBe(-1)
  })

  it('zeigt nur, was seit der zuletzt gesehenen Version neu ist', () => {
    const entries = parseChangelog(sample)
    expect(changesSince(entries, '0.4.2', '0.4.4').map((e) => e.version)).toEqual(['0.4.4', '0.4.3'])
    expect(changesSince(entries, '0.4.3', '0.4.4').map((e) => e.version)).toEqual(['0.4.4'])
    expect(changesSince(entries, '0.4.4', '0.4.4')).toEqual([])
    // „Unreleased“ erscheint nie im Launcher.
    expect(changesSince(entries, '0.0.1', '9.0.0').every((e) => e.version !== null)).toBe(true)
  })

  it('CHANGELOG.md hat für jede Version und die aktuelle Launcher-Version Englisch und Deutsch', () => {
    const entries = parseChangelog(readFileSync(path.join(root, 'CHANGELOG.md'), 'utf8'))
    const released = entries.filter((e) => e.version !== null)
    expect(released.length).toBeGreaterThan(0)
    for (const e of entries) {
      expect(e.en, `${e.version ?? 'Unreleased'}: English`).not.toBe('')
      expect(e.de, `${e.version ?? 'Unreleased'}: Deutsch`).not.toBe('')
    }
    for (const e of released) {
      expect(e.date, `${e.version}: Datum`).toMatch(/^\d{4}-\d{2}-\d{2}$/)
      expect(e.title, `${e.version}: Update-Name`).not.toBeNull()
      expect(e.banner?.motif, `${e.version}: Banner-Motiv`).toBeTruthy()
      expect(existsSync(path.join(root, 'public', e.banner!.motif!)), `${e.version}: ${e.banner?.motif} fehlt`).toBe(true)
      // Screenshots: gültig, vorhanden, echte PNG/WebP in vernünftiger Größe; ab 0.6.5 mindestens einer.
      expect(checkShots(e, path.join(root, 'public')), `${e.version}: Screenshots`).toEqual([])
    }
    // Die nachgetragenen Screenshots älterer Updates sind da.
    expect(changelogFor(entries, '0.5.0')!.shots.length).toBeGreaterThanOrEqual(4)
    expect(changelogFor(entries, '0.3.0')!.shots.map((s) => s.src)).toContain('/news/0.3.0/hud-editor.png')
    const { version } = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8')) as { version: string }
    expect(changelogFor(entries, version), `CHANGELOG.md braucht einen Abschnitt „## ${version} – <Datum>“`).not.toBeNull()
  })

  it('liest Screenshots aus dem Kommentar „shots:“ – mehrzeilig und einzeilig', () => {
    const [a, b, c] = parseChangelog(
      [
        '## 0.6.5 – 2026-10-01 – The Clip Update | Das Clip-Update',
        '<!-- banner: accent=#ff7ab8 motif=/news/0.6.5/banner.png -->',
        '<!-- shots:',
        '/news/0.6.5/clips.png | The clip gallery | Die Clip-Galerie',
        '/news/0.6.5/trim.webp | Only English',
        '  /news/0.6.5/plain.png  ',
        '/news/0.6.5/clips.png | doppelt | doppelt',
        'https://evil.example/x.png | fremd',
        '/news/../secret.png',
        '/news/0.6.5/x.gif',
        '/news/0.6.5/y.png | <b>html</b>',
        '/news/0.6.5/z.png | a | b | c',
        '-->',
        '### English',
        '- A',
        '### Deutsch',
        '- A',
        '## 0.6.4 – 2026-09-26',
        '<!-- shots: /news/0.6.4/a.png | A | A-de ; /news/0.6.4/b.png -->',
        '### English',
        '- B',
        '### Deutsch',
        '- B',
        '## 0.6.3 – 2026-09-26',
        'Text <!-- shots: /news/0.6.3/inline.png --> mitten in der Zeile zählt nicht',
        '### English',
        '- C',
      ].join('\n'),
    )
    expect(a!.shots).toEqual([
      { src: '/news/0.6.5/clips.png', caption: { en: 'The clip gallery', de: 'Die Clip-Galerie' } },
      { src: '/news/0.6.5/trim.webp', caption: { en: 'Only English', de: 'Only English' } },
      { src: '/news/0.6.5/plain.png', caption: null },
    ])
    expect(a!.shotIssues).toHaveLength(6)
    expect(a!.banner?.accent).toBe('#ff7ab8')
    expect(a!.en).toBe('- A')
    expect(b!.shots).toEqual([
      { src: '/news/0.6.4/a.png', caption: { en: 'A', de: 'A-de' } },
      { src: '/news/0.6.4/b.png', caption: null },
    ])
    expect(c!.shots).toEqual([])
    // Nur Deutsch angegeben → gilt für beide Sprachen; zu lange Unterschrift → ungültig.
    expect(parseShot('/news/1.0.0/a.png |  | Nur Deutsch')).toEqual({ src: '/news/1.0.0/a.png', caption: { en: 'Nur Deutsch', de: 'Nur Deutsch' } })
    expect(parseShot(`/news/1.0.0/a.png | ${'x'.repeat(200)}`)).toBeNull()
  })

  it('Galerie + Text eines Beitrags und Screenshots in den Release-Hinweisen', () => {
    const [entry] = parseChangelog(
      [
        '## 0.6.5 – 2026-10-01 – The Clip Update | Das Clip-Update',
        '<!-- shots:',
        '/news/0.6.5/a.png | Gallery | Galerie',
        '/news/0.6.5/b.png',
        '-->',
        '### English',
        '- **Clips.** Save them.',
        '',
        '![Old style](/news/0.6.5/old.png)',
        '![Same file](/news/0.6.5/a.png)',
        '### Deutsch',
        '- **Clips.** Speichern.',
      ].join('\n'),
    )
    const en = postContent(entry!, 'en')
    expect(en.markdown).toBe('- **Clips.** Save them.')
    expect(en.shots).toEqual([
      { src: '/news/0.6.5/a.png', caption: 'Gallery' },
      { src: '/news/0.6.5/b.png', caption: '' },
      { src: '/news/0.6.5/old.png', caption: 'Old style' },
    ])
    expect(postContent(entry!, 'de').shots.map((s) => s.caption)).toEqual(['Galerie', ''])
    const many = { en: '', de: '', shots: Array.from({ length: 12 }, (_, i) => ({ src: `/news/1.0.0/${i}.png`, caption: null })) }
    expect(postContent(many, 'en').shots).toHaveLength(SHOTS_MAX)
    const notes = releaseNotes(entry!)
    const raw = 'https://raw.githubusercontent.com/theredstonee/TRS-Launcher/v0.6.5/public/news/0.6.5'
    expect(notes).toContain(`![Gallery](${raw}/a.png)`)
    expect(notes).toContain(`![Galerie](${raw}/a.png)`)
    expect(notes).toContain(`![](${raw}/b.png)`)
  })

  it('Release-Check der Screenshots: Pflicht ab 0.6.5, Dateien, Format, Größe, Anzahl', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'trs-shots-'))
    try {
      mkdirSync(path.join(dir, 'news', '0.6.5'), { recursive: true })
      const png = (w: number, h: number) => {
        const b = Buffer.alloc(64)
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(b)
        b.write('IHDR', 12, 'ascii')
        b.writeUInt32BE(w, 16)
        b.writeUInt32BE(h, 20)
        return b
      }
      const webp = (w: number, h: number) => {
        const b = Buffer.alloc(64)
        b.write('RIFF', 0, 'ascii')
        b.write('WEBPVP8X', 8, 'ascii')
        b.writeUIntLE(w - 1, 24, 3)
        b.writeUIntLE(h - 1, 27, 3)
        return b
      }
      writeFileSync(path.join(dir, 'news/0.6.5/ok.png'), png(1280, 720))
      writeFileSync(path.join(dir, 'news/0.6.5/ok.webp'), webp(1920, 1080))
      writeFileSync(path.join(dir, 'news/0.6.5/tiny.png'), png(200, 100))
      writeFileSync(path.join(dir, 'news/0.6.5/fake.png'), webp(1280, 720))
      writeFileSync(path.join(dir, 'news/0.6.5/text.png'), 'kein Bild')
      expect(imageInfo(png(854, 480))).toEqual({ format: 'png', width: 854, height: 480 })
      expect(imageInfo(webp(1920, 1080))).toEqual({ format: 'webp', width: 1920, height: 1080 })
      expect(imageInfo(Buffer.from('hallo'))).toBeNull()

      const entry = (version: string, lines: string[]) =>
        parseChangelog([`## ${version} – 2026-10-01 – X | X`, '<!-- shots:', ...lines, '-->', '### English', '- a', '### Deutsch', '- a'].join('\n'))[0]!
      expect(checkShots(entry('0.6.5', ['/news/0.6.5/ok.png | A | A', '/news/0.6.5/ok.webp']), dir)).toEqual([])
      // Ab 0.6.5 Pflicht, davor freiwillig.
      expect(checkShots(entry('0.6.5', []), dir).join()).toContain('Kein Screenshot')
      expect(checkShots(entry('0.7.0', []), dir)).toHaveLength(1)
      expect(checkShots(entry('0.6.4', []), dir)).toEqual([])
      const bad = checkShots(
        entry('0.6.5', ['/news/0.6.5/missing.png', '/news/0.6.5/tiny.png', '/news/0.6.5/fake.png', '/news/0.6.5/text.png', '/news/0.6.4/other.png', 'kaputt']),
        dir,
      ).join('\n')
      expect(bad).toContain('missing.png: Datei fehlt')
      expect(bad).toContain('tiny.png: 200×100 px')
      expect(bad).toContain('fake.png: Inhalt ist WEBP')
      expect(bad).toContain('text.png: kein gültiges PNG/WebP')
      expect(bad).toContain('gehört nach /news/0.6.5/')
      expect(bad).toContain('„kaputt“ ist ungültig')
      const nine = Array.from({ length: 9 }, (_, i) => `/news/0.6.5/s${i}.png`)
      for (const src of nine) writeFileSync(path.join(dir, src), png(1280, 720))
      expect(checkShots(entry('0.6.5', nine), dir)).toEqual([`9 Screenshots – höchstens ${SHOTS_MAX} je Update.`])
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })
})
