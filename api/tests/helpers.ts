import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { crc32 } from 'node:zlib'
import { PNG } from 'pngjs'
import { afterEach } from 'vitest'
import { verifyLogin, createChallenge } from '../server/lib/auth'
import { seedBuiltins, type BuiltinCape } from '../server/lib/capes'
import { loadConfig, type Limits } from '../server/lib/config'
import { createContext, type AppContext } from '../server/lib/context'
import { openDb } from '../server/lib/db'
import type { MojangClient, MojangProfile } from '../server/lib/mojang'

export const ADMIN = '75c1a6f3112240abbdb57b9d21c64232'

/** Mojang-Attrappe: `join(name, uuid, serverId)` merkt sich einen Beitritt wie der echte Session-Server. */
export class FakeMojang implements MojangClient {
  joins = new Map<string, MojangProfile>()
  fail = false
  calls = 0
  join(name: string, uuid: string, serverId: string): void {
    this.joins.set(serverId, { uuid, name })
  }
  async hasJoined(username: string, serverId: string): Promise<MojangProfile | null> {
    this.calls++
    if (this.fail) {
      const { MojangUnavailable } = await import('../server/lib/mojang')
      throw new MojangUnavailable('down')
    }
    const p = this.joins.get(serverId)
    return p && p.name.toLowerCase() === username.toLowerCase() ? p : null
  }
}

export interface TestEnv {
  ctx: AppContext
  mojang: FakeMojang
  clock: { t: number, advance: (ms: number) => void }
  dir: string
}

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

export function makeEnv(opts: { limits?: Partial<Limits>, env?: Record<string, string> } = {}): TestEnv {
  const dir = mkdtempSync(join(tmpdir(), 'trs-api-test-'))
  dirs.push(dir)
  const clock = {
    t: Date.UTC(2026, 8, 23, 12, 0, 0),
    advance(ms: number) {
      this.t += ms
    },
  }
  const config = loadConfig(
    {
      DATA_DIR: dir,
      SECRET_KEY: 'test-secret-key-0123456789abcdef-0123456789',
      ADMIN_UUIDS: ADMIN,
      ADMIN_API_KEY: 'admin-key-0123456789abcdef-0123456789abcdef',
      PUBLIC_BASE_URL: 'https://api.example.test',
      ...opts.env,
    },
    opts.limits,
  )
  const mojang = new FakeMojang()
  const ctx = createContext({ config, db: openDb(':memory:'), mojang, capeDir: join(dir, 'capes'), now: () => clock.t })
  return { ctx, mojang, clock, dir }
}

let serial = 0
/** Meldet einen Spieler über den echten Challenge/Verify-Ablauf an (Mojang gemockt). */
export async function login(env: TestEnv, name: string, uuid?: string) {
  const id = uuid ?? (++serial).toString(16).padStart(32, 'a')
  const { serverId } = createChallenge(env.ctx)
  env.mojang.join(name, id, serverId)
  return verifyLogin(env.ctx, name, serverId)
}

// ------------------------------------------------------------------ PNG-Helfer

export function solidPng(w: number, h: number, rgba: [number, number, number, number] = [200, 30, 20, 255]): Buffer {
  const p = new PNG({ width: w, height: h })
  for (let i = 0; i < w * h; i++) p.data.set(rgba, i * 4)
  return PNG.sync.write(p)
}

export function chunk(type: string, data: Buffer): Buffer {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type, 'latin1'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(td) >>> 0)
  return Buffer.concat([len, td, crc])
}

/** Zerlegt ein PNG in Chunks (Typ + Daten). */
export function chunks(png: Buffer): { type: string, data: Buffer }[] {
  const out: { type: string, data: Buffer }[] = []
  let off = 8
  while (off < png.length) {
    const len = png.readUInt32BE(off)
    out.push({ type: png.toString('latin1', off + 4, off + 8), data: png.subarray(off + 8, off + 8 + len) })
    off += 12 + len
  }
  return out
}

/** Fügt vor IEND zusätzliche Chunks ein. */
export function withChunks(png: Buffer, extra: Buffer[], beforeIdat = false): Buffer {
  const parts = chunks(png)
  const sig = png.subarray(0, 8)
  const out: Buffer[] = [sig]
  for (const c of parts) {
    if ((beforeIdat && c.type === 'IDAT') || (!beforeIdat && c.type === 'IEND')) {
      out.push(...extra)
      extra = []
    }
    out.push(chunk(c.type, c.data))
  }
  return Buffer.concat(out)
}

/** Standard-Umhänge für Tests: frei (2×), per Code, Admin (animiert, 4 Frames). */
export function fixtureBuiltins(): BuiltinCape[] {
  return [
    { id: 'redstone', name: 'Redstone', unlock: 'free', sort: 0, scale: 2, frames: 1, frameTimeMs: null, png: solidPng(128, 64) },
    { id: 'emerald', name: 'Smaragd', unlock: 'code', sort: 1, scale: 1, frames: 1, frameTimeMs: null, png: solidPng(64, 32, [20, 200, 80, 255]) },
    { id: 'team', name: 'TRS Team', unlock: 'admin', sort: 2, scale: 2, frames: 4, frameTimeMs: 150, png: solidPng(128, 256, [120, 10, 10, 255]) },
  ]
}

export function seedFixtures(env: TestEnv): void {
  seedBuiltins(env.ctx, fixtureBuiltins())
}
