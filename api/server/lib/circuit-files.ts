import {
  CIRCUIT_BLOCKS,
  MAX_CIRCUIT_JSON_BYTES,
  MAX_CIRCUIT_SIZE,
  checkCircuit,
  circuitBlock,
  circuitOfGrid,
  compareVersions,
  emptyGrid,
  gridOf,
  slugCircuitId,
  trimGrid,
  type BlockSpec,
  type CircuitData,
  type CircuitGrid,
} from '../../shared/circuits'
import { ApiError } from './errors'
import { NbtError, isCompound, nCompound, nInt, nList, nStr, num, readNbt, str, writeNbt, type NbtCompound, type NbtOut, type NbtValue } from './nbt'

/**
 * Schaltungs-Dateien (§25.6): Import von Litematica (.litematic), Sponge-Schematic v1–v3 (.schem), Vanilla-Strukturblock
 * (.nbt) und dem eigenen JSON; Export als eigenes JSON und als Strukturdatei (.nbt). Übernommen werden nur
 * Blockzustände – keine Block-Entities (Kisteninhalte, Schilder-Texte), keine Entities.
 */

export const MAX_IMPORT_BYTES = 2 * 1024 * 1024
/** Rohes Volumen der Datei (vor dem Zuschneiden auf die belegten Felder). */
export const MAX_IMPORT_VOLUME = 1 << 20

export type ImportFormat = 'json' | 'litematic' | 'schem' | 'nbt'

export interface ImportWarning {
  /** Block-ID aus der Datei. */
  block: string
  count: number
  /** `solid`: als beliebiger fester Block übernommen, `converted`: als ähnlicher Katalog-Block, `skipped`: weggelassen. */
  action: 'solid' | 'converted' | 'skipped'
  /** Bei `converted`: Katalog-Block. */
  as?: string
}

export interface ImportResult {
  format: ImportFormat
  circuit: CircuitData
  warnings: ImportWarning[]
  size: { x: number, y: number, z: number }
  blockCount: number
}

const invalid = (message: string, details?: Record<string, unknown>) => new ApiError(400, 'invalid_circuit', message, details)

// ---------------------------------------------------------------- Blockzustände → Katalog

/** Volle Blöcke, die als „beliebiger fester Block“ (`solid`) gelten. */
const SOLID_EXACT = new Set([
  'stone', 'cobblestone', 'mossy_cobblestone', 'smooth_stone', 'granite', 'polished_granite', 'diorite', 'polished_diorite', 'andesite',
  'polished_andesite', 'deepslate', 'cobbled_deepslate', 'polished_deepslate', 'calcite', 'tuff', 'dirt', 'coarse_dirt', 'grass_block',
  'podzol', 'mycelium', 'rooted_dirt', 'mud', 'clay', 'gravel', 'netherrack', 'end_stone', 'obsidian', 'crying_obsidian', 'bricks',
  'sandstone', 'chiseled_sandstone', 'cut_sandstone', 'smooth_sandstone', 'red_sandstone', 'chiseled_red_sandstone', 'cut_red_sandstone',
  'smooth_red_sandstone', 'quartz_block', 'smooth_quartz', 'chiseled_quartz_block', 'quartz_pillar', 'iron_block', 'gold_block',
  'diamond_block', 'emerald_block', 'lapis_block', 'coal_block', 'copper_block', 'netherite_block', 'blackstone', 'polished_blackstone',
  'gilded_blackstone', 'basalt', 'polished_basalt', 'smooth_basalt', 'purpur_block', 'purpur_pillar', 'prismarine', 'dark_prismarine',
  'terracotta', 'packed_mud', 'magma_block', 'bone_block', 'hay_block', 'dried_kelp_block', 'honeycomb_block', 'bookshelf',
  'crafting_table', 'jukebox', 'note_block', 'target', 'sculk', 'amethyst_block', 'raw_iron_block', 'raw_gold_block', 'raw_copper_block',
  'snow_block', 'nether_wart_block', 'warped_wart_block', 'shroomlight', 'melon', 'pumpkin', 'carved_pumpkin', 'sponge', 'wet_sponge',
  'dripstone_block', 'moss_block', 'resin_block', 'pale_moss_block', 'chiseled_tuff', 'polished_tuff', 'chiseled_deepslate',
  'reinforced_deepslate', 'lodestone', 'ancient_debris', 'respawn_anchor', 'cartography_table', 'fletching_table', 'smithing_table',
  'loom',
])
const SOLID_PATTERNS = [
  /_planks$/, /_log$/, /_wood$/, /_stem$/, /_hyphae$/, /_wool$/, /_concrete$/, /_terracotta$/, /_bricks$/, /_ore$/,
  /^(exposed|weathered|oxidized)_(cut_)?copper$/, /^waxed_(exposed_|weathered_|oxidized_)?(cut_)?copper(_block)?$/, /^cut_copper$/,
  /^(chiseled|cracked|mossy)_.*bricks$/, /^infested_/, /^stripped_.*_(log|wood|stem|hyphae)$/, /_block$/,
]
/** Nicht-volle `_block`-Namen, die trotz Endung nicht als fest gelten. */
const NOT_SOLID = new Set(['redstone_block', 'slime_block', 'honey_block', 'scaffolding'])

