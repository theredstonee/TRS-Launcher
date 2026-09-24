import { describe, expect, it } from 'vitest'
import { javaMajorFor } from '../app/utils/format'
import { envSchema, groupSchema, hooksSchema, javaPathSchema, updateInstanceSchema } from '../app/utils/schemas'

const baseOverrides = {
  maxMemoryMb: null,
  javaPath: null,
  jvmArgs: null,
  resolution: null,
  trsClient: null,
  boost: null,
  updateChannel: null,
  fullscreen: null,
  hooks: null,
  env: null,
  syncSeparate: [],
}

describe('javaMajorFor', () => {
  it('ordnet Versionen der Java-Hauptversion zu', () => {
    expect(javaMajorFor('1.8.9')).toBe(8)
    expect(javaMajorFor('1.16.5')).toBe(8)
    expect(javaMajorFor('1.17.1')).toBe(17)
    expect(javaMajorFor('1.20.4')).toBe(17)
    expect(javaMajorFor('1.20.5')).toBe(21)
    expect(javaMajorFor('1.21.11')).toBe(21)
    expect(javaMajorFor('26.1')).toBe(25)
    expect(javaMajorFor('25w14a')).toBe(21)
    expect(javaMajorFor('26w02a')).toBe(25)
  })
})

describe('Instanz-Einstellungen', () => {
  it('akzeptiert gültige Overrides', () => {
    const parsed = updateInstanceSchema.parse({
      name: ' Survival ',
      overrides: {
        ...baseOverrides,
        javaPath: 'C:\\Program Files\\Java\\bin\\javaw.exe',
        hooks: { preLaunch: '  echo hi ', wrapper: '', postExit: null },
        env: [{ key: 'FOO_1', value: 'bar' }],
        syncSeparate: ['options'],
        updateChannel: 'beta',
      },
    })
    expect(parsed.name).toBe('Survival')
    expect(parsed.overrides.hooks).toEqual({ preLaunch: 'echo hi', wrapper: null, postExit: null })
  })

  it('lehnt gefährliche oder ungültige Werte ab', () => {
    const bad = (overrides: Record<string, unknown>) =>
      updateInstanceSchema.safeParse({ name: 'x', overrides: { ...baseOverrides, ...overrides } }).success
    expect(bad({ javaPath: 'C:\\Windows\\System32\\cmd.exe' })).toBe(false)
    expect(bad({ javaPath: 'javaw.exe' })).toBe(false)
    expect(bad({ hooks: { preLaunch: 'echo a\r\ndel x', wrapper: null, postExit: null } })).toBe(false)
    expect(bad({ syncSeparate: ['everything'] })).toBe(false)
    expect(bad({ updateChannel: 'nightly' })).toBe(false)
    expect(bad({ maxMemoryMb: 100 })).toBe(false)
  })

  it('prüft Umgebungsvariablen, Hooks und Gruppen', () => {
    expect(envSchema.safeParse([{ key: '1ABC', value: '' }]).success).toBe(false)
    expect(envSchema.safeParse([{ key: 'A=B', value: '' }]).success).toBe(false)
    expect(envSchema.safeParse([{ key: 'path', value: 'a' }, { key: 'PATH', value: 'b' }]).success).toBe(false)
    expect(envSchema.safeParse([{ key: 'OK', value: 'x\ny' }]).success).toBe(false)
    expect(hooksSchema.safeParse({ preLaunch: 'x'.repeat(1025), wrapper: null, postExit: null }).success).toBe(false)
    expect(groupSchema.safeParse('g'.repeat(33)).success).toBe(false)
    expect(groupSchema.parse('  PvP  ')).toBe('PvP')
    expect(javaPathSchema.safeParse('D:\\jdk-21\\bin\\java.exe').success).toBe(true)
    // Linux: absoluter Pfad auf `…/bin/java`.
    expect(javaPathSchema.safeParse('/usr/lib/jvm/java-21-openjdk/bin/java').success).toBe(true)
    for (const bad of ['java', '/bin/sh', 'bin/java', 'C:\\x\\cmd.exe', '/usr/bin/java\n']) {
      expect(javaPathSchema.safeParse(bad).success, bad).toBe(false)
    }
  })
})
