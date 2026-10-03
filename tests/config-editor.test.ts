import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  ConfigEditError,
  ConfigParseError,
  applyChanges,
  commentHints,
  detectFormat,
  draftProblem,
  editorSupports,
  entryId,
  highlightLine,
  outOfRange,
  parseConfig,
  parseProblem,
  type ConfigDoc,
  type ConfigEntry,
  type ConfigFormat,
  type ConfigValue,
} from '../app/utils/config/editor'

const fixture = (name: string) => fs.readFileSync(path.join(__dirname, 'fixtures', 'config', name), 'utf8').replace(/\r\n/g, '\n')

function entry(doc: ConfigDoc, ...p: (string | number)[]): ConfigEntry {
  const found = doc.entries.find((e) => e.id === entryId(p))
  if (!found) throw new Error(`kein Eintrag ${p.join('.')}`)
  return found
}

/** Einen Wert Ã¤ndern; erwartet, dass sich genau `from` â†’ `to` im Text Ã¤ndert. */
function expectEdit(format: ConfigFormat, text: string, p: (string | number)[], value: ConfigValue, from: string, to: string, reread: ConfigValue = value) {
  const doc = parseConfig(format, text)
  const out = applyChanges(doc, text, { [entryId(p)]: value })
  expect(text.split(from).length, `â€ž${from}â€œ muss eindeutig sein`).toBe(2)
  expect(out).toBe(text.replace(from, to))
  // Danach ist der neue Wert gelesen.
  const again = parseConfig(format, out)
  expect(again.entries.find((e) => e.id === entryId(p))?.value).toEqual(reread)
  return out
}

describe('Formate', () => {
  it('erkennt unterstÃ¼tzte Dateien am Namen', () => {
    for (const name of ['a.toml', 'sodium-options.json', 'x.JSON5', 'server.properties', 'options.txt', 'optionsof.txt', 'optionsshaders.txt', 'x.cfg', 'config.yml', 'b.yaml', 'c.jsonc']) {
      expect(editorSupports(name), name).toBe(true)
    }
    for (const name of ['latest.log', 'notes.txt', 'mod.jar', 'json', 'pack.mcmeta', 'icon.png']) expect(editorSupports(name), name).toBe(false)
  })

  it('.cfg: Forge-Format nach Inhalt, sonst key=value', () => {
    expect(detectFormat('jetpacks.cfg', fixture('jetpacks.cfg'))).toBe('forgecfg')
    expect(detectFormat('other.cfg', 'a=1\nb=true\n')).toBe('properties')
    expect(detectFormat('options.txt', '')).toBe('options')
    expect(detectFormat('x.json5', '')).toBe('json5')
  })
})