const ALIASES = new Map<string, string>()
for (const b of CIRCUIT_BLOCKS) for (const a of b.aliases) ALIASES.set(a, b.key)

/** Ähnliche Blöcke, die als Katalog-Block übernommen werden (mit Hinweis). */
function similar(id: string): string | null {
  if (/_stained_glass$/.test(id) || id === 'glass') return 'glass'
  if (/_button$/.test(id)) return 'stone_button'
  if (/_pressure_plate$/.test(id) && !/weighted/.test(id)) return 'stone_pressure_plate'
  if (/_sign$/.test(id) && !/wall_sign$|hanging_sign$/.test(id)) return 'oak_sign'
  if (/copper_bulb$/.test(id)) return 'copper_bulb'
  if (id === 'lit_redstone_lamp') return 'redstone_lamp'
  if (id === 'blast_furnace' || id === 'smoker') return 'furnace'
  return null
}

export function isAir(id: string): boolean {
  return id === 'air' || id === 'cave_air' || id === 'void_air' || id === 'structure_void'
}

export interface MappedBlock {
  spec: BlockSpec | null
  action: 'exact' | ImportWarning['action']
}

/** Blockzustand aus einer Datei → Katalog-Eintrag (nur erlaubte Eigenschaften mit erlaubten Werten). */
export function mapBlockState(name: string, props: Record<string, string>): MappedBlock {
  let id = name.trim().toLowerCase()
  if (id.startsWith('minecraft:')) id = id.slice(10)
  else if (id.includes(':')) return { spec: null, action: 'skipped' }
  if (isAir(id)) return { spec: null, action: 'exact' }
  let key: string | null = circuitBlock(id) && id !== 'solid' ? id : ALIASES.get(id) ?? null
  let action: MappedBlock['action'] = 'exact'
  if (!key) {
    key = similar(id)
    if (key) action = 'converted'
  }
  if (!key && !NOT_SOLID.has(id) && (SOLID_EXACT.has(id) || SOLID_PATTERNS.some((p) => p.test(id)))) {
    key = 'solid'
    action = id === 'stone' ? 'exact' : 'solid'
  }
  if (!key) return { spec: null, action: 'skipped' }
  const block = circuitBlock(key)!
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(props)) {
    const allowed = Object.prototype.hasOwnProperty.call(block.props, k) ? block.props[k] : undefined
    const value = String(v).toLowerCase()
    if (allowed?.includes(value)) out[k] = value
  }
  return { spec: { key, props: out, movable: false, optional: false, marker: null }, action }
}

/** `minecraft:repeater[delay=2,facing=north]` → Name + Eigenschaften (Sponge-Palette). */
export function parseStateString(s: string): { name: string, props: Record<string, string> } {
  const br = s.indexOf('[')
  if (br < 0 || !s.endsWith(']')) return { name: s, props: {} }
  const props: Record<string, string> = Object.create(null) as Record<string, string>
  for (const part of s.slice(br + 1, -1).split(',')) {
    const eq = part.indexOf('=')
    if (eq > 0) props[part.slice(0, eq).trim()] = part.slice(eq + 1).trim()
  }
  return { name: s.slice(0, br), props }
}

