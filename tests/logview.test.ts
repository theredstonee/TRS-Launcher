import { describe, expect, it } from 'vitest'
import type { LogLine } from '../app/types'
import {
  LogModel,
  LogTextParser,
  MAX_ENTRIES,
  cleanText,
  entryMatches,
  highlightMatches,
  isContinuation,
  levelGroup,
  parseLogText,
  parseTextLine,
} from '../app/utils/logview'

const line = (message: string, level: LogLine['level'] = 'info', thread: string | null = 'Render thread'): LogLine => ({ time: 1_700_000_000_000, level, thread, message })

describe('Zeilen aus Log-Dateien', () => {
  it('erkennt Vanilla-, Forge- und Fabric-Präfixe', () => {
    const vanilla = parseTextLine('[12:34:56] [Render thread/WARN]: Missing sound for event')!
    expect(vanilla).toMatchObject({ level: 'warn', thread: 'Render thread', clock: '12:34:56', message: 'Missing sound for event' })

    const forge = parseTextLine('[25Sep2026 12:34:56.123] [main/INFO] [cpw.mods.modlauncher.Launcher/MODLAUNCHER]: ModLauncher running')!
    expect(forge).toMatchObject({ level: 'info', thread: 'main', clock: '12:34:56', message: 'ModLauncher running' })

    const fabric = parseTextLine('[12:00:01] [main/ERROR] (FabricLoader) Mod resolution failed')!
    expect(fabric).toMatchObject({ level: 'error', message: '(FabricLoader) Mod resolution failed' })

    const legacy = parseTextLine('2013-01-01 12:00:00 [CLIENT] [SEVERE] Unable to launch')!
    expect(legacy).toMatchObject({ level: 'error', clock: '12:00:00', message: 'Unable to launch' })
  })

  it('Stacktrace-Zeilen gehören zur vorigen Meldung', () => {
    expect(parseTextLine('\tat net.minecraft.client.Main.main(Main.java:1)')).toBeNull()
    expect(parseTextLine('Caused by: java.lang.NullPointerException')).toBeNull()
    expect(parseTextLine('\t... 12 more')).toBeNull()
    expect(isContinuation('java.lang.IllegalStateException: kaputt')).toBe(true)
    expect(isContinuation('Setting user: Steve')).toBe(false)

    const entries = parseLogText(
      [
        '[12:00:00] [main/INFO]: Start',
        '[12:00:01] [Render thread/ERROR]: Absturz!',
        'java.lang.RuntimeException: boom',
        '\tat a.b.C.d(C.java:1)',
        'Caused by: java.lang.NullPointerException',
        '\t... 3 more',
        '[12:00:02] [main/INFO]: weiter',
      ].join('\r\n'),
    )
    expect(entries).toHaveLength(3)
    expect(entries[1]!.message).toBe('Absturz!')
    expect(entries[1]!.detail).toEqual(['java.lang.RuntimeException: boom', '\tat a.b.C.d(C.java:1)', 'Caused by: java.lang.NullPointerException', '\t... 3 more'])
  })

  it('liest log4j-XML (Launcher-Mitschnitt) auch über mehrere Zeilen und Stücke', () => {
    const parser = new LogTextParser()
    const out: ReturnType<typeof parseLogText> = []
    parser.feed('<log4j:Event logger="x" timestamp="1700000000123" level="ERROR" thread="main">\n  <log4j:Message><![CDATA[Bo', out)
    parser.feed('om]]></log4j:Message>\n  <log4j:Throwable><![CDATA[java.lang.Error: x\n\tat a.b(C.java:1)\n]]></log4j:Throwable>\n</log4j:Event>\n', out)
    parser.flush(out)
    expect(out).toHaveLength(1)
    expect(out[0]).toMatchObject({ level: 'error', thread: 'main', time: 1_700_000_000_123, message: 'Boom' })
    expect(out[0]!.detail).toEqual(['java.lang.Error: x', '\tat a.b(C.java:1)'])
  })

  it('entfernt ANSI- und §-Farbcodes', () => {
    expect(cleanText('\u001b[31mrot\u001b[0m und §6gold§r')).toBe('rot und gold')
    expect(parseTextLine('[12:00:00] [main/INFO]: §aGrün\u001b[1m!')!.message).toBe('Grün!')
    expect(cleanText('tab\tbleibt')).toBe('tab\tbleibt')
  })

  it('Absturzberichte ohne Präfix werden zu Info-Einträgen mit Folgezeilen', () => {
    const entries = parseLogText('---- Minecraft Crash Report ----\nDescription: Rendering overlay\n\njava.lang.NullPointerException: x\n\tat foo.Bar.baz(Bar.java:5)\n')
    expect(entries.map((e) => e.message)).toEqual(['---- Minecraft Crash Report ----', 'Description: Rendering overlay'])
    expect(entries[1]!.detail).toHaveLength(2)
    expect(entries.every((e) => e.level === 'info')).toBe(true)
  })
})

