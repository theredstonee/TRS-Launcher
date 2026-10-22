import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import {
  clampTimeout,
  COUNTDOWN_MS,
  createPinRecord,
  DEFAULT_TIMEOUT_S,
  IdleWatch,
  idlePhase,
  isMesseBlockedCommand,
  isPitchDemoMessage,
  isValidPin,
  MESSE,
  messeRestricted,
  parseBuiltInPin,
  parseMesseConfig,
  pickPinRecord,
  pitchUrl,
  verifyPin,
  type IdlePhase,
} from '../app/utils/messe'

const root = path.resolve(__dirname, '..')

describe('Messe: Leerlauf-Uhr', () => {
  const timeout = 90_000

  it('aktiv → Countdown (10 s, aufgerundet) → Präsentation', () => {
    expect(idlePhase(0, 0, timeout)).toEqual({ kind: 'active' })
    expect(idlePhase(0, timeout - 1, timeout)).toEqual({ kind: 'active' })
    expect(idlePhase(0, timeout, timeout)).toEqual({ kind: 'countdown', secondsLeft: 10 })
    expect(idlePhase(0, timeout + 100, timeout)).toEqual({ kind: 'countdown', secondsLeft: 10 })
    expect(idlePhase(0, timeout + 9_001, timeout)).toEqual({ kind: 'countdown', secondsLeft: 1 })
    expect(idlePhase(0, timeout + COUNTDOWN_MS, timeout)).toEqual({ kind: 'idle' })
  })

  it('Uhr rückwärts (Zeitsprung) zählt als gerade eben', () => {
    expect(idlePhase(5_000, 1_000, timeout)).toEqual({ kind: 'active' })
  })

  function setup(blocked = { value: false }) {
    let now = 0
    const phases: IdlePhase[] = []
    const onIdle = vi.fn()
    const w = new IdleWatch({ timeoutMs: () => timeout, blocked: () => blocked.value, onPhase: (p) => phases.push(p), onIdle, now: () => now })
    const advance = (ms: number) => {
      for (let t = 0; t < ms; t += 250) {
        now += 250
        w.tick()
      }
    }
    return { w, phases, onIdle, advance, blocked }
  }

  it('meldet Countdown-Sekunden und löst nach Ablauf genau einmal aus', () => {
    const { phases, onIdle, advance } = setup()
    advance(timeout - 250)
    expect(phases).toEqual([])
    advance(250)
    expect(phases.at(-1)).toEqual({ kind: 'countdown', secondsLeft: 10 })
    advance(COUNTDOWN_MS - 250)
    expect(onIdle).not.toHaveBeenCalled()
    expect(phases.at(-1)).toEqual({ kind: 'countdown', secondsLeft: 1 })
    advance(250)
    expect(onIdle).toHaveBeenCalledTimes(1)
    expect(phases.at(-1)).toEqual({ kind: 'active' })
    // danach beginnt die Uhr von vorn (keine Dauerschleife)
    advance(1_000)
    expect(onIdle).toHaveBeenCalledTimes(1)
  })

  it('jede Eingabe bricht den Countdown ab', () => {
    const { w, phases, onIdle, advance } = setup()
    advance(timeout + 3_000)
    expect(phases.at(-1)?.kind).toBe('countdown')
    w.input()
    expect(phases.at(-1)).toEqual({ kind: 'active' })
    advance(timeout - 250)
    expect(onIdle).not.toHaveBeenCalled()
  })

  it('läuft ein Spiel (blockiert), geht es nie zurück zur Präsentation', () => {
    const { onIdle, advance, blocked } = setup({ value: true })
    advance(timeout * 3)
    expect(onIdle).not.toHaveBeenCalled()
    // Spiel beendet: volle Wartezeit ab jetzt, nicht sofort
    blocked.value = false
    advance(timeout - 250)
    expect(onIdle).not.toHaveBeenCalled()
    advance(COUNTDOWN_MS + 250)
    expect(onIdle).toHaveBeenCalledTimes(1)
  })
})