function propsOf(v: NbtValue | undefined): Record<string, string> {
  const out = Object.create(null) as Record<string, string>
  if (isCompound(v)) for (const [k, x] of Object.entries(v)) if (typeof x === 'string') out[k] = x
  return out
}

// ---------------------------------------------------------------- Formate

/** Einheitliche Sicht auf eine Datei: Maße + Zustand je Feld (Index in die Palette, -1 = Luft). */
interface Volume {
  sx: number
  sy: number
  sz: number
  palette: { name: string, props: Record<string, string> }[]
  /** Feld-Index je Position (x + z*sx + y*sx*sz) oder für dünn besetzte Dateien eine Liste. */
  at: (x: number, y: number, z: number) => number
  /** Optional: nur diese Felder sind belegt (Strukturdateien). */
  sparse?: { x: number, y: number, z: number, state: number }[]
  name?: string
}

function checkDims(sx: number, sy: number, sz: number): void {
  if (![sx, sy, sz].every((n) => Number.isInteger(n) && n >= 1 && n <= 4096)) throw invalid('file has invalid dimensions')
  if (sx * sy * sz > MAX_IMPORT_VOLUME) throw invalid(`file is too big (${sx}×${sy}×${sz}); circuits are at most ${MAX_CIRCUIT_SIZE}×${MAX_CIRCUIT_SIZE}×${MAX_CIRCUIT_SIZE}`)
}

/** Vanilla-Strukturdatei: size, palette (bzw. palettes[0]), blocks [{pos, state}]. */
function structureVolume(root: NbtCompound): Volume {
  const size = root.size
  if (!Array.isArray(size) || size.length !== 3) throw invalid('structure file without size')
  const [sx, sy, sz] = size.map((v) => num(v) ?? -1) as [number, number, number]
  checkDims(sx, sy, sz)
  const pal = Array.isArray(root.palette) ? root.palette : Array.isArray(root.palettes) && Array.isArray(root.palettes[0]) ? root.palettes[0] : null
  if (!pal) throw invalid('structure file without palette')
  const palette = pal.map((p) => ({ name: isCompound(p) ? str(p.Name) ?? '' : '', props: isCompound(p) ? propsOf(p.Properties) : {} }))
  if (!Array.isArray(root.blocks)) throw invalid('structure file without blocks')
  const sparse: Volume['sparse'] = []
  for (const bl of root.blocks) {
    if (!isCompound(bl) || !Array.isArray(bl.pos) || bl.pos.length !== 3) continue
    const [x, y, z] = bl.pos.map((v) => num(v) ?? -1) as [number, number, number]
    const state = num(bl.state) ?? -1
    if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz || state < 0 || state >= palette.length) continue
    // bl.nbt (Block-Entity) wird bewusst nicht gelesen.
    sparse.push({ x, y, z, state })
  }
  return { sx, sy, sz, palette, sparse, at: () => -1 }
}

/** Varints aus Sponge-BlockData lesen (höchstens `count` Werte). */
function readVarints(data: Uint8Array, count: number): Int32Array {
  const out = new Int32Array(count)
  let i = 0
  let off = 0
  while (i < count) {
    let value = 0
    let shift = 0
    for (;;) {
      if (off >= data.length) throw invalid('schematic block data is truncated')
      const byte = data[off++]!
      value |= (byte & 0x7f) << shift
      if ((byte & 0x80) === 0) break
      shift += 7
      if (shift > 28) throw invalid('schematic block data is corrupt')
    }
    out[i++] = value
  }
  return out
}

