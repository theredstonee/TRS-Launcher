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
    expect(capes.map((c) => c.id)).toEqual(expect.arrayContaining(['team', 'tester', 'redstone']))
    for (const c of capes) {
      // PNG-Größe aus dem IHDR: (64·scale) × (32·scale·frames)
      expect(c.png.readUInt32BE(16)).toBe(64 * c.scale)
      expect(c.png.readUInt32BE(20)).toBe(32 * c.scale * c.frames)
    }
    const team = capes.find((c) => c.id === 'team')!
    expect(team.unlock).toBe('admin')
    expect(team.frames).toBeGreaterThan(1)
    expect(team.frameTimeMs).toBeGreaterThanOrEqual(20)
    expect(capes.find((c) => c.id === 'tester')!.unlock).toBe('admin')
  })
})
