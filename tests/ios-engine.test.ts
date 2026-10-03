import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
// @ts-expect-error – Node-Skript ohne Typdeklarationen
import { appEntry, entitlementNames, privacyFromInfoPlist, sourceJson, versionEntry } from '../scripts/ios/altstore-entry.mjs'

// Der Swift-/Objective-C-Teil lässt sich hier nicht bauen (nur macOS-CI). Diese Tests prüfen,
// dass Swift, Rust und die vendorten Amethyst-Header zusammenpassen.
const root = path.resolve(__dirname, '..')
const plugin = path.join(root, 'src-tauri/plugins/tauri-plugin-trs-game')
const read = (rel: string) => fs.readFileSync(path.join(plugin, rel), 'utf8').replace(/\r\n/g, '\n')

const snakeToCamel = (s: string) => s.replace(/_([a-z0-9])/g, (_, c: string) => c.toUpperCase())

/** Felder eines Rust-Structs (pub name: Typ). */
function rustFields(source: string, struct: string): string[] {
  const body = source.split(`pub struct ${struct} {`)[1]?.split('\n}')[0] ?? ''
  return [...body.matchAll(/^\s+pub ([a-z0-9_]+):/gm)].map((m) => snakeToCamel(m[1]!))
}

/** Felder eines Swift-Structs (let/var name:). */
function swiftFields(source: string, struct: string): string[] {
  const body = source.split(`struct ${struct}: `)[1]?.split('\n}')[0] ?? ''
  // Nur direkte Felder (zwei Leerzeichen Einzug), keine verschachtelten Typen.
  return [...body.matchAll(/^ {2}(?:let|var) ([A-Za-z0-9_]+):/gm)].map((m) => m[1]!)
}