describe('Filter und Suche', () => {
  it('ordnet Stufen den Filtern zu', () => {
    expect(levelGroup('fatal')).toBe('error')
    expect(levelGroup('warn')).toBe('warn')
    expect(levelGroup('debug')).toBe('info')
  })

  it('sucht ohne RegExp und groß/klein-unabhängig, auch im Stacktrace', () => {
    const [entry] = parseLogText('[1:00:00] [main/ERROR]: Fehler (x+y)\n\tat Klasse.[Methode](Datei.java:1)')
    expect(entryMatches(entry!, 'all', 'x+y')).toBe(true)
    expect(entryMatches(entry!, 'all', '[methode]')).toBe(true)
    expect(entryMatches(entry!, 'warn', '')).toBe(false)
    expect(entryMatches(entry!, 'error', 'nicht da')).toBe(false)
  })

  it('markiert alle Treffer', () => {
    expect(highlightMatches('Mod mod MOD', 'mod')).toEqual([
      { text: 'Mod', hit: true },
      { text: ' ', hit: false },
      { text: 'mod', hit: true },
      { text: ' ', hit: false },
      { text: 'MOD', hit: true },
    ])
    expect(highlightMatches('abc', '')).toEqual([{ text: 'abc', hit: false }])
    expect(highlightMatches('abc', 'x')).toEqual([{ text: 'abc', hit: false }])
  })
})

describe('LogModel', () => {
  it('zählt Stufen und filtert inkrementell', () => {
    const model = new LogModel()
    model.appendLines([line('a'), line('b', 'warn'), line('c', 'error'), line('d', 'fatal')])
    expect(model.counts).toEqual({ all: 4, error: 2, warn: 1, info: 1 })
    model.setFilter('error', '')
    expect(model.rows).toHaveLength(2)
    // Neue Zeilen werden nur geprüft, nicht alles neu gebaut.
    model.appendLines([line('e', 'error'), line('f')])
    expect(model.rows.map((r) => model.entries[r.entry]!.message)).toEqual(['c', 'd', 'e'])
    expect(model.counts.all).toBe(6)
    model.setFilter('all', 'E')
    expect(model.visibleEntries).toBe(1)
  })

  it('hängt stderr-Stacktraces an die Meldung davor und klappt sie auf', () => {
    const model = new LogModel()
    model.appendLines([
      line('Exception in thread main', 'error', null),
      line('java.lang.IllegalStateException: x', 'error', null),
      line('\tat a.B.c(B.java:1)', 'error', null),
      line('Throwable im Event\njava.lang.Error: y\n\tat d.E.f(E.java:2)', 'error', 'main'),
    ])
    expect(model.entries).toHaveLength(2)
    expect(model.entries[0]!.detail).toHaveLength(2)
    expect(model.entries[1]!.detail).toHaveLength(2)
    expect(model.rows).toHaveLength(2)

    model.toggle(0)
    expect(model.rows).toHaveLength(4)
    expect(model.rows[1]).toEqual({ entry: 0, line: 0 })
    // Aufgeklappt + neue Folgezeile am Ende → erscheint sofort.
    model.toggle(0)
    model.toggle(1)
    model.appendLines([line('\tat g.H.i(H.java:3)', 'error', null)])
    expect(model.entries[1]!.detail).toHaveLength(3)
    expect(model.rows).toHaveLength(5)
    model.setAllExpanded(false)
    expect(model.rows).toHaveLength(2)
  })

  it('kopiert sichtbare Einträge mit Folgezeilen', () => {
    const model = new LogModel()
    model.replace(parseLogText('[1:02:03] [main/ERROR]: Boom\n\tat x.Y(Y.java:1)\n[1:02:04] [main/INFO]: ok'))
    model.setFilter('error', '')
    expect(model.toText((e) => e.clock ?? '')).toBe('[1:02:03] [main/ERROR] Boom\n\tat x.Y(Y.java:1)')
  })

  it('bleibt bei 100 000+ Zeilen schnell und begrenzt', () => {
    const model = new LogModel()
    const batch = Array.from({ length: 1000 }, (_, i) => line(`Zeile ${i}`, i % 10 === 0 ? 'warn' : 'info'))
    const started = performance.now()
    for (let i = 0; i < 170; i++) model.appendLines(batch)
    const elapsed = performance.now() - started
    expect(model.entries.length).toBeLessThanOrEqual(MAX_ENTRIES + 10_000)
    expect(model.entries.length).toBeGreaterThanOrEqual(MAX_ENTRIES)
    model.setFilter('warn', 'zeile 99')
    expect(model.rows.length).toBeGreaterThan(0)
    // Großzügig – soll nur grobe Ausreißer (quadratische Laufzeit) fangen.
    expect(elapsed).toBeLessThan(5000)
  })
})
