import { readFileSync } from 'node:fs'
import { IncomingMessage, ServerResponse } from 'node:http'
import { Socket } from 'node:net'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { createEvent, type H3Event } from 'h3'
import { circuitBlock, gridOf, type BlockSpec, type CircuitData, type CircuitGrid } from '../shared/circuits'
import { seedCircuits, type SeedFiles } from '../server/lib/circuits'
import { setContext } from '../server/lib/context'
import { nCompound, nInt, nList, nStr, writeNbt, type NbtOut } from '../server/lib/nbt'
import type { TestEnv } from './helpers'

const DIR = join(__dirname, '..', 'assets', 'circuits')

/** Die mitgelieferten Schaltungen aus assets/circuits (wie beim Serverstart). */
export function bundledSeed(): SeedFiles {
  const order = (JSON.parse(readFileSync(join(DIR, 'index.json'), 'utf8')) as { circuits: string[] }).circuits
  const files = new Map<string, unknown>()
  for (const id of order) files.set(id, JSON.parse(readFileSync(join(DIR, `${id}.json`), 'utf8')))
  return { order, files }
}

export function seedAll(env: TestEnv) {
  return seedCircuits(env.ctx, bundledSeed())
}

export function bundledCircuit(id: string): CircuitData {
  return JSON.parse(readFileSync(join(DIR, `${id}.json`), 'utf8')) as CircuitData
}

// ---------------------------------------------------------------- Routen aufrufen

export interface RouteResult {
  status: number
  headers: Record<string, unknown>
  body?: unknown
  error?: { status: number, code: string, details?: Record<string, unknown>, headers?: Record<string, string> }
}

/** Ruft einen Nitro-Handler direkt auf (Methode, Header, Body, Router-Parameter). */
export async function callRoute(
  env: TestEnv,
  route: (e: H3Event) => unknown,
  opts: { method?: string, url: string, params?: Record<string, string>, headers?: Record<string, string>, body?: Buffer | string },
): Promise<RouteResult> {
  setContext(env.ctx)
  const body = opts.body === undefined ? Buffer.alloc(0) : Buffer.isBuffer(opts.body) ? opts.body : Buffer.from(opts.body)
  const req = Readable.from(body.length ? [body] : []) as unknown as IncomingMessage
  Object.assign(req, {
    method: opts.method ?? 'GET',
    url: opts.url,
    headers: Object.fromEntries(Object.entries({ ...(body.length ? { 'content-length': String(body.length) } : {}), ...opts.headers }).map(([k, v]) => [k.toLowerCase(), v])),
    socket: new Socket(),
    connection: new Socket(),
  })
  const res = new ServerResponse(new IncomingMessage(new Socket()))
  const event = createEvent(req, res)
  event.context.params = opts.params ?? {}
  try {
    const out = await route(event)
    return { status: res.statusCode, headers: res.getHeaders(), body: out }
  } catch (e) {
    const err = e as { status: number, code: string, details?: Record<string, unknown>, headers?: Record<string, string> }
    return { status: err.status, headers: res.getHeaders(), error: err }
  } finally {
    setContext(undefined)
  }
}

// ---------------------------------------------------------------- Testdateien bauen

function stateOf(spec: BlockSpec | null): { name: string, props: [string, string][] } {
  if (!spec) return { name: 'minecraft:air', props: [] }
  return { name: `minecraft:${circuitBlock(spec.key)!.exportId}`, props: Object.entries(spec.props).sort(([a], [b]) => a.localeCompare(b)) }
}

const stateString = (s: { name: string, props: [string, string][] }) =>
  s.props.length ? `${s.name}[${s.props.map(([k, v]) => `${k}=${v}`).join(',')}]` : s.name

