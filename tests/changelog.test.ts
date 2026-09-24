import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { changelogFor, changesSince, compareVersions, parseChangelog, releaseNotes } from '../app/utils/changelog'

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
    for (const e of released) expect(e.date, `${e.version}: Datum`).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    const { version } = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8')) as { version: string }
    expect(changelogFor(entries, version), `CHANGELOG.md braucht einen Abschnitt „## ${version} – <Datum>“`).not.toBeNull()
  })
})