/** Sponge-Schematic v1/v2 (Palette + BlockData an der Wurzel) und v3 (Blocks.Palette + Blocks.Data). */
function spongeVolume(root: NbtCompound): Volume {
  const s = isCompound(root.Schematic) ? root.Schematic : root
  const sx = num(s.Width) ?? -1
  const sy = num(s.Height) ?? -1
  const sz = num(s.Length) ?? -1
  // Sponge speichert Maße als (unsigned) Short.
  const fix = (n: number) => (n < 0 && n >= -32768 ? n + 65536 : n)
  const [w, h, l] = [fix(sx), fix(sy), fix(sz)]
  checkDims(w, h, l)
  const blocks = isCompound(s.Blocks) ? s.Blocks : null
  const paletteTag = blocks ? blocks.Palette : s.Palette
  const data = blocks ? blocks.Data : s.BlockData
  if (!isCompound(paletteTag) || !(data instanceof Uint8Array)) throw invalid('schematic without palette or block data')
  const entries = Object.entries(paletteTag)
  if (entries.length > 65536) throw invalid('schematic palette is too large')
  const palette: Volume['palette'] = []
  for (const [state, idx] of entries) {
    const i = num(idx)
    if (i === null || i < 0 || i >= entries.length) throw invalid('schematic palette is corrupt')
    palette[i] = parseStateString(state)
  }
  for (let i = 0; i < palette.length; i++) if (!palette[i]) palette[i] = { name: 'minecraft:air', props: {} }
  const ids = readVarints(data, w * h * l)
  return {
    sx: w, sy: h, sz: l, palette,
    at: (x, y, z) => {
      const v = ids[x + z * w + y * w * l]!
      return v < palette.length ? v : -1
    },
  }
}

/** Litematica: Regions → je Region Palette + gepackte BlockStates (Werte dürfen über Long-Grenzen reichen). */
function litematicVolume(root: NbtCompound): Volume {
  if (!isCompound(root.Regions)) throw invalid('litematic without regions')
  const regions = Object.values(root.Regions).filter(isCompound)
  if (!regions.length) throw invalid('litematic has no regions')
  if (regions.length > 16) throw invalid('litematic has too many regions')
  interface Reg { x0: number, y0: number, z0: number, sx: number, sy: number, sz: number, pal: number[], states: BigInt64Array, bits: number }
  const regs: Reg[] = []
  const palette: Volume['palette'] = []
  const palIndex = new Map<string, number>()
  let minX = Infinity, minY = Infinity, minZ = Infinity, maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity
  for (const r of regions) {
    const pos = isCompound(r.Position) ? r.Position : null
    const size = isCompound(r.Size) ? r.Size : null
    if (!pos || !size) throw invalid('litematic region without position or size')
    const [px, py, pz] = [num(pos.x), num(pos.y), num(pos.z)]
    const [rx, ry, rz] = [num(size.x), num(size.y), num(size.z)]
    if ([px, py, pz, rx, ry, rz].some((v) => v === null || Math.abs(v) > 30_000_000)) throw invalid('litematic region is corrupt')
    const sx = Math.abs(rx!), sy = Math.abs(ry!), sz = Math.abs(rz!)
    checkDims(sx, sy, sz)
    // Negative Größe = Region reicht vom Ursprung aus in die negative Richtung.
    const x0 = px! + (rx! < 0 ? rx! + 1 : 0), y0 = py! + (ry! < 0 ? ry! + 1 : 0), z0 = pz! + (rz! < 0 ? rz! + 1 : 0)
    const bsp = r.BlockStatePalette
    const states = r.BlockStates
    if (!Array.isArray(bsp) || !(states instanceof BigInt64Array)) throw invalid('litematic region without block states')
    if (bsp.length === 0 || bsp.length > 65536) throw invalid('litematic palette is corrupt')
    const pal = bsp.map((p) => {
      const e = { name: isCompound(p) ? str(p.Name) ?? '' : '', props: isCompound(p) ? propsOf(p.Properties) : {} }
      const k = `${e.name}|${JSON.stringify(Object.entries(e.props).sort())}`
      let i = palIndex.get(k)
      if (i === undefined) {
        i = palette.length
        palette.push(e)
        palIndex.set(k, i)
      }
      return i
    })
    const bits = Math.max(2, 32 - Math.clz32(bsp.length - 1))
    if (BigInt(states.length) * 64n < BigInt(sx * sy * sz) * BigInt(bits)) throw invalid('litematic block states are truncated')
    regs.push({ x0, y0, z0, sx, sy, sz, pal, states, bits })
    minX = Math.min(minX, x0); minY = Math.min(minY, y0); minZ = Math.min(minZ, z0)
    maxX = Math.max(maxX, x0 + sx - 1); maxY = Math.max(maxY, y0 + sy - 1); maxZ = Math.max(maxZ, z0 + sz - 1)
  }
  const W = maxX - minX + 1, H = maxY - minY + 1, L = maxZ - minZ + 1
  checkDims(W, H, L)
  const value = (r: Reg, i: number): number => {
    const start = BigInt(i) * BigInt(r.bits)
    const word = Number(start >> 6n)
    const bit = start & 63n
    const mask = (1n << BigInt(r.bits)) - 1n
    let v = BigInt.asUintN(64, r.states[word]!) >> bit
    if (bit + BigInt(r.bits) > 64n) v |= BigInt.asUintN(64, r.states[word + 1]!) << (64n - bit)
    return Number(v & mask)
  }
  const meta = isCompound(root.Metadata) ? root.Metadata : null
  return {
    sx: W, sy: H, sz: L, palette,
    name: meta ? str(meta.Name) ?? undefined : undefined,
    at: (x, y, z) => {
      const gx = x + minX, gy = y + minY, gz = z + minZ
      // Spätere Regionen überdecken frühere (wie beim Einfügen in Litematica).
      for (let k = regs.length - 1; k >= 0; k--) {
        const r = regs[k]!
        const lx = gx - r.x0, ly = gy - r.y0, lz = gz - r.z0
        if (lx < 0 || ly < 0 || lz < 0 || lx >= r.sx || ly >= r.sy || lz >= r.sz) continue
        const p = value(r, (ly * r.sz + lz) * r.sx + lx)
        if (p >= r.pal.length) return -1
        const idx = r.pal[p]!
        if (isAir(palette[idx]!.name.replace(/^minecraft:/, ''))) continue
        return idx
      }
      return -1
    },
  }
}

