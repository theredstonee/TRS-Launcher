import { describe, expect, it } from 'vitest'
import { liveEventSchema } from '../app/utils/chat'
import { normalizePackCode, packVersionLine, sharePackOutcomeSchema, sharedPackSchema } from '../app/utils/packs'

const pack = {
  id: 'Qm9vLWJhei1xdXV4LTEyMw',
  code: 'TRS-7K2M-Q9XA',
  url: 'https://trs-launcher.theredstonee.de/p/TRS-7K2M-Q9XA',
  name: 'Redstone Pack',
  summary: null,
  packVersion: '1.0.0',
  revision: 2,
  mcVersion: '1.21.1',
  loader: { kind: 'fabric', version: '0.16.9' },
  modrinthFiles: 12,
  ownJars: 0,
  otherFiles: 4,
  bytes: 1000,
  sha256: 'a'.repeat(64),
  owner: { uuid: '0123456789abcdef0123456789abcdef', name: 'Alex' },
  createdAt: null,
  updatedAt: null,
  expiresAt: null,
}

describe('shared modpacks (utils)', () => {
  it('normalizes codes and pasted links like the core', () => {
    expect(normalizePackCode('TRS-AB12-CD34')).toBe('TRS-AB12-CD34')
    expect(normalizePackCode(' trs ab12 cd34 ')).toBe('TRS-AB12-CD34')
    expect(normalizePackCode('ab12cd34')).toBe('TRS-AB12-CD34')
    expect(normalizePackCode('Ab1O-CDl4')).toBe('TRS-AB10-CD14')
    expect(normalizePackCode('https://trs-launcher.theredstonee.de/p/TRS-7K2M-Q9XA')).toBe('TRS-7K2M-Q9XA')
    expect(normalizePackCode('AB12CD3')).toBeNull()
    expect(normalizePackCode('AB12CD3U')).toBeNull()
    expect(normalizePackCode('https://evil.example/x')).toBeNull()
  })

  it('describes version and loader', () => {
    expect(packVersionLine({ mcVersion: '1.21.1', loader: { kind: 'fabric', version: '0.16.9' } })).toBe('Minecraft 1.21.1 · Fabric 0.16.9')
    expect(packVersionLine({ mcVersion: '1.20.1', loader: { kind: 'vanilla', version: null } })).toBe('Minecraft 1.20.1 · Vanilla')
  })

  it('only accepts clean packs and outcomes', () => {
    expect(sharedPackSchema.safeParse(pack).success).toBe(true)
    expect(sharedPackSchema.safeParse({ ...pack, url: 'javascript:alert(1)' }).success).toBe(false)
    expect(sharedPackSchema.safeParse({ ...pack, code: 'TRS-7K2M-Q9XU' }).success).toBe(false)
    const jars = { status: 'confirmOwnJars' as const, files: ['own.jar'], token: 'a'.repeat(32), downloads: 1, uploaded: 2, bytes: 100 }
    expect(sharePackOutcomeSchema.parse(jars)).toEqual(jars)
    const ready = { status: 'ready' as const, token: 'b'.repeat(32), downloads: 42, uploaded: 3, bytes: 12_582_912 }
    expect(sharePackOutcomeSchema.parse(ready)).toEqual(ready)
    expect(sharePackOutcomeSchema.safeParse({ status: 'shared', pack }).success).toBe(false)
  })

  it('parses pack live events', () => {
    const shared = liveEventSchema.parse({ type: 'pack_shared', pack, from: { uuid: '0123456789abcdef0123456789abcdef', name: 'Alex' }, sentAt: null })
    expect(shared.type).toBe('pack_shared')
    expect(liveEventSchema.safeParse({ type: 'pack_removed', packId: '../x' }).success).toBe(false)
  })
})