describe('JSON (Sodium)', () => {
  const text = fixture('sodium-options.json')
  const doc = parseConfig('json', text)

  it('liest Gruppen und Typen', () => {
    expect(doc.root.children.filter((c) => c.type === 'group').map((c) => (c.type === 'group' ? c.group.title : ''))).toEqual(['quality', 'advanced', 'performance', 'notifications'])
    expect(entry(doc, 'advanced', 'cpu_render_ahead_limit')).toMatchObject({ kind: 'number', value: 3, integer: true, groups: ['advanced'] })
    expect(entry(doc, 'quality', 'enable_vignette')).toMatchObject({ kind: 'bool', value: true })
    expect(entry(doc, 'quality', 'weather_quality')).toMatchObject({ kind: 'string', value: 'DEFAULT' })
    expect(entry(doc, 'brightness')).toMatchObject({ kind: 'number', value: 0.5, integer: false })
    expect(entry(doc, 'seen')).toMatchObject({ kind: 'list', value: ['intro', 'news'], style: { list: 'multiline', indent: '    ', closeIndent: '  ' } })
  })

  it('Ã¤ndert nur den einen Wert', () => {
    expectEdit('json', text, ['advanced', 'cpu_render_ahead_limit'], 5, '"cpu_render_ahead_limit": 3', '"cpu_render_ahead_limit": 5')
    expectEdit('json', text, ['quality', 'enable_vignette'], false, '"enable_vignette": true', '"enable_vignette": false')
    expectEdit('json', text, ['quality', 'leaves_quality'], 'FAST "x"', '"leaves_quality": "DEFAULT"', '"leaves_quality": "FAST \\"x\\""')
    expectEdit('json', text, ['brightness'], 1, '"brightness": 0.5', '"brightness": 1.0')
  })

  it('Listen behalten ihren Stil', () => {
    expectEdit('json', text, ['seen'], ['intro', 'news', 'tips'], '"seen": [\n    "intro",\n    "news"\n  ]', '"seen": [\n    "intro",\n    "news",\n    "tips"\n  ]')
    expectEdit('json', text, ['ignored_shader_packs'], ['BSL'], '"ignored_shader_packs": []', '"ignored_shader_packs": ["BSL"]')
  })

  it('ohne Ã„nderung bleibt der Text gleich, auch mit CRLF', () => {
    expect(applyChanges(doc, text, { [entryId(['brightness'])]: 0.5 })).toBe(text)
    const crlf = text.replace(/\n/g, '\r\n')
    const out = applyChanges(parseConfig('json', crlf), crlf, { [entryId(['seen'])]: ['a'] })
    expect(out).toContain('"seen": [\r\n    "a"\r\n  ]')
    expect(out.replace(/\r\n/g, '').includes('\n')).toBe(false)
  })

  it('meldet Fehler mit Zeile', () => {
    expect(parseProblem('json', '{\n  "a": 1,\n  "b": \n}')).toMatchObject({ code: 'invalidValue', line: 4 })
    expect(parseProblem('json', '{\n  "a": "x\n}')).toMatchObject({ code: 'unterminatedString', line: 2 })
    expect(parseProblem('json', '{\n  "a": 1\n')).toMatchObject({ code: 'unclosed', line: 1 })
    expect(parseProblem('json', '{"a": 1, "a": 2}')).toMatchObject({ code: 'duplicateKey' })
    expect(parseProblem('json', text)).toBeNull()
  })
})

describe('JSON5', () => {
  const text = fixture('mod.json5')
  const doc = parseConfig('json5', text)

  it('Kommentare werden Hilfetext, Sonderformen bleiben', () => {
    expect(entry(doc, 'enabled')).toMatchObject({ kind: 'bool', help: 'Enables the feature' })
    expect(entry(doc, 'maxDistance')).toMatchObject({ kind: 'number', value: 64, help: 'Maximum distance\nin blocks' })
    expect(entry(doc, 'name')).toMatchObject({ kind: 'string', value: 'Steve', style: { quote: "'" } })
    expect(entry(doc, 'tags')).toMatchObject({ kind: 'list', value: ['a', 'b'] })
    expect(entry(doc, 'ratio')).toMatchObject({ kind: 'number', value: 0.5 })
    expect(entry(doc, 'hex')).toMatchObject({ kind: 'raw', value: '0xFF' })
    // Kommentar hinter einem Wert gehÃ¶rt nicht zum nÃ¤chsten SchlÃ¼ssel.
    expect(entry(doc, 'nested', 'deep').help).toBeUndefined()
  })

  it('Ã¤ndert nur den einen Wert und behÃ¤lt AnfÃ¼hrungszeichen', () => {
    expectEdit('json5', text, ['name'], "Alex's", "name: 'Steve'", "name: 'Alex\\'s'")
    expectEdit('json5', text, ['nested', 'deep'], 'y', 'deep: "x"', 'deep: "y"')
    expectEdit('json5', text, ['tags'], ['a'], "tags: ['a', 'b',]", "tags: ['a']")
    expectEdit('json5', text, ['hex'], '0x10', 'hex: 0xFF', 'hex: 0x10')
  })

  it('ungÃ¼ltiger Rohtext wird nicht gespeichert', () => {
    expect(() => applyChanges(doc, text, { [entryId(['hex'])]: '0x10,,' })).toThrow(ConfigEditError)
  })
})