/** Volumen → zugeschnittenes Raster + Hinweise. */
function toGrid(v: Volume): { grid: CircuitGrid, warnings: ImportWarning[] } {
  const mapped = v.palette.map((p) => ({ ...mapBlockState(p.name, p.props), name: p.name.replace(/^minecraft:/, '') }))
  const counts = new Map<string, ImportWarning>()
  let occupied = 0
  const cells: { x: number, y: number, z: number, spec: BlockSpec }[] = []
  const visit = (x: number, y: number, z: number, state: number) => {
    if (state < 0) return
    const m = mapped[state]
    if (!m) return
    // Luft ist `exact` ohne Eintrag; alles andere außer exakten Treffern bekommt einen Hinweis.
    if (m.action !== 'exact') {
      const key = `${m.name}|${m.action}`
      const w = counts.get(key) ?? { block: m.name.slice(0, 64), count: 0, action: m.action, ...(m.action === 'converted' && m.spec ? { as: m.spec.key } : {}) }
      w.count++
      counts.set(key, w)
    }
    if (!m.spec) return
    if (++occupied > MAX_CIRCUIT_SIZE ** 3) throw invalid(`too many blocks – circuits are at most ${MAX_CIRCUIT_SIZE}×${MAX_CIRCUIT_SIZE}×${MAX_CIRCUIT_SIZE}`)
    cells.push({ x, y, z, spec: m.spec })
  }
  if (v.sparse) for (const c of v.sparse) visit(c.x, c.y, c.z, c.state)
  else for (let y = 0; y < v.sy; y++) for (let z = 0; z < v.sz; z++) for (let x = 0; x < v.sx; x++) visit(x, y, z, v.at(x, y, z))
  if (!cells.length) throw invalid('the file contains no supported blocks', { warnings: [...counts.values()] })
  const minX = Math.min(...cells.map((c) => c.x)), minY = Math.min(...cells.map((c) => c.y)), minZ = Math.min(...cells.map((c) => c.z))
  const dx = Math.max(...cells.map((c) => c.x)) - minX + 1, dy = Math.max(...cells.map((c) => c.y)) - minY + 1, dz = Math.max(...cells.map((c) => c.z)) - minZ + 1
  if (dx > MAX_CIRCUIT_SIZE || dy > MAX_CIRCUIT_SIZE || dz > MAX_CIRCUIT_SIZE) {
    throw invalid(`the build is ${dx}×${dy}×${dz} – circuits are at most ${MAX_CIRCUIT_SIZE}×${MAX_CIRCUIT_SIZE}×${MAX_CIRCUIT_SIZE}`, { size: { x: dx, y: dy, z: dz } })
  }
  const grid = emptyGrid(dx, dy, dz)
  for (const c of cells) grid.cells[c.y - minY]![c.z - minZ]![c.x - minX] = c.spec
  return { grid: trimGrid(grid), warnings: [...counts.values()].sort((a, b) => b.count - a.count).slice(0, 50) }
}

