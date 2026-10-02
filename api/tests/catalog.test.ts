import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { loadBuiltins } from '../server/lib/builtin'

// Die mitgelieferten TRS-Umhänge müssen zum Katalog-Schema passen – sonst startet der Server nicht.
const dir = join(__dirname, '..', 'assets', 'capes')

describe('mitgelieferter Umhang-Katalog', () => {
  it('lädt alle Umhänge mit passenden Bildern', async () => {
    const capes = await loadBuiltins(
      async () => JSON.parse(readFileSync(join(dir, 'catalog.json'), 'utf8')),
      async (name) => readFileSync(join(dir, name)),
    )
    const ids = capes.map((c) => c.id)
    expect(ids).toEqual(expect.arrayContaining(['redstone', 'halloween']))
    for (const id of ['trs', 'team', 'tester', 'content-team', 'veteran', 'ideengeber']) expect(ids).not.toContain(id)
    for (const c of capes) {
      // PNG-Größe aus dem IHDR: (64·scale) × (32·scale·frames)
      expect(c.png.readUInt32BE(16)).toBe(64 * c.scale)
      expect(c.png.readUInt32BE(20)).toBe(32 * c.scale * c.frames)
    }
    const redstone = capes.find((c) => c.id === 'redstone')!
    expect(redstone.unlock).toBe('free')
    expect(redstone.frames).toBe(1)
    const halloween = capes.find((c) => c.id === 'halloween')!
    expect(halloween).toMatchObject({ unlock: 'admin', event: 'halloween', frames: 4 })
    expect(halloween.frameTimeMs).toBeGreaterThanOrEqual(20)
  })
})