/** Litematica-Datei (eine oder zwei Regionen; Werte über Long-Grenzen wie in Litematica). */
export function toLitematic(c: Pick<CircuitData, 'palette' | 'layers'>, opts: { negative?: boolean, name?: string, extraPalette?: number } = {}): Buffer {
  const g = gridOf(c)
  const palette: { name: string, props: [string, string][] }[] = [{ name: 'minecraft:air', props: [] }]
  const idx = new Map<string, number>([['minecraft:air', 0]])
  // Zusätzliche (unbenutzte) Einträge erzwingen mehr Bits je Wert.
  for (let i = 0; i < (opts.extraPalette ?? 0); i++) {
    palette.push({ name: `minecraft:white_wool`, props: [['dummy', String(i)]] })
    idx.set(`dummy${i}`, palette.length - 1)
  }
  const values: number[] = []
  for (let y = 0; y < g.y; y++) for (let z = 0; z < g.z; z++) for (let x = 0; x < g.x; x++) {
    const s = stateOf(g.cells[y]![z]![x]!)
    const key = stateString(s)
    let i = idx.get(key)
    if (i === undefined) {
      i = palette.length
      palette.push(s)
      idx.set(key, i)
    }
    values.push(i)
  }
  const bits = Math.max(2, 32 - Math.clz32(palette.length - 1))
  const longs = new Array<bigint>(Math.ceil((values.length * bits) / 64)).fill(0n)
  values.forEach((v, i) => {
    const start = i * bits
    const word = start >> 6
    const off = start & 63
    longs[word] = BigInt.asUintN(64, longs[word]! | (BigInt(v) << BigInt(off)))
    if (off + bits > 64) longs[word + 1] = BigInt.asUintN(64, longs[word + 1]! | (BigInt(v) >> BigInt(64 - off)))
  })
  const neg = opts.negative ?? false
  const size = neg ? [-g.x, -g.y, -g.z] : [g.x, g.y, g.z]
  const pos = neg ? [g.x - 1 + 100, g.y - 1 + 60, g.z - 1 - 20] : [100, 60, -20]
  const region = nCompound([
    ['Position', nCompound([['x', nInt(pos[0]!)], ['y', nInt(pos[1]!)], ['z', nInt(pos[2]!)]])],
    ['Size', nCompound([['x', nInt(size[0]!)], ['y', nInt(size[1]!)], ['z', nInt(size[2]!)]])],
    ['BlockStatePalette', nList('compound', palette.map((p) => nCompound([
      ['Name', nStr(p.name)],
      ...(p.props.length ? [['Properties', nCompound(p.props.map(([k, v]) => [k, nStr(v)]))] as [string, NbtOut]] : []),
    ])))],
    ['BlockStates', { t: 'longs', v: longs }],
    ['TileEntities', nList('compound', [nCompound([['Items', nList('compound', [nCompound([['id', nStr('minecraft:diamond')]])])]])])],
    ['Entities', nList('compound', [])],
  ])
  return writeNbt(nCompound([
    ['Version', nInt(6)],
    ['MinecraftDataVersion', nInt(3953)],
    ['Metadata', nCompound([['Name', nStr(opts.name ?? 'Litematic test')], ['Author', nStr('tester')]])],
    ['Regions', nCompound([['main', region]])],
  ]))
}

function varints(values: number[]): Uint8Array {
  const out: number[] = []
  for (let v of values) {
    while ((v & ~0x7f) !== 0) {
      out.push((v & 0x7f) | 0x80)
      v >>>= 7
    }
    out.push(v)
  }
  return Uint8Array.from(out)
}

/** Sponge-Schematic v2 (Palette an der Wurzel) bzw. v3 (Schematic.Blocks). */
export function toSponge(c: Pick<CircuitData, 'palette' | 'layers'>, version: 2 | 3, opts: { pad?: number, extraBlocks?: [number, number, number, string][] } = {}): Buffer {
  const g0 = gridOf(c)
  const pad = opts.pad ?? 0
  const W = g0.x + pad * 2, H = g0.y + pad, L = g0.z + pad * 2
  const pal = new Map<string, number>([['minecraft:air', 0]])
  const data: number[] = []
  const extra = new Map((opts.extraBlocks ?? []).map(([x, y, z, s]) => [`${x},${y},${z}`, s]))
  for (let y = 0; y < H; y++) for (let z = 0; z < L; z++) for (let x = 0; x < W; x++) {
    const gx = x - pad, gz = z - pad
    const inside = gx >= 0 && gz >= 0 && gx < g0.x && gz < g0.z && y < g0.y
    const key = extra.get(`${x},${y},${z}`) ?? stateString(stateOf(inside ? g0.cells[y]![gz]![gx]! : null))
    let i = pal.get(key)
    if (i === undefined) {
      i = pal.size
      pal.set(key, i)
    }
    data.push(i)
  }
  const palette = nCompound([...pal.entries()].map(([k, v]) => [k, nInt(v)]))
  const bytes: NbtOut = { t: 'bytes', v: varints(data) }
  if (version === 2) {
    return writeNbt(nCompound([
      ['Version', nInt(2)], ['DataVersion', nInt(3953)],
      ['Width', { t: 'short', v: W }], ['Height', { t: 'short', v: H }], ['Length', { t: 'short', v: L }],
      ['PaletteMax', nInt(pal.size)], ['Palette', palette], ['BlockData', bytes],
      ['BlockEntities', nList('compound', [nCompound([['Id', nStr('minecraft:chest')], ['Pos', { t: 'ints', v: [0, 0, 0] }]])])],
    ]), 'Schematic')
  }
  return writeNbt(nCompound([['Schematic', nCompound([
    ['Version', nInt(3)], ['DataVersion', nInt(3953)],
    ['Width', { t: 'short', v: W }], ['Height', { t: 'short', v: H }], ['Length', { t: 'short', v: L }],
    ['Blocks', nCompound([['Palette', palette], ['Data', bytes], ['BlockEntities', nList('compound', [])]])],
  ])]]))
}

/** Raster eines Imports (für Vergleiche). */
export function gridText(g: CircuitGrid): string {
  return JSON.stringify(g.cells)
}