// ---------------------------------------------------------------- Import

export interface ImportOptions {
  /** Dateiname (für Name/ID, wenn die Datei keinen hat). */
  filename?: string
}

const stripBom = (t: string): string => (t.charCodeAt(0) === 0xfeff ? t.slice(1) : t)

function looksLikeJson(buf: Buffer): boolean {
  let i = 0
  if (buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) i = 3
  while (i < buf.length && (buf[i] === 0x20 || buf[i] === 0x0a || buf[i] === 0x0d || buf[i] === 0x09)) i++
  return buf[i] === 0x7b
}

function baseName(filename: string | undefined): string {
  const f = (filename ?? '').replace(/^.*[\\/]/, '').replace(/\.(litematic|schem|schematic|nbt|json)$/i, '')
  return f.slice(0, 64)
}

/**
 * Datei → Schaltung. Format wird am Inhalt erkannt (nicht an der Endung). Fehler: 400 `invalid_circuit` (Details:
 * `errors` bzw. `warnings`), 413 bei zu großen Dateien.
 */
export function importCircuitFile(data: Uint8Array, opts: ImportOptions = {}): ImportResult {
  if (data.length === 0) throw invalid('the file is empty')
  if (data.length > MAX_IMPORT_BYTES) throw new ApiError(413, 'payload_too_large', 'File is larger than 2 MB')
  const buf = Buffer.from(data.buffer, data.byteOffset, data.byteLength)
  const fallbackName = baseName(opts.filename)

  if (looksLikeJson(buf)) {
    if (buf.length > MAX_CIRCUIT_JSON_BYTES) throw new ApiError(413, 'payload_too_large', 'Circuit JSON is larger than 256 KB')
    let raw: unknown
    try {
      raw = JSON.parse(stripBom(buf.toString('utf8')))
    } catch {
      throw invalid('the file is not valid JSON')
    }
    const r = checkCircuit(raw)
    if (!r.ok) throw invalid('the circuit is not valid', { errors: r.errors })
    return { format: 'json', circuit: r.circuit, warnings: [], size: r.info.size, blockCount: r.info.blockCount }
  }

  let root: NbtCompound
  try {
    root = readNbt(buf).root
  } catch (err) {
    if (err instanceof NbtError) throw invalid(`the file could not be read: ${err.message}`)
    throw err
  }
  let format: ImportFormat
  let volume: Volume
  if (isCompound(root.Regions)) {
    format = 'litematic'
    volume = litematicVolume(root)
  } else if (Array.isArray(root.size) && Array.isArray(root.blocks)) {
    format = 'nbt'
    volume = structureVolume(root)
  } else if (isCompound(root.Schematic) || root.Width !== undefined) {
    if (root.Blocks instanceof Uint8Array && root.Materials !== undefined) throw invalid('old MCEdit .schematic files are not supported – save as .schem (Sponge) or .litematic')
    format = 'schem'
    volume = spongeVolume(root)
  } else {
    throw invalid('unknown file format – use .litematic, .schem, .nbt (structure block) or the TRS JSON')
  }
  const { grid, warnings } = toGrid(volume)
  let body: ReturnType<typeof circuitOfGrid>
  try {
    body = circuitOfGrid(grid)
  } catch (err) {
    throw invalid((err as Error).message)
  }
  const name = (volume.name?.trim() || fallbackName || 'Imported circuit').replace(/[\p{Cc}§]/gu, '').slice(0, 64)
  const candidate = {
    format: 1,
    id: slugCircuitId(name),
    category: 'basics',
    difficulty: 1,
    server: 'ok',
    texts: { en: { name } },
    ...body,
  }
  const r = checkCircuit(candidate)
  if (!r.ok) throw invalid('the converted circuit is not valid', { errors: r.errors, warnings })
  return { format, circuit: r.circuit, warnings, size: r.info.size, blockCount: r.info.blockCount }
}