describe('Messe: Nachricht aus der Präsentation', () => {
  const frame = { name: 'pitch' }
  const demo = { source: 'trs-pitch', type: 'demo' }

  it('nur vom eigenen iframe mit passender Kennung', () => {
    expect(isPitchDemoMessage({ source: frame, data: demo }, frame)).toBe(true)
    expect(isPitchDemoMessage({ source: {}, data: demo }, frame)).toBe(false)
    expect(isPitchDemoMessage({ source: null, data: demo }, null)).toBe(false)
    expect(isPitchDemoMessage({ source: undefined, data: demo }, undefined)).toBe(false)
    expect(isPitchDemoMessage({ source: frame, data: { source: 'other', type: 'demo' } }, frame)).toBe(false)
    expect(isPitchDemoMessage({ source: frame, data: { source: 'trs-pitch', type: 'next' } }, frame)).toBe(false)
    expect(isPitchDemoMessage({ source: frame, data: 'trs-pitch:demo' }, frame)).toBe(false)
    expect(isPitchDemoMessage({ source: frame, data: null }, frame)).toBe(false)
  })

  it('Adresse der Präsentation', () => {
    expect(pitchUrl('de')).toBe('/messe-pitch/index.html?mode=loop&demo=1&lang=de')
    expect(pitchUrl('en')).toContain('lang=en')
  })
})

describe('Messe: Einstellungen und PIN', () => {
  it('Leerlaufzeit begrenzt, kaputte Werte → Standard', () => {
    expect(clampTimeout(5)).toBe(20)
    expect(clampTimeout(100_000)).toBe(1800)
    expect(clampTimeout('120')).toBe(120)
    expect(clampTimeout('abc')).toBe(DEFAULT_TIMEOUT_S)
    expect(parseMesseConfig(null)).toEqual({ timeoutS: 90, lang: 'de' })
    expect(parseMesseConfig('{kaputt')).toEqual({ timeoutS: 90, lang: 'de' })
    expect(parseMesseConfig('{"timeoutS":45,"lang":"en"}')).toEqual({ timeoutS: 45, lang: 'en' })
    expect(parseMesseConfig('{"timeoutS":45,"lang":"fr"}').lang).toBe('de')
  })

  it('PIN: 4–8 Ziffern, nur gesalzener Hash gespeichert', async () => {
    expect(isValidPin('123')).toBe(false)
    expect(isValidPin('1234')).toBe(true)
    expect(isValidPin('12345678')).toBe(true)
    expect(isValidPin('123456789')).toBe(false)
    expect(isValidPin('12a4')).toBe(false)
    const record = await createPinRecord('4711')
    expect(JSON.stringify(record)).not.toContain('4711')
    expect(record.hash).toMatch(/^[0-9a-f]{64}$/)
    expect(await verifyPin('4711', record)).toBe(true)
    expect(await verifyPin('4712', record)).toBe(false)
    expect(await verifyPin('', record)).toBe(false)
    // anderes Salz → anderer Hash
    expect((await createPinRecord('4711')).hash).not.toBe(record.hash)
  })

  it('eingebaute PIN: gilt ab Werk, am Stand geänderte nur zur gleichen EXE-PIN', async () => {
    expect(parseBuiltInPin('')).toBeNull()
    expect(parseBuiltInPin('{kaputt')).toBeNull()
    const builtIn = await createPinRecord('1337')
    expect(parseBuiltInPin(JSON.stringify(builtIn))).toEqual(builtIn)
    const changed = { ...(await createPinRecord('2468')), base: builtIn.hash }
    const old = await createPinRecord('4711')
    expect(pickPinRecord(null, builtIn)).toBe(builtIn)
    expect(pickPinRecord(changed, builtIn)).toBe(changed)
    // alte PIN ohne/mit anderer Basis → neue EXE-PIN gewinnt
    expect(pickPinRecord(old, builtIn)).toBe(builtIn)
    expect(pickPinRecord({ ...old, base: 'f'.repeat(64) }, builtIn)).toBe(builtIn)
    // ohne eingebaute PIN bleibt alles wie bisher
    expect(pickPinRecord(old, null)).toBe(old)
    expect(pickPinRecord(null, null)).toBeNull()
  })
})

