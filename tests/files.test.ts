import { describe, expect, it } from 'vitest'
import type { FileEntry } from '../app/types'
import { breadcrumbs, fileKind, filterFiles, isKnownFolder, joinPath, nameProblem, nextSelection, parentPath, sortFiles } from '../app/utils/files'

const file = (name: string, size = 0, modified: string | null = null, dir = false, created: string | null = null): FileEntry => ({ name, dir, size, created, modified })

const entries: FileEntry[] = [
  file('options.txt', 300, '2026-09-20T10:00:00Z'),
  file('mods', 0, '2026-09-25T10:00:00Z', true),
  file('Welt 10.zip', 5000, '2026-09-21T10:00:00Z'),
  file('Welt 2.zip', 9000, '2026-09-22T10:00:00Z'),
  file('config', 0, '2026-09-19T10:00:00Z', true),
  file('a.log', 300, null),
]

describe('sortFiles', () => {
  const collator = new Intl.Collator('de', { numeric: true, sensitivity: 'base' })

  it('Ordner zuerst, Namen natürlich sortiert', () => {
    expect(sortFiles(entries, { key: 'name', desc: false }, collator).map((e) => e.name)).toEqual(['config', 'mods', 'a.log', 'options.txt', 'Welt 2.zip', 'Welt 10.zip'])
  })

  it('absteigend bleiben Ordner trotzdem oben', () => {
    expect(sortFiles(entries, { key: 'name', desc: true }, collator).map((e) => e.name)).toEqual(['mods', 'config', 'Welt 10.zip', 'Welt 2.zip', 'options.txt', 'a.log'])
  })

  it('nach Größe, gleiche Größe nach Name', () => {
    expect(sortFiles(entries, { key: 'size', desc: true }, collator).map((e) => e.name)).toEqual(['mods', 'config', 'Welt 2.zip', 'Welt 10.zip', 'options.txt', 'a.log'])
  })

  it('nach Änderungsdatum, fehlende Daten zuerst (aufsteigend)', () => {
    expect(sortFiles(entries, { key: 'modified', desc: false }, collator).map((e) => e.name)).toEqual(['config', 'mods', 'a.log', 'options.txt', 'Welt 10.zip', 'Welt 2.zip'])
  })

  it('verändert die Eingabe nicht', () => {
    const copy = [...entries]
    sortFiles(entries, { key: 'size', desc: false })
    expect(entries).toEqual(copy)
  })
})

describe('Filter, Pfade und Arten', () => {
  it('filtert per Teilstring (Sonderzeichen sind kein RegExp)', () => {
    expect(filterFiles(entries, 'WELT').map((e) => e.name)).toEqual(['Welt 10.zip', 'Welt 2.zip'])
    expect(filterFiles(entries, '.*')).toEqual([])
    expect(filterFiles(entries, '  ')).toHaveLength(entries.length)
  })

  it('Brotkrumen und Pfad-Helfer', () => {
    expect(breadcrumbs('')).toEqual([])
    expect(breadcrumbs('config/trsclient')).toEqual([
      { name: 'config', path: 'config' },
      { name: 'trsclient', path: 'config/trsclient' },
    ])
    expect(joinPath('', 'mods')).toBe('mods')
    expect(joinPath('config', 'a.json')).toBe('config/a.json')
    expect(parentPath('config/trsclient')).toBe('config')
    expect(parentPath('mods')).toBe('')
  })

  it('bekannte Ordner nur im Spielordner selbst', () => {
    expect(fileKind(file('mods', 0, null, true), true)).toBe('mods')
    expect(fileKind(file('crash-reports', 0, null, true), true)).toBe('crash')
    expect(fileKind(file('mods', 0, null, true), false)).toBe('folder')
    expect(isKnownFolder('saves')).toBe(true)
    expect(isKnownFolder('folder')).toBe(false)
    expect(fileKind(file('sodium.jar'), true)).toBe('jar')
    expect(fileKind(file('2026-09-25-1.log.gz'), false)).toBe('text')
    expect(fileKind(file('level.dat'), false)).toBe('nbt')
    expect(fileKind(file('README'), false)).toBe('file')
  })

  it('prüft neue Namen wie der Kern', () => {
    expect(nameProblem('  ')).toBe('empty')
    for (const bad of ['..', 'a/b', 'a\\b', 'x:y', 'CON', 'nul.txt', 'ende.', 'a?b']) expect(nameProblem(bad), bad).toBe('invalid')
    for (const good of ['Neuer Ordner', '.minecraft', 'options.txt', 'com10']) expect(nameProblem(good), good).toBeNull()
  })
})

describe('Mehrfachauswahl', () => {
  const names = ['a', 'b', 'c', 'd', 'e']
  it('Klick wählt einzeln, Strg schaltet um, Umschalt wählt Bereiche', () => {
    let sel = nextSelection(new Set(), names, 'b', null, { shift: false, toggle: false })
    expect([...sel]).toEqual(['b'])
    sel = nextSelection(sel, names, 'd', 'b', { shift: true, toggle: false })
    expect([...sel]).toEqual(['b', 'c', 'd'])
    sel = nextSelection(sel, names, 'c', 'b', { shift: false, toggle: true })
    expect([...sel].sort()).toEqual(['b', 'd'])
    sel = nextSelection(sel, names, 'a', 'e', { shift: true, toggle: true })
    expect([...sel].sort()).toEqual(['a', 'b', 'c', 'd', 'e'])
    sel = nextSelection(sel, names, 'e', null, { shift: true, toggle: false })
    expect([...sel]).toEqual(['e'])
  })
})