describe('TOML (Forge)', () => {
  const text = fixture('forge-client.toml')
  const doc = parseConfig('toml', text)

  it('Kommentare, Bereiche und erlaubte Werte', () => {
    expect(entry(doc, 'client', 'alwaysSetupTerrainOffThread')).toMatchObject({
      kind: 'bool',
      value: false,
      help: 'Enable Forge to queue all chunk updates to the Chunk Update thread.\nMay increase FPS significantly, but may also cause weird rendering lag.',
    })
    expect(entry(doc, 'general', 'maxEntities')).toMatchObject({ kind: 'number', min: 0, max: 1000, help: 'The maximum number of entities' })
    expect(entry(doc, 'general', 'renderScale')).toMatchObject({ kind: 'number', value: 1, min: 0.1, max: 4, integer: false })
    expect(entry(doc, 'general', 'notificationMode')).toMatchObject({ kind: 'enum', value: 'TOAST', options: ['TOAST', 'CHAT', 'BOTH', 'NONE'] })
    expect(entry(doc, 'general', 'dimensionBlacklist')).toMatchObject({ kind: 'list', value: ['minecraft:the_end', 'minecraft:the_nether'] })
    expect(entry(doc, 'general', 'literal')).toMatchObject({ kind: 'string', value: 'C:\\path', style: { quote: "'" } })
    expect(entry(doc, 'general', 'inline')).toMatchObject({ kind: 'raw', value: '{ x = 1, y = "z" }' })
    expect(entry(doc, 'general', 'when')).toMatchObject({ kind: 'raw' })
    expect(entry(doc, 'general', 'big')).toMatchObject({ kind: 'number', value: 1000000 })
    expect(entry(doc, 'general', 'nested', 'threads')).toMatchObject({ kind: 'number', min: 0, groups: ['general', 'nested'] })
    expect(entry(doc, 'servers', 1, 'name')).toMatchObject({ value: 'Beta', groups: ['servers [2]'] })
    const general = doc.root.children.find((c) => c.type === 'group' && c.group.title === 'general')
    expect(general?.type === 'group' && general.group.help).toBe('General settings')
  })

  it('Ã¤ndert nur den einen Wert', () => {
    expectEdit('toml', text, ['general', 'maxEntities'], 300, 'maxEntities = 200', 'maxEntities = 300')
    expectEdit('toml', text, ['general', 'renderScale'], 2, 'renderScale = 1.0', 'renderScale = 2.0')
    expectEdit('toml', text, ['general', 'notificationMode'], 'CHAT', 'notificationMode = "TOAST"', 'notificationMode = "CHAT"')
    expectEdit('toml', text, ['general', 'literal'], 'D:\\games', "literal = 'C:\\path'", "literal = 'D:\\games'")
    expectEdit('toml', text, ['general', 'literal'], "it's", "literal = 'C:\\path'", 'literal = "it\'s"')
    expectEdit('toml', text, ['servers', 1, 'port'], 25570, 'port = 25566', 'port = 25570')
    expectEdit('toml', text, ['general', 'inline'], '{ x = 2 }', 'inline = { x = 1, y = "z" } # inline table', 'inline = { x = 2 } # inline table')
  })

  it('Listen: einzeilig und mehrzeilig mit Tabs', () => {
    expectEdit(
      'toml',
      text,
      ['general', 'dimensionBlacklist'],
      ['minecraft:the_end'],
      'dimensionBlacklist = ["minecraft:the_end", "minecraft:the_nether"]',
      'dimensionBlacklist = ["minecraft:the_end"]',
    )
    expectEdit('toml', text, ['general', 'multiLine'], ['a', 'b', 'c'], 'multiLine = [\n\t\t"a",\n\t\t"b"\n\t]', 'multiLine = [\n\t\t"a",\n\t\t"b",\n\t\t"c"\n\t]')
  })

  it('meldet Fehler mit Zeile', () => {
    expect(parseProblem('toml', 'a = 1\nb = \n')).toMatchObject({ code: 'invalidValue', line: 2 })
    expect(parseProblem('toml', 'a = "x\n')).toMatchObject({ code: 'unterminatedString', line: 1 })
    expect(parseProblem('toml', '[a\nb = 1')).toMatchObject({ code: 'unclosed', line: 1 })
    expect(parseProblem('toml', 'a = 1\na = 2')).toMatchObject({ code: 'duplicateKey', line: 2 })
    expect(parseProblem('toml', 'a = 1 2')).toMatchObject({ code: 'unexpected', line: 1 })
    expect(parseProblem('toml', 'x = [1,\n2,\n# c\n3]\n')).toBeNull()
    expect(parseProblem('toml', 's = """\nmehr\nzeilen"""\n')).toBeNull()
  })
})