// ---------------------------------------------------------------- Export

/** DataVersion für Strukturdateien: älteste Version, in der die Schaltung läuft (Minecraft rechnet neuere hoch). */
export function dataVersionFor(since: string): number {
  if (compareVersions(since, '1.21') >= 0) return 3953
  if (compareVersions(since, '1.20') >= 0) return 3463
  if (compareVersions(since, '1.19') >= 0) return 3105
  if (compareVersions(since, '1.17') >= 0) return 2724
  if (compareVersions(since, '1.16') >= 0) return 2566
  // Moderne Block-Namen gibt es erst seit 1.13 (Flattening).
  return 1631
}

/**
 * Schaltung → Vanilla-Strukturdatei (gzip-NBT), ladbar mit einem Strukturblock (Datei nach
 * `<Welt>/generated/minecraft/structures/<name>.nbt`). Leere Felder werden als Luft gespeichert.
 */
export function exportStructureNbt(c: Pick<CircuitData, 'palette' | 'layers' | 'since'>): Buffer {
  const grid = gridOf(c)
  // Wirksame Version: höchste aus `since` und den Blöcken (Kupfer-Birne → 1.21 …).
  let since = c.since ?? '1.13'
  for (const layer of grid.cells) for (const row of layer) for (const s of row) {
    const bs = s ? circuitBlock(s.key)?.since : undefined
    if (bs && compareVersions(bs, since) > 0) since = bs
  }
  const paletteIdx = new Map<string, number>()
  const palette: NbtOut[] = []
  const stateOf = (spec: BlockSpec | null): number => {
    const block = spec ? circuitBlock(spec.key)! : null
    const id = `minecraft:${block ? block.exportId : 'air'}`
    const props: [string, string][] = spec ? Object.entries(spec.props).sort(([a], [b]) => a.localeCompare(b)) : []
    if (spec?.key === 'water') props.push(['level', '0'])
    const key = `${id}|${props.map(([k, v]) => `${k}=${v}`).join(',')}`
    let i = paletteIdx.get(key)
    if (i === undefined) {
      i = palette.length
      paletteIdx.set(key, i)
      const entry: [string, NbtOut][] = [['Name', nStr(id)]]
      if (props.length) entry.push(['Properties', nCompound(props.map(([k, v]) => [k, nStr(v)]))])
      palette.push(nCompound(entry))
    }
    return i
  }
  const blocks: NbtOut[] = []
  for (let y = 0; y < grid.y; y++) for (let z = 0; z < grid.z; z++) for (let x = 0; x < grid.x; x++) {
    const state = stateOf(grid.cells[y]![z]![x]!)
    blocks.push(nCompound([['pos', nList('int', [nInt(x), nInt(y), nInt(z)])], ['state', nInt(state)]]))
  }
  return writeNbt(nCompound([
    ['DataVersion', nInt(dataVersionFor(since))],
    ['size', nList('int', [nInt(grid.x), nInt(grid.y), nInt(grid.z)])],
    ['palette', nList('compound', palette)],
    ['blocks', nList('compound', blocks)],
    ['entities', nList('compound', [])],
  ]))
}