describe('iOS-Engine: Swift ↔ Rust', () => {
  it('LaunchRequest (Swift) hat genau die Felder von EngineLaunch (Rust)', () => {
    const rust = rustFields(read('src/ios/args.rs'), 'EngineLaunch')
    const swift = swiftFields(read('ios/Sources/TrsGame/GameViewController.swift'), 'LaunchRequest')
    expect(rust.length).toBeGreaterThan(10)
    expect(swift.sort()).toEqual(rust.sort())
  })

  it('DeviceProbe (Swift) liefert alle Felder, die Rust liest', () => {
    const rustSource = read('src/ios/probe.rs')
    const swiftSource = read('ios/Sources/TrsGame/DeviceProbe.swift')
    expect(swiftFields(swiftSource, 'DeviceProbe').sort()).toEqual(rustFields(rustSource, 'DeviceProbe').sort())
    for (const [rustStruct, swiftStruct] of [
      ['JitProbe', 'Jit'],
      ['Entitlements', 'Entitlements'],
      ['MemoryProbe', 'Memory'],
      ['ScreenProbe', 'Screen'],
    ] as const) {
      const nested = swiftSource.split(`struct ${swiftStruct}: Encodable {`)[1]?.split('\n  }')[0] ?? ''
      const swift = [...nested.matchAll(/let ([A-Za-z0-9]+):/g)].map((m) => m[1]!)
      expect(swift.sort(), swiftStruct).toEqual(rustFields(rustSource, rustStruct).sort())
    }
  })

  it('jedes Swift-Ereignis ist eine Variante von EngineEvent', () => {
    const rust = read('src/ios/session.rs')
    const body = rust.split('pub enum EngineEvent {')[1]!.split('\n}')[0]!
    const variants = new Set([...body.matchAll(/^ {4}([A-Z][A-Za-z]+)/gm)].map((m) => m[1]![0]!.toLowerCase() + m[1]!.slice(1)))
    const swift = fs
      .readdirSync(path.join(plugin, 'ios/Sources/TrsGame'))
      .map((f) => read(`ios/Sources/TrsGame/${f}`))
      .join('\n')
    const sent = new Set([...swift.matchAll(/EngineEventOut\(type: "([a-zA-Z]+)"/g)].map((m) => m[1]!))
    expect(sent.size).toBeGreaterThanOrEqual(7)
    for (const type of sent) expect(variants.has(type), type).toBe(true)
  })

  it('Swift lädt jede C-Funktion, die trs_engine.h exportiert', () => {
    const header = read('ios/Engine/trs/trs_engine.h')
    const exported = [...header.matchAll(/^[a-z0-9_ *]+?\b(trs_[a-z0-9_]+)\(/gm)].map((m) => m[1]!).filter((n) => !n.endsWith('_cb'))
    const swift = read('ios/Sources/TrsGame/Engine.swift')
    const loaded = new Set([...swift.matchAll(/load\("([a-z0-9_]+)"/g)].map((m) => m[1]!))
    expect(exported.length).toBeGreaterThan(15)
    for (const name of exported) expect(loaded.has(name), name).toBe(true)
    expect(swift).toContain('static let apiVersion: Int32 = 1')
    expect(header).toContain('#define TRS_ENGINE_API_VERSION 1')
  })

  it('Fehlernamen der Engine kennt die Swift-Oberfläche', () => {
    const engine = read('ios/Engine/trs/trs_engine.m')
    const names = [...engine.matchAll(/return "([a-zA-Z]+)";/g)].map((m) => m[1]!)
    expect(names).toContain('jitRequired')
    const strings = read('ios/Sources/TrsGame/Strings.swift')
    for (const name of ['notEnoughMemory', 'runtimeBroken', 'restartRequired']) {
      expect(names).toContain(name)
      expect(strings).toContain(`case "${name}"`)
    }
    // runtimeBroken löscht die Laufzeit im Rust-Teil.
    expect(read('src/ios/engine.rs')).toContain('reason == "runtimeBroken"')
  })

  it('GLFW-Tastencodes in Swift stimmen mit glfw_keycodes.h überein', () => {
    const header = read('ios/Engine/vendor/amethyst-ios/Natives/glfw_keycodes.h')
    const defines = new Map([...header.matchAll(/#define (GLFW_(?:KEY|MOD)_[A-Z0-9_]+)\s+(0x[0-9a-fA-F]+|\d+)/g)].map((m) => [m[1]!, Number(m[2]!)]))
    // Amethyst nennt Pfeil- und Ziffernblocktasten GLFW_KEY_DPAD_* / GLFW_KEY_NUMPAD_* (gleiche Werte wie GLFW).
    const special: Record<string, string> = {
      num0: 'GLFW_KEY_0',
      kp0: 'GLFW_KEY_NUMPAD_0',
      world1: 'GLFW_KEY_WORLD_1',
      world2: 'GLFW_KEY_WORLD_2',
      right: 'GLFW_KEY_DPAD_RIGHT',
      left: 'GLFW_KEY_DPAD_LEFT',
      down: 'GLFW_KEY_DPAD_DOWN',
      up: 'GLFW_KEY_DPAD_UP',
    }
    const swift = read('ios/Sources/TrsGame/KeyMapping.swift')
    const constants = [...swift.matchAll(/static let ([a-zA-Z0-9]+) = (0x[0-9a-fA-F]+|\d+)/g)]
    expect(constants.length).toBeGreaterThan(50)
    for (const [, name, value] of constants) {
      const upper = (s: string) => s.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase()
      const glfw =
        special[name!] ??
        (name!.startsWith('mod')
          ? `GLFW_MOD_${upper(name!.slice(3))}`
          : name!.startsWith('kp')
            ? `GLFW_KEY_NUMPAD_${upper(name!.slice(2))}`
            : `GLFW_KEY_${upper(name!)}`)
      expect(defines.get(glfw), `${name} → ${glfw}`).toBe(Number(value))
    }
  })

  it('die iOS-Fehlercodes sind übersetzt', () => {
    const error = read('src/error.rs')
    const codes = ['engineMissing', 'restartRequired', 'notEnoughMemory']
    for (const code of codes) expect(error).toContain(`"game.${code}"`)
    for (const lang of ['en', 'de', 'es', 'fr', 'nl', 'pl', 'pt-BR', 'tr']) {
      const locale = JSON.parse(fs.readFileSync(path.join(root, `app/locales/${lang}.json`), 'utf8'))
      for (const code of codes) expect(locale.errors.game?.[code], `${lang} ${code}`).toBeTruthy()
    }
  })

  it('Amethyst-Commit ist überall derselbe', () => {
    const upstream = read('ios/Engine/vendor/amethyst-ios/UPSTREAM.md')
    const commit = upstream.match(/Commit: `([0-9a-f]{40})`/)?.[1]
    expect(commit).toBeTruthy()
    expect(fs.readFileSync(path.join(root, 'scripts/ios/build-engine.sh'), 'utf8')).toContain(`AMETHYST_COMMIT="${commit}"`)
    expect(fs.readFileSync(path.join(root, 'NOTICE'), 'utf8')).toContain(commit!.slice(0, 10))
  })
})

describe('AltStore/SideStore-Quelle', () => {
  const base = {
    version: '0.18.0',
    date: '2026-10-03',
    url: 'https://github.com/theredstonee/TRS-Launcher/releases/download/v0.18.0/TRS-Launcher.ipa',
    size: 123456,
    sha256: 'a'.repeat(64),
    notes: '  Neu  ',
  }

  it('übernimmt Entitlements und Datenschutz-Texte aus der IPA', () => {
    const app = appEntry({
      ...base,
      info: {
        CFBundleIdentifier: 'dev.theredstonee.trslauncher',
        CFBundleVersion: '42',
        NSCameraUsageDescription: 'QR',
        NSLocalNetworkUsageDescription: 'LAN',
        UIFileSharingEnabled: true,
      },
      entitlements: {
        'application-identifier': 'X.dev.theredstonee.trslauncher',
        'get-task-allow': true,
        'com.apple.developer.kernel.increased-memory-limit': true,
      },
    })
    expect(app.bundleIdentifier).toBe('dev.theredstonee.trslauncher')
    expect(app.appPermissions.entitlements).toEqual(['com.apple.developer.kernel.increased-memory-limit', 'get-task-allow'])
    expect(app.appPermissions.privacy).toEqual({ NSCameraUsageDescription: 'QR', NSLocalNetworkUsageDescription: 'LAN' })
    expect(app.versions[0]).toMatchObject({ version: '0.18.0', buildVersion: '42', localizedDescription: 'Neu', minOSVersion: '14.0' })
  })

  it('lehnt kaputte Angaben ab', () => {
    expect(() => versionEntry({ ...base, url: 'http://x' })).toThrow()
    expect(() => versionEntry({ ...base, sha256: 'xyz' })).toThrow()
    expect(() => versionEntry({ ...base, version: 'v1' })).toThrow()
    expect(() => versionEntry({ ...base, size: 0 })).toThrow()
    expect(() => appEntry({ ...base, info: { CFBundleIdentifier: 'com.other' }, entitlements: {} })).toThrow()
  })

  it('Hilfsfunktionen und Quelle', () => {
    expect(privacyFromInfoPlist({ NSFooUsageDescription: 1, NSBarUsageDescription: 'b', Other: 'x' })).toEqual({ NSBarUsageDescription: 'b' })
    expect(entitlementNames({ 'keychain-access-groups': [], 'get-task-allow': true })).toEqual(['get-task-allow'])
    const source = sourceJson({ name: 'x' }, { sourceURL: 'https://example.org/altstore.json' })
    expect(source.apps).toHaveLength(1)
    expect(source.sourceURL).toBe('https://example.org/altstore.json')
    expect(source.identifier).toBe('dev.theredstonee.trslauncher.source')
  })
})