describe('Forge-cfg (1.7.10â€“1.12)', () => {
  const text = fixture('jetpacks.cfg')
  const doc = parseConfig('forgecfg', text)

  it('Kategorien, Typen und Hinweise', () => {
    const general = doc.root.children[0]
    expect(general?.type === 'group' && general.group).toMatchObject({ title: 'general', help: 'General settings' })
    expect(entry(doc, 'general', 'enableHud')).toMatchObject({ kind: 'bool', value: true, help: 'Enable the jetpack HUD', defaultText: 'true' })
    expect(entry(doc, 'general', 'updateInterval')).toMatchObject({ kind: 'number', value: 20, integer: true, min: 1, max: 200, defaultText: '20' })
    expect(entry(doc, 'general', 'renderMultiplier')).toMatchObject({ kind: 'number', value: 1, integer: false, min: 0.5, max: 4 })
    expect(entry(doc, 'general', 'displayMode')).toMatchObject({ kind: 'enum', options: ['SIMPLE', 'DETAILED', 'OFF'], help: 'Display mode' })
    expect(entry(doc, 'general', 'greeting')).toMatchObject({ kind: 'string', value: 'Hello # World' })
    expect(entry(doc, 'general', 'blacklist')).toMatchObject({ kind: 'list', value: ['minecraft:bedrock', 'minecraft:barrier'], itemType: 'string' })
    expect(entry(doc, 'general', 'levels')).toMatchObject({ kind: 'list', itemType: 'number', integer: true })
    expect(entry(doc, 'general', 'client', 'Show Particles')).toMatchObject({ kind: 'bool', groups: ['general', 'client'] })
  })

  it('Ã¤ndert nur den einen Wert', () => {
    expectEdit('forgecfg', text, ['general', 'updateInterval'], 40, 'I:updateInterval=20', 'I:updateInterval=40')
    expectEdit('forgecfg', text, ['general', 'renderMultiplier'], 2, 'D:renderMultiplier=1.0', 'D:renderMultiplier=2.0')
    expectEdit('forgecfg', text, ['general', 'client', 'Show Particles'], false, 'B:"Show Particles"=true', 'B:"Show Particles"=false')
    expectEdit('forgecfg', text, ['general', 'displayMode'], 'OFF', 'S:displayMode=SIMPLE', 'S:displayMode=OFF')
  })

  it('Listen Zeile fÃ¼r Zeile', () => {
    expectEdit(
      'forgecfg',
      text,
      ['general', 'blacklist'],
      ['minecraft:bedrock', 'minecraft:barrier', 'minecraft:spawner'],
      '        minecraft:barrier\n     >',
      '        minecraft:barrier\n        minecraft:spawner\n     >',
    )
    const doc2 = parseConfig('forgecfg', text)
    expect(() => applyChanges(doc2, text, { [entryId(['general', 'blacklist'])]: [] })).not.toThrow()
    expect(draftProblem(entry(doc2, 'general', 'levels'), ['1', '2.5'])).toBe('integer')
    expect(draftProblem(entry(doc2, 'general', 'levels'), ['1', 'x'])).toBe('number')
  })

  it('meldet Fehler mit Zeile', () => {
    expect(parseProblem('forgecfg', 'general {\n    B:x=true\n')).toMatchObject({ code: 'unclosed', line: 1 })
    expect(parseProblem('forgecfg', 'general {\n    kaputt\n}')).toMatchObject({ code: 'unexpected', line: 2 })
    expect(parseProblem('forgecfg', 'a {\n S:l <\n x\n}')).toMatchObject({ code: 'unclosed', line: 2 })
  })
})