describe('Messe: Besucher-Sperren', () => {
  it('sperrt Löschen, Abmelden, Hochladen, Ordner/Browser und den Team-Bereich', () => {
    for (const c of ['login_browser', 'login_device_code', 'remove_account', 'delete_instance', 'share_log', 'open_data_dir', 'open_external_url', 'confirm_data_move', 'trs_delete_me', 'trs_admin_remove_event_player', 'admin_delete_word']) {
      expect(isMesseBlockedCommand(c), c).toBe(true)
    }
    for (const c of ['launch_instance', 'list_instances', 'set_active_account', 'save_settings', 'modrinth_search']) {
      expect(isMesseBlockedCommand(c), c).toBe(false)
    }
  })

  it('jeder gesperrte Befehl existiert im Frontend (keine Tippfehler)', () => {
    const backend = fs.readFileSync(path.join(root, 'app/utils/backend.ts'), 'utf8')
    const source = fs.readFileSync(path.join(root, 'app/utils/messe.ts'), 'utf8')
    const block = source.slice(source.indexOf('const BLOCKED_COMMANDS'), source.indexOf('])', source.indexOf('const BLOCKED_COMMANDS')))
    const names = [...block.matchAll(/'([a-z_]+)'/g)].map((m) => m[1]!)
    expect(names.length).toBeGreaterThan(20)
    for (const n of names) expect(backend, n).toContain(`'${n}'`)
  })

  it('in normalen Builds (und Tests) aus', () => {
    expect(MESSE).toBe(false)
    expect(messeRestricted.value).toBe(false)
  })
})

describe('Messe: Build-Konfiguration', () => {
  const base = JSON.parse(fs.readFileSync(path.join(root, 'src-tauri/tauri.conf.json'), 'utf8'))
  const messe = JSON.parse(fs.readFileSync(path.join(root, 'src-tauri/tauri.messe.conf.json'), 'utf8'))

  it('eigene Kennung, kein Updater, kein Link-Protokoll', () => {
    expect(base.identifier).toBe('dev.theredstonee.trslauncher')
    expect(messe.identifier).toBe(`${base.identifier}.messe`)
    expect(messe.productName).not.toBe(base.productName)
    expect(messe.bundle.createUpdaterArtifacts).toBe(false)
    expect(messe.plugins.updater.endpoints).toEqual([])
    expect(messe.plugins['deep-link'].desktop.schemes).toEqual([])
    expect(messe.build.beforeBuildCommand).toContain('scripts/messe/nuxt.mjs')
  })

  it('Fenster „main“ vollständig (Arrays werden beim Zusammenführen ersetzt)', () => {
    const [win] = messe.app.windows
    expect(messe.app.windows).toHaveLength(1)
    expect(win.label).toBe('main')
    expect(win.decorations).toBe(false)
    expect(win.minWidth).toBe(base.app.windows[0].minWidth)
  })

  it('iframe nur im Messe-Build erlaubt; Rechte = alle normalen + Beenden', () => {
    expect(base.app.security.csp['frame-ancestors']).toBe("'none'")
    expect(base.app.security.csp['frame-src']).toBeUndefined()
    expect(messe.app.security.csp['frame-src']).toBe("'self'")
    const caps = fs.readdirSync(path.join(root, 'src-tauri/capabilities')).map((f) => JSON.parse(fs.readFileSync(path.join(root, 'src-tauri/capabilities', f), 'utf8')).identifier)
    const listed = messe.app.security.capabilities.filter((c: unknown) => typeof c === 'string')
    expect([...listed].sort()).toEqual([...caps].sort())
    const inline = messe.app.security.capabilities.find((c: unknown) => typeof c === 'object')
    expect(inline.permissions).toEqual(['core:window:allow-destroy', 'process:allow-exit'])
    // normale Builds: kein Zerstören/Beenden aus dem Webview
    const desktop = JSON.parse(fs.readFileSync(path.join(root, 'src-tauri/capabilities/desktop.json'), 'utf8'))
    expect(desktop.permissions).not.toContain('core:window:allow-destroy')
    expect(desktop.permissions).not.toContain('process:allow-exit')
  })

  it('Präsentation nur im Messe-Build eingebunden und nicht im Git', () => {
    const nuxtConfig = fs.readFileSync(path.join(root, 'nuxt.config.ts'), 'utf8')
    expect(nuxtConfig).toMatch(/publicAssets: messe \?/)
    expect(fs.readFileSync(path.join(root, '.gitignore'), 'utf8')).toMatch(/^\.messe-pitch\/$/m)
    expect(fs.existsSync(path.join(root, 'public/messe-pitch'))).toBe(false)
  })
})