describe('options.txt', () => {
  const text = fixture('options.txt')
  const doc = parseConfig('options', text)

  it('liest key:value mit Typen', () => {
    expect(entry(doc, 'autoJump')).toMatchObject({ kind: 'bool', value: false })
    expect(entry(doc, 'renderDistance')).toMatchObject({ kind: 'number', value: 12, integer: true })
    expect(entry(doc, 'gamma')).toMatchObject({ kind: 'number', value: 0.5 })
    expect(entry(doc, 'lang')).toMatchObject({ kind: 'string', value: 'en_us' })
    expect(entry(doc, 'lastServer')).toMatchObject({ kind: 'string', value: 'mc.hypixel.net:25565' })
    expect(entry(doc, 'resourcePacks')).toMatchObject({ kind: 'list', value: ['vanilla', 'fabric', 'file/Faithful.zip'] })
  })

  it('Ã¤ndert nur den einen Wert', () => {
    expectEdit('options', text, ['renderDistance'], 16, 'renderDistance:12', 'renderDistance:16')
    expectEdit('options', text, ['fov'], 0.25, 'fov:0.0', 'fov:0.25')
    expectEdit('options', text, ['soundCategory_master'], 0, 'soundCategory_master:1.0', 'soundCategory_master:0.0')
    expectEdit('options', text, ['resourcePacks'], ['vanilla', 'fabric'], 'resourcePacks:["vanilla","fabric","file/Faithful.zip"]', 'resourcePacks:["vanilla","fabric"]')
    expectEdit('options', text, ['lang'], 'de_de', 'lang:en_us', 'lang:de_de')
  })

  it('server.properties mit Escapes und Kommentaren', () => {
    const props = '#Minecraft server properties\n#Thu Oct 02 12:00:00 CEST 2026\nmotd=A Minecraft Server\\: Test\nserver-port=25565\nonline-mode=true\nlevel-seed=\n'
    const d = parseConfig('properties', props)
    expect(entry(d, 'motd')).toMatchObject({ kind: 'string', value: 'A Minecraft Server: Test' })
    expect(entry(d, 'server-port')).toMatchObject({ kind: 'number', value: 25565 })
    expect(entry(d, 'level-seed')).toMatchObject({ kind: 'string', value: '' })
    expectEdit('properties', props, ['online-mode'], false, 'online-mode=true', 'online-mode=false')
    expectEdit('properties', props, ['level-seed'], '-123', 'level-seed=\n', 'level-seed=-123\n', -123)
    expectEdit('properties', props, ['motd'], 'Hallo\\Welt', 'motd=A Minecraft Server\\: Test', 'motd=Hallo\\\\Welt')
  })
})

describe('YAML (Plugin-Config)', () => {
  const text = fixture('plugin-config.yml')
  const doc = parseConfig('yaml', text)

  it('liest Mappings, Listen und SonderfÃ¤lle', () => {
    expect(entry(doc, 'settings', 'allow-end')).toMatchObject({ kind: 'bool', value: true, help: 'Allow players to enter the End', groups: ['settings'] })
    expect(entry(doc, 'settings', 'warn-on-overload')).toMatchObject({ kind: 'bool', value: true, style: { boolWords: ['yes', 'no'] } })
    expect(entry(doc, 'settings', 'connection-throttle')).toMatchObject({ kind: 'number', value: 4000 })
    expect(entry(doc, 'settings', 'shutdown-message')).toMatchObject({ kind: 'string', value: 'Server closed', style: { quote: "'" } })
    expect(entry(doc, 'worlds')).toMatchObject({ kind: 'list', value: ['world', 'world_nether', 'world the end'] })
    expect(entry(doc, 'motd')).toMatchObject({ kind: 'string', value: '&aWelcome &b%player%', help: 'Motd line' })
    expect(entry(doc, 'empty')).toMatchObject({ kind: 'raw', value: '' })
    expect(entry(doc, 'flow')).toMatchObject({ kind: 'list', value: ['a', 'b', 'c d'], style: { list: 'inline' } })
    expect(entry(doc, 'messages', 1, 'delay')).toMatchObject({ kind: 'number', value: 10, groups: ['messages', 'messages [2]'] })
    expect(entry(doc, 'description')).toMatchObject({ kind: 'complex' })
    expect(entry(doc, 'anchor', 'x')).toMatchObject({ kind: 'number', value: 1 })
    expect(entry(doc, 'version')).toMatchObject({ kind: 'string', value: '1.2' })
  })

  it('Ã¤ndert nur den einen Wert', () => {
    expectEdit('yaml', text, ['settings', 'allow-end'], false, 'allow-end: true', 'allow-end: false')
    expectEdit('yaml', text, ['settings', 'warn-on-overload'], false, 'warn-on-overload: yes', 'warn-on-overload: no')
    expectEdit('yaml', text, ['spawn-limits', 'monsters'], 50, 'monsters: 70', 'monsters: 50')
    expectEdit('yaml', text, ['motd'], 'Hi "you"', 'motd: "&aWelcome &b%player%"  # colors', 'motd: "Hi \\"you\\""  # colors')
    expectEdit('yaml', text, ['settings', 'minimum-api'], 'true', 'minimum-api: none', "minimum-api: 'true'")
    expectEdit('yaml', text, ['settings', 'minimum-api'], 'a: b', 'minimum-api: none', "minimum-api: 'a: b'")
    expectEdit('yaml', text, ['empty'], '5', 'empty:\n', 'empty: 5\n', 5)
    expectEdit('yaml', text, ['messages', 0, 'text'], 'hey', 'text: hello', 'text: hey')
    expectEdit('yaml', text, ['version'], '1.3', "version: '1.2'", "version: '1.3'")
  })

  it('Listen: Block und Flow', () => {
    expectEdit('yaml', text, ['worlds'], ['world', 'lobby'], '  - world\n  - world_nether\n  - "world the end"', '  - world\n  - lobby')
    expectEdit('yaml', text, ['worlds'], [], 'worlds:\n  - world\n  - world_nether\n  - "world the end"', 'worlds: []')
    expectEdit('yaml', text, ['flow'], ['a', 'x, y'], "flow: [a, b, 'c d']", "flow: [a, 'x, y']")
  })

  it('meldet Fehler mit Zeile', () => {
    expect(parseProblem('yaml', 'a: 1\n\tb: 2\n')).toMatchObject({ code: 'tab', line: 2 })
    expect(parseProblem('yaml', 'a:\n  b: 1\n   c: 2\n')).toMatchObject({ code: 'badIndent', line: 3 })
    expect(parseProblem('yaml', 'a: 1\nnur text\n')).toMatchObject({ code: 'expectedSeparator', line: 2 })
    expect(parseProblem('yaml', 'a: "offen\nb: 1\n')).toMatchObject({ code: 'unterminatedString' })
    expect(parseProblem('yaml', text)).toBeNull()
  })
})

describe('Alle Werte auf einmal ändern (LF und CRLF)', () => {
  const samples: [ConfigFormat, string][] = [
    ['json', 'sodium-options.json'],
    ['json5', 'mod.json5'],
    ['toml', 'forge-client.toml'],
    ['forgecfg', 'jetpacks.cfg'],
    ['options', 'options.txt'],
    ['yaml', 'plugin-config.yml'],
  ]
  function changed(e: ConfigEntry): ConfigValue | undefined {
    switch (e.kind) {
      case 'bool':
        return !e.value
      case 'number':
        return e.integer ? (e.value as number) + 1 : (e.value as number) + 0.5
      case 'enum':
        return e.options!.find((o) => o !== e.value)
      case 'string':
        return `${e.value as string}x`
      case 'list':
        return e.itemType === 'bool' ? undefined : [...(e.value as string[]), e.itemType === 'number' ? '7' : 'neu']
      default:
        return undefined
    }
  }
  const comments = (text: string) => text.split(/\r?\n/).map((l) => l.trim()).filter((l) => /^(#|\/\/)/.test(l))

  for (const [format, name] of samples) {
    for (const eol of ['\n', '\r\n']) {
      it(`${name} (${eol === '\n' ? 'LF' : 'CRLF'})`, () => {
        const text = fixture(name).replace(/\n/g, eol)
        const doc = parseConfig(format, text)
        const drafts: Record<string, ConfigValue> = {}
        for (const e of doc.entries) {
          const v = changed(e)
          if (v !== undefined) drafts[e.id] = v
        }
        expect(Object.keys(drafts).length).toBeGreaterThan(3)
        const out = applyChanges(doc, text, drafts)
        const again = parseConfig(format, out)
        for (const [id, v] of Object.entries(drafts)) {
          const now = again.entries.find((e) => e.id === id)!
          if (Array.isArray(v)) expect(now.value, id).toEqual(v)
          else expect(String(now.value), id).toBe(String(v))
        }
        // Kommentare bleiben, Zeilenenden auch, nichts sonst verschwindet.
        expect(comments(out)).toEqual(comments(text))
        if (eol === '\r\n') expect(out.replace(/\r\n/g, '')).not.toContain('\n')
        const untouched = doc.entries.filter((e) => !(e.id in drafts))
        for (const e of untouched) expect(out).toContain(text.slice(e.span.start, e.span.end))
      })
    }
  }
})

describe('Hinweise aus Kommentaren', () => {
  it('Forge-Varianten', () => {
    expect(commentHints(['Speed', 'Min: 1', 'Max: 10'])).toMatchObject({ help: 'Speed', min: 1, max: 10 })
    expect(commentHints(['Mode [valid values: A, B, C] [default: A]'])).toMatchObject({ help: 'Mode', options: ['A', 'B', 'C'], defaultText: 'A' })
    expect(commentHints(['Range: < 5'])).toMatchObject({ max: 5 })
    expect(commentHints([' Default: 3'])).toMatchObject({ defaultText: '3' })
  })

  it('Bereich ist nur ein Hinweis', () => {
    const doc = parseConfig('toml', fixture('forge-client.toml'))
    const max = entry(doc, 'general', 'maxEntities')
    expect(outOfRange(max, 5000)).toBe(true)
    expect(outOfRange(max, 50)).toBe(false)
    expect(draftProblem(max, 1.5)).toBe('integer')
    expect(draftProblem(max, Number.NaN)).toBe('number')
  })
})

describe('Syntaxfarben', () => {
  it('Tokens ergeben wieder genau die Zeile', () => {
    const samples: [ConfigFormat, string][] = [
      ['json', fixture('sodium-options.json')],
      ['json5', fixture('mod.json5')],
      ['toml', fixture('forge-client.toml')],
      ['forgecfg', fixture('jetpacks.cfg')],
      ['options', fixture('options.txt')],
      ['yaml', fixture('plugin-config.yml')],
      ['properties', 'motd=Hi\n! c\n'],
    ]
    for (const [format, text] of samples) {
      for (const line of text.split('\n')) expect(highlightLine(format, line).map((t) => t.text).join('')).toBe(line)
    }
  })

  it('fÃ¤rbt SchlÃ¼ssel, Werte und Kommentare', () => {
    expect(highlightLine('toml', '\tmaxEntities = 200 # x')).toEqual([
      { text: '\t', cls: 'text' },
      { text: 'maxEntities', cls: 'key' },
      { text: ' ', cls: 'text' },
      { text: '=', cls: 'punct' },
      { text: ' ', cls: 'text' },
      { text: '200', cls: 'number' },
      { text: ' ', cls: 'text' },
      { text: '# x', cls: 'comment' },
    ])
    expect(highlightLine('yaml', 'motd: "x" # c').map((t) => t.cls)).toEqual(['key', 'punct', 'text', 'string', 'text', 'comment'])
    expect(highlightLine('yaml', '  - world').map((t) => t.cls)).toEqual(['text', 'punct', 'string'])
    expect(highlightLine('forgecfg', '    B:enableHud=true').map((t) => t.cls)).toEqual(['text', 'punct', 'key', 'punct', 'bool'])
  })
})

it('ConfigParseError trÃ¤gt Code und Zeile', () => {
  const e = new ConfigParseError('tab', 3)
  expect(e.code).toBe('tab')
  expect(e.line).toBe(3)
})
