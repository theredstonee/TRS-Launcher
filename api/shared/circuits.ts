/**
 * Schaltungs-Bibliothek (API.md §25): Format der Schaltungen wie im TRS Client (docs/circuit-format.md), Block-Katalog
 * (Allowlist), strenge Prüfung und das Raster für Editor und Import. Reine Datenverarbeitung ohne Importe – Server,
 * Website-Editor und Tests nutzen dieselben Regeln.
 *
 * Aufbau einer Schaltung:
 * ```
 * {"format": 1, "id": "not_gate", "category": "basics", "difficulty": 1, "server": "ok", "since"?: "1.11", "until"?: "1.21.4",
 *  "texts": {"en": {"name": "…", "desc": "…", "note"?: "…"}, "de": {…}},
 *  "palette": {"#": "solid", "A": "lever[face=floor]@A", …},
 *  "layers": [["A-#tL"]],     // layers[y][z] = Zeile in x-Richtung (Osten); '.' und ' ' = Luft
 *  "tests"?: [...]}           // Simulation im Client
 * ```
 * Koordinaten: x = Osten, y = oben, z = Süden; Eigenschaften wie in Minecraft.
 * Palette-Eintrag: `id[k=v,…]~?@M` – `~` beweglich (Kolben), `?` nur Anzeige/Simulation, `@M` Anschluss für Tests.
 */

export const CIRCUIT_FORMAT = 1
export const MAX_CIRCUIT_SIZE = 16
export const MAX_PALETTE = 64
export const MAX_NAME = 64
export const MAX_DESC = 1200
export const MAX_NOTE = 300
export const MAX_LANGUAGES = 12
export const MAX_TESTS = 16
/** Schritte bzw. Zeilen je Test und Ticks je Schritt (wie `CircuitSimTest` im Client). */
export const MAX_TEST_STEPS = 128
export const MAX_TEST_TICKS = 2000
/** Größe einer Schaltung als JSON (auch Einreichungen). */
export const MAX_CIRCUIT_JSON_BYTES = 256 * 1024

export const CIRCUIT_CATEGORIES = ['basics', 'clocks', 'memory', 'pulse', 'doors', 'farms', 'displays'] as const
export type CircuitCategory = (typeof CIRCUIT_CATEGORIES)[number]
export const isCircuitCategory = (v: unknown): v is CircuitCategory => typeof v === 'string' && (CIRCUIT_CATEGORIES as readonly string[]).includes(v)

/** Sprachen, die der Admin-Editor pflegt (der Client kennt bis zu {@link MAX_LANGUAGES}). */
export const CIRCUIT_LANGS = ['en', 'de', 'es'] as const
export type CircuitLang = (typeof CIRCUIT_LANGS)[number]

export const CIRCUIT_ID = /^[a-z0-9_]{1,48}$/
/** IDs, die mit Routen kollidieren würden (`/v1/circuits/index` …). */
export const RESERVED_CIRCUIT_IDS: ReadonlySet<string> = new Set(['index', 'submissions', 'convert', 'new', 'mine', 'submit'])
export const CIRCUIT_LANG = /^[a-z]{2}(-[A-Z]{2})?$/
export const CIRCUIT_VERSION = /^[0-9]{1,3}(\.[0-9]{1,3}){1,3}$/
export const CIRCUIT_MARKER = /^[A-Za-z0-9]{1,8}$/

// ---------------------------------------------------------------- Block-Katalog

const H = ['north', 'east', 'south', 'west'] as const
const ALL6 = ['north', 'east', 'south', 'west', 'up', 'down'] as const
const HOPPER = ['down', 'north', 'east', 'south', 'west'] as const
const FACE = ['floor', 'wall', 'ceiling'] as const
const DELAY = ['1', '2', '3', '4'] as const
const MODE = ['compare', 'subtract'] as const
const BOOL = ['false', 'true'] as const

/** Darstellung im isometrischen Bild und im Raster-Editor. */
export type BlockShape = 'cube' | 'glass' | 'dust' | 'torch' | 'wall_torch' | 'slab' | 'lever' | 'button' | 'plate' | 'cross' | 'water' | 'hopper' | 'sign' | 'piston' | 'daylight'

export interface CircuitBlock {
  /** Moderne Block-ID ohne `minecraft:` (`solid` = beliebiger voller, leitender Block). */
  key: string
  /** Gegenstand für Export und Materialliste. */
  item: string
  /** Block-ID für den .nbt-Export. */
  exportId: string
  /** Ab dieser Minecraft-Version gibt es den Block. */
  since: string
  /** Erlaubte Eigenschaften mit Werten (erster Wert = Standard im Editor). */
  props: Readonly<Record<string, readonly string[]>>
  /** Andere IDs, die beim Import als dieser Block gelten. */
  aliases: readonly string[]
  shape: BlockShape
  /** Grundfarbe (Oberseite) für Vorschau und Raster. */
  color: string
  /** Zeichen, das der Editor bevorzugt in die Palette schreibt. */
  glyph: string
}

const b = (key: string, o: Partial<CircuitBlock> & Pick<CircuitBlock, 'shape' | 'color' | 'glyph'>): CircuitBlock => ({
  key,
  item: o.item ?? key,
  exportId: o.exportId ?? key,
  since: o.since ?? '1.8',
  props: o.props ?? {},
  aliases: o.aliases ?? [],
  shape: o.shape,
  color: o.color,
  glyph: o.glyph,
})

/** Alle erlaubten Blöcke (= `assets/circuits/blocks.json` des Clients, Test hält beides gleich). */
export const CIRCUIT_BLOCKS: readonly CircuitBlock[] = [
  b('solid', { item: 'stone', exportId: 'stone', shape: 'cube', color: '#8a8a8e', glyph: '#' }),
  b('glass', { shape: 'glass', color: '#bfe3ea', glyph: 'g', aliases: ['white_stained_glass', 'tinted_glass'] }),
  b('sand', { shape: 'cube', color: '#dccf9f', glyph: 's', aliases: ['red_sand'] }),
  b('soul_sand', { shape: 'cube', color: '#5b4538', glyph: 'u' }),
  b('sugar_cane', { shape: 'cross', color: '#8fc462', glyph: 'c' }),
  b('water', { item: 'water_bucket', shape: 'water', color: '#3f76e4', glyph: 'w', aliases: ['bubble_column'] }),
  b('redstone_wire', { item: 'redstone', shape: 'dust', color: '#d21a12', glyph: '-' }),
  b('repeater', { props: { facing: H, delay: DELAY }, shape: 'slab', color: '#b9b4ad', glyph: 'r' }),
  b('comparator', { props: { facing: H, mode: MODE }, shape: 'slab', color: '#c4bdb4', glyph: 'k' }),
  b('redstone_torch', { shape: 'torch', color: '#ff3b22', glyph: 't' }),
  b('redstone_wall_torch', { item: 'redstone_torch', props: { facing: H }, shape: 'wall_torch', color: '#ff3b22', glyph: 'T' }),
  b('lever', { props: { face: FACE, facing: H }, shape: 'lever', color: '#7a6446', glyph: 'l' }),
  b('stone_button', { props: { face: FACE, facing: H }, shape: 'button', color: '#9d9d9d', glyph: 'b' }),
  b('stone_pressure_plate', { props: {}, shape: 'plate', color: '#9d9d9d', glyph: 'p', aliases: ['oak_pressure_plate', 'polished_blackstone_pressure_plate'] }),
  b('redstone_lamp', { shape: 'cube', color: '#b86d2e', glyph: 'L' }),
  b('redstone_block', { shape: 'cube', color: '#b3140c', glyph: 'R' }),
  b('piston', { props: { facing: ALL6 }, shape: 'piston', color: '#9c8456', glyph: 'P' }),
  b('sticky_piston', { props: { facing: ALL6 }, shape: 'piston', color: '#7fae5a', glyph: 'S' }),
  b('observer', { props: { facing: ALL6 }, since: '1.11', shape: 'cube', color: '#5f5f5f', glyph: 'O' }),
  b('hopper', { props: { facing: HOPPER }, shape: 'hopper', color: '#4a4a4f', glyph: 'H' }),
  b('chest', { props: { facing: H }, shape: 'cube', color: '#a26b28', glyph: 'C', aliases: ['trapped_chest', 'barrel'] }),
  b('furnace', { props: { facing: H }, shape: 'cube', color: '#6e6e6e', glyph: 'F' }),
  b('dropper', { props: { facing: ALL6 }, shape: 'cube', color: '#7c7c7c', glyph: 'D' }),
  b('copper_bulb', {
    since: '1.21',
    shape: 'cube',
    color: '#c46e45',
    glyph: 'B',
    aliases: ['exposed_copper_bulb', 'weathered_copper_bulb', 'oxidized_copper_bulb', 'waxed_copper_bulb', 'waxed_exposed_copper_bulb', 'waxed_weathered_copper_bulb', 'waxed_oxidized_copper_bulb'],
  }),
  b('daylight_detector', { props: { inverted: BOOL }, shape: 'daylight', color: '#c9b48c', glyph: 'd' }),
  b('oak_sign', {
    shape: 'sign',
    color: '#b8945f',
    glyph: 'i',
    aliases: ['spruce_sign', 'birch_sign', 'jungle_sign', 'acacia_sign', 'dark_oak_sign', 'mangrove_sign', 'cherry_sign', 'bamboo_sign', 'crimson_sign', 'warped_sign', 'pale_oak_sign'],
  }),
  b('glowstone', { shape: 'cube', color: '#e6c36a', glyph: 'G' }),
]

const BLOCK_BY_KEY: ReadonlyMap<string, CircuitBlock> = new Map(CIRCUIT_BLOCKS.map((x) => [x.key, x]))
export const circuitBlock = (key: string): CircuitBlock | undefined => BLOCK_BY_KEY.get(key)

// ---------------------------------------------------------------- Palette-Einträge

export interface BlockSpec {
  key: string
  /** Eigenschaften, sortiert. */
  props: Record<string, string>
  movable: boolean
  optional: boolean
  marker: string | null
}

/** Liest `id[k=v,…]~?@M` (streng: nur Katalog-Blöcke, nur ihre Eigenschaften und Werte). Fehler → Text. */
export function parseSpec(text: string): BlockSpec | string {
  if (typeof text !== 'string' || text.length === 0 || text.length > 200) return 'invalid block entry'
  let s = text.trim()
  let marker: string | null = null
  const at = s.indexOf('@')
  if (at >= 0) {
    marker = s.slice(at + 1).trim()
    s = s.slice(0, at)
    if (!CIRCUIT_MARKER.test(marker)) return `invalid marker "${marker.slice(0, 16)}"`
  }
  let movable = false
  let optional = false
  while (s.endsWith('~') || s.endsWith('?')) {
    if (s.endsWith('~')) movable = true
    else optional = true
    s = s.slice(0, -1)
  }
  const props: Record<string, string> = {}
  let id = s
  const br = s.indexOf('[')
  if (br >= 0) {
    if (!s.endsWith(']')) return `missing "]" in ${text.slice(0, 60)}`
    id = s.slice(0, br)
    for (const part of s.slice(br + 1, -1).split(',')) {
      if (!part.trim()) continue
      const eq = part.indexOf('=')
      if (eq <= 0) return `property without value in ${text.slice(0, 60)}`
      const k = part.slice(0, eq).trim()
      const v = part.slice(eq + 1).trim()
      if (Object.prototype.hasOwnProperty.call(props, k)) return `duplicate property ${k.slice(0, 20)}`
      props[k] = v
    }
  }
  id = id.trim()
  if (id.startsWith('minecraft:')) id = id.slice(10)
  const block = BLOCK_BY_KEY.get(id)
  if (!block) return `unknown block "${id.slice(0, 40)}"`
  for (const [k, v] of Object.entries(props)) {
    const allowed = Object.prototype.hasOwnProperty.call(block.props, k) ? block.props[k] : undefined
    if (!allowed) return `${id} has no property "${k.slice(0, 20)}"`
    if (!allowed.includes(v)) return `${id}: ${k}=${v.slice(0, 20)} is not allowed`
  }
  const sorted: Record<string, string> = {}
  for (const k of Object.keys(props).sort()) sorted[k] = props[k]!
  return { key: id, props: sorted, movable, optional, marker }
}

/** Kanonische Schreibweise (`key[k=v]~?@M`, Eigenschaften sortiert). */
export function specString(s: BlockSpec, opts: { marker?: boolean, flags?: boolean } = {}): string {
  const keys = Object.keys(s.props).sort()
  let out = s.key
  if (keys.length) out += `[${keys.map((k) => `${k}=${s.props[k]}`).join(',')}]`
  if (opts.flags !== false) {
    if (s.movable) out += '~'
    if (s.optional) out += '?'
  }
  if (opts.marker !== false && s.marker) out += `@${s.marker}`
  return out
}

// ---------------------------------------------------------------- Schaltung

export interface CircuitTexts {
  name?: string
  desc?: string
  note?: string
}

/** Eine geprüfte Schaltung im Client-Format (ohne Server-Felder wie rev/author). */
export interface CircuitData {
  format: 1
  id: string
  category: CircuitCategory
  difficulty: 1 | 2 | 3
  server: 'ok' | 'note'
  since?: string
  until?: string
  texts: Record<string, CircuitTexts>
  palette: Record<string, string>
  layers: string[][]
  tests?: unknown[]
}

export interface Cell {
  x: number
  y: number
  z: number
  spec: BlockSpec
}

export interface CircuitInfo {
  size: { x: number, y: number, z: number }
  cells: Cell[]
  /** Blöcke, die gesetzt werden (ohne `?`). */
  blockCount: number
  /** Höchste Version aus `since` und den Blöcken. */
  since: string
  markers: string[]
}

export type CircuitCheck = { ok: true, circuit: CircuitData, info: CircuitInfo } | { ok: false, errors: string[] }

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** Vergleicht Minecraft-Versionen („1.8.9“ < „1.21“ < „26.1“). */
export function compareVersions(a: string, b: string): number {
  const pa = a.split('.').map((x) => Number.parseInt(x, 10) || 0)
  const pb = b.split('.').map((x) => Number.parseInt(x, 10) || 0)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0)
    if (d !== 0) return d < 0 ? -1 : 1
  }
  return 0
}

/** Sauberer Text: getrimmt, keine Steuerzeichen außer Zeilenumbruch, keine Minecraft-Formatcodes. */
export function textProblem(v: string, max: number): string | null {
  if (v.length > max) return `longer than ${max} characters`
  for (const ch of v) {
    const c = ch.codePointAt(0)!
    if ((c < 32 && c !== 10) || c === 127) return 'contains control characters'
    if (ch === '§') return 'contains formatting codes (§)'
  }
  return null
}

/** Welche Anschluss-Namen ein Test benutzt (für die Prüfung, dass es sie gibt). */
function testMarkers(t: unknown, out: Set<string>, errors: string[], where: string): void {
  const names = (v: unknown) => {
    if (typeof v === 'string') out.add(v)
    else if (Array.isArray(v)) for (const x of v) if (typeof x === 'string') out.add(x)
  }
  if (!isObj(t)) {
    errors.push(`${where}: must be an object`)
    return
  }
  for (const k of Object.keys(t)) if (!['setup', 'truth', 'steps'].includes(k)) errors.push(`${where}: unknown key "${k.slice(0, 20)}"`)
  const truth = t.truth
  if (truth !== undefined) {
    if (!isObj(truth) || !Array.isArray(truth.rows)) errors.push(`${where}: truth needs rows`)
    else {
      if (truth.rows.length > MAX_TEST_STEPS) errors.push(`${where}: more than ${MAX_TEST_STEPS} rows`)
      if (truth.ticks !== undefined && !(Number.isInteger(truth.ticks) && (truth.ticks as number) >= 1 && (truth.ticks as number) <= MAX_TEST_TICKS)) errors.push(`${where}: ticks 1–${MAX_TEST_TICKS}`)
      for (const r of truth.rows) if (typeof r !== 'string' || !/^[0-9a-f]{1,16}:[01]{1,16}$/.test(r)) errors.push(`${where}: bad truth row`)
      names(truth.in)
      names(truth.out)
    }
  }
  for (const key of ['setup', 'steps'] as const) {
    const steps = t[key]
    if (steps === undefined) continue
    if (!Array.isArray(steps)) {
      errors.push(`${where}: ${key} must be a list`)
      continue
    }
    if (steps.length > MAX_TEST_STEPS) errors.push(`${where}: more than ${MAX_TEST_STEPS} steps`)
    for (const s of steps) {
      if (!isObj(s)) {
        errors.push(`${where}: step must be an object`)
        continue
      }
      for (const [k, v] of Object.entries(s)) {
        if (k === 'set' || k === 'expect' || k === 'changed') {
          if (!isObj(v)) errors.push(`${where}: ${k} must be an object`)
          else for (const m of Object.keys(v)) out.add(m)
        } else if (k === 'press' || k === 'bump' || k === 'mark') names(v)
        else if (k === 'run') {
          if (!Number.isInteger(v) || (v as number) < 0 || (v as number) > MAX_TEST_TICKS) errors.push(`${where}: run 0–${MAX_TEST_TICKS}`)
        } else if (k === 'count') {
          if (!isObj(v) || typeof v.out !== 'string' || !Number.isInteger(v.ticks) || (v.ticks as number) < 1 || (v.ticks as number) > MAX_TEST_TICKS) errors.push(`${where}: bad count`)
          else out.add(v.out)
        } else errors.push(`${where}: unknown step "${k.slice(0, 20)}"`)
      }
    }
  }
}

function jsonDepth(v: unknown, depth = 0): number {
  if (depth > 12) return depth
  if (Array.isArray(v)) return Math.max(depth, ...v.map((x) => jsonDepth(x, depth + 1)))
  if (isObj(v)) return Math.max(depth, ...Object.values(v).map((x) => jsonDepth(x, depth + 1)))
  return depth
}

/**
 * Prüft eine Schaltung streng (gleiche Regeln wie `Circuit.parse` im Client, dazu Eigenschaften je Block) und liefert
 * die kanonische Form: Palette-Einträge normalisiert, `format` gesetzt, leere Texte entfernt.
 */
export function checkCircuit(raw: unknown): CircuitCheck {
  const errors: string[] = []
  if (!isObj(raw)) return { ok: false, errors: ['circuit must be a JSON object'] }
  const allowed = new Set(['format', 'id', 'category', 'difficulty', 'server', 'since', 'until', 'texts', 'palette', 'layers', 'tests',
    // Server-Felder, die in exportierten Dateien stehen dürfen (werden verworfen)
    'rev', 'author', 'updatedAt'])
  for (const k of Object.keys(raw)) if (!allowed.has(k)) errors.push(`unknown field "${k.slice(0, 30)}"`)
  if (raw.format !== undefined && raw.format !== CIRCUIT_FORMAT) errors.push(`format must be ${CIRCUIT_FORMAT}`)
  const id = raw.id
  if (typeof id !== 'string' || !CIRCUIT_ID.test(id)) errors.push('id: 1–48 characters a–z, 0–9, _')
  else if (RESERVED_CIRCUIT_IDS.has(id)) errors.push(`id "${id}" is reserved`)
  if (!isCircuitCategory(raw.category)) errors.push(`category must be one of ${CIRCUIT_CATEGORIES.join(', ')}`)
  const difficulty = raw.difficulty ?? 1
  if (difficulty !== 1 && difficulty !== 2 && difficulty !== 3) errors.push('difficulty must be 1, 2 or 3')
  const server = raw.server ?? 'ok'
  if (server !== 'ok' && server !== 'note') errors.push('server must be "ok" or "note"')
  // `null` = keine Angabe (so steht es auch in docs/circuit-format.md).
  for (const k of ['since', 'until'] as const) {
    const v = raw[k]
    if (v !== undefined && v !== null && (typeof v !== 'string' || !CIRCUIT_VERSION.test(v))) errors.push(`${k} must be a version like 1.21.4`)
  }

  // Texte
  const texts: Record<string, CircuitTexts> = {}
  if (raw.texts !== undefined) {
    if (!isObj(raw.texts)) errors.push('texts must be an object')
    else {
      const langs = Object.keys(raw.texts)
      if (langs.length > MAX_LANGUAGES) errors.push(`at most ${MAX_LANGUAGES} languages`)
      for (const lang of langs.slice(0, MAX_LANGUAGES)) {
        const t = raw.texts[lang]
        if (!CIRCUIT_LANG.test(lang)) {
          errors.push(`texts: bad language "${lang.slice(0, 10)}"`)
          continue
        }
        if (!isObj(t)) {
          errors.push(`texts.${lang} must be an object`)
          continue
        }
        const out: CircuitTexts = {}
        for (const [k, v] of Object.entries(t)) {
          const max = k === 'name' ? MAX_NAME : k === 'desc' ? MAX_DESC : k === 'note' ? MAX_NOTE : 0
          if (!max) {
            errors.push(`texts.${lang}: unknown key "${k.slice(0, 20)}"`)
            continue
          }
          if (v === null || v === undefined) continue
          if (typeof v !== 'string') {
            errors.push(`texts.${lang}.${k} must be text`)
            continue
          }
          const s = v.replace(/\r\n?/g, '\n').trim()
          const p = textProblem(s, max)
          if (p) errors.push(`texts.${lang}.${k} ${p}`)
          else if (s) out[k as keyof CircuitTexts] = s
        }
        if (Object.keys(out).length) texts[lang] = out
      }
    }
  }

  // Palette
  const palette = new Map<string, BlockSpec>()
  const paletteOut: Record<string, string> = {}
  if (!isObj(raw.palette)) errors.push('palette is missing')
  else {
    const entries = Object.entries(raw.palette)
    if (entries.length > MAX_PALETTE) errors.push(`palette has more than ${MAX_PALETTE} entries`)
    for (const [ch, v] of entries.slice(0, MAX_PALETTE + 1)) {
      if (ch.length !== 1 || ch === '.' || ch <= ' ' || ch > '~') {
        errors.push(`palette key "${ch.slice(0, 4)}" must be one printable character (not "." or space)`)
        continue
      }
      if (typeof v !== 'string') {
        errors.push(`palette "${ch}" must be text`)
        continue
      }
      const spec = parseSpec(v)
      if (typeof spec === 'string') errors.push(`palette "${ch}": ${spec}`)
      else {
        palette.set(ch, spec)
        paletteOut[ch] = specString(spec)
      }
    }
  }

  // Schichten
  const cells: Cell[] = []
  let sx = 0
  let sy = 0
  let sz = 0
  const layersOut: string[][] = []
  if (!Array.isArray(raw.layers) || raw.layers.length === 0 || raw.layers.length > MAX_CIRCUIT_SIZE) {
    errors.push(`layers: 1–${MAX_CIRCUIT_SIZE} layers`)
  } else {
    sy = raw.layers.length
    const unknownChars = new Set<string>()
    raw.layers.forEach((rows, y) => {
      if (!Array.isArray(rows) || rows.length > MAX_CIRCUIT_SIZE) {
        errors.push(`layer ${y}: 0–${MAX_CIRCUIT_SIZE} rows`)
        layersOut.push([])
        return
      }
      sz = Math.max(sz, rows.length)
      const outRows: string[] = []
      rows.forEach((row, z) => {
        if (typeof row !== 'string' || row.length > MAX_CIRCUIT_SIZE) {
          errors.push(`layer ${y} row ${z}: text of at most ${MAX_CIRCUIT_SIZE} characters`)
          outRows.push('')
          return
        }
        outRows.push(row)
        sx = Math.max(sx, row.length)
        for (let x = 0; x < row.length; x++) {
          const ch = row[x]!
          if (ch === '.' || ch === ' ') continue
          const spec = palette.get(ch)
          if (!spec) {
            if (!Object.prototype.hasOwnProperty.call(paletteOut, ch)) unknownChars.add(ch)
            continue
          }
          cells.push({ x, y, z, spec })
        }
      })
      layersOut.push(outRows)
    })
    for (const ch of unknownChars) errors.push(`character "${ch}" is not in the palette`)
    if (sx === 0 || sz === 0) errors.push('circuit is empty')
    else if (cells.length === 0 && errors.length === 0) errors.push('circuit has no blocks')
  }

  // Tests
  let tests: unknown[] | undefined
  const usedMarkers = new Set<string>()
  if (raw.tests !== undefined) {
    if (!Array.isArray(raw.tests)) errors.push('tests must be a list')
    else if (raw.tests.length > MAX_TESTS) errors.push(`at most ${MAX_TESTS} tests`)
    else if (jsonDepth(raw.tests) > 8) errors.push('tests are nested too deeply')
    else if (JSON.stringify(raw.tests).length > 32 * 1024) errors.push('tests are too large')
    else {
      raw.tests.forEach((t, i) => testMarkers(t, usedMarkers, errors, `test ${i + 1}`))
      if (raw.tests.length) tests = raw.tests
    }
  }
  const markers = new Set<string>()
  for (const c of cells) if (c.spec.marker) markers.add(c.spec.marker)
  for (const m of usedMarkers) if (!markers.has(m)) errors.push(`test uses marker "${m.slice(0, 10)}" that no block has`)

  if (errors.length) return { ok: false, errors: [...new Set(errors)].slice(0, 30) }

  let since = typeof raw.since === 'string' ? raw.since : '1.8'
  for (const c of cells) {
    const bs = BLOCK_BY_KEY.get(c.spec.key)!.since
    if (compareVersions(bs, since) > 0) since = bs
  }
  const circuit: CircuitData = {
    format: 1,
    id: id as string,
    category: raw.category as CircuitCategory,
    difficulty: difficulty as 1 | 2 | 3,
    server: server as 'ok' | 'note',
    ...(typeof raw.since === 'string' ? { since: raw.since } : {}),
    ...(typeof raw.until === 'string' ? { until: raw.until } : {}),
    texts,
    palette: paletteOut,
    layers: layersOut,
    ...(tests ? { tests } : {}),
  }
  return {
    ok: true,
    circuit,
    info: {
      size: { x: sx, y: sy, z: sz },
      cells,
      blockCount: cells.filter((c) => !c.spec.optional).length,
      since,
      markers: [...markers].sort(),
    },
  }
}

/** Materialliste (ohne `?`-Felder), meiste zuerst. */
export function circuitMaterials(cells: readonly Cell[]): { key: string, item: string, count: number }[] {
  const counts = new Map<string, number>()
  for (const c of cells) if (!c.spec.optional) counts.set(c.spec.key, (counts.get(c.spec.key) ?? 0) + 1)
  return [...counts.entries()]
    .map(([key, count]) => ({ key, item: BLOCK_BY_KEY.get(key)?.item ?? key, count }))
    .sort((a, b) => b.count - a.count || a.key.localeCompare(b.key))
}

/**
 * Kanonischer Inhalt für die Duplikat-Erkennung: belegte Felder relativ zur kleinsten Ecke, sortiert, ohne
 * Anschlüsse und Markierungen. Gleicher Bau = gleicher Text (Palette-Zeichen und Leerraum egal).
 */
export function canonicalContent(cells: readonly Cell[]): string {
  if (!cells.length) return ''
  const minX = Math.min(...cells.map((c) => c.x))
  const minY = Math.min(...cells.map((c) => c.y))
  const minZ = Math.min(...cells.map((c) => c.z))
  return cells
    .map((c) => `${c.x - minX},${c.y - minY},${c.z - minZ}=${specString(c.spec, { marker: false, flags: false })}`)
    .sort()
    .join(';')
}

// ---------------------------------------------------------------- Raster (Editor, Import)

/** Dichtes Raster `cells[y][z][x]` (null = Luft). */
export interface CircuitGrid {
  x: number
  y: number
  z: number
  cells: (BlockSpec | null)[][][]
}

export function emptyGrid(x: number, y: number, z: number): CircuitGrid {
  return { x, y, z, cells: Array.from({ length: y }, () => Array.from({ length: z }, () => Array.from({ length: x }, () => null))) }
}

/** Palette + Schichten → Raster (ungültige Zeichen werden Luft; vorher `checkCircuit` benutzen). */
export function gridOf(c: Pick<CircuitData, 'palette' | 'layers'>): CircuitGrid {
  const pal = new Map<string, BlockSpec>()
  for (const [ch, v] of Object.entries(c.palette)) {
    const s = parseSpec(v)
    if (typeof s !== 'string') pal.set(ch, s)
  }
  const y = Math.max(1, c.layers.length)
  const z = Math.max(1, ...c.layers.map((l) => l.length))
  const x = Math.max(1, ...c.layers.flatMap((l) => l.map((r) => r.length)))
  const g = emptyGrid(x, y, z)
  c.layers.forEach((rows, yy) => rows.forEach((row, zz) => {
    for (let xx = 0; xx < row.length; xx++) {
      const s = pal.get(row[xx]!)
      if (s) g.cells[yy]![zz]![xx] = { ...s, props: { ...s.props } }
    }
  }))
  return g
}

/** Zeichen, die als Palette-Schlüssel taugen (druckbar, kein `.`, kein `"` und `\`). */
const POOL = [...'#-ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!$%&*+/:;<=>^_|~(){}[],\'`?@']

/**
 * Raster → Palette + Schichten. Gleiche Einträge teilen sich ein Zeichen; `prefer` (Eintrag → Zeichen) behält
 * Zeichen einer bestehenden Palette bei. Wirft, wenn es mehr als {@link MAX_PALETTE} verschiedene Einträge gibt.
 */
export function circuitOfGrid(g: CircuitGrid, prefer?: Record<string, string>): Pick<CircuitData, 'palette' | 'layers'> {
  const byEntry = new Map<string, string>()
  const used = new Set<string>()
  const preferred = new Map<string, string>()
  if (prefer) for (const [ch, entry] of Object.entries(prefer)) {
    const s = parseSpec(entry)
    if (typeof s !== 'string' && !preferred.has(specString(s))) preferred.set(specString(s), ch)
  }
  const charFor = (s: BlockSpec): string => {
    const entry = specString(s)
    const have = byEntry.get(entry)
    if (have) return have
    if (byEntry.size >= MAX_PALETTE) throw new Error(`more than ${MAX_PALETTE} different blocks`)
    const candidates = [preferred.get(entry), BLOCK_BY_KEY.get(s.key)?.glyph, ...POOL]
    const ch = candidates.find((c): c is string => !!c && c !== '.' && c !== ' ' && !used.has(c))!
    used.add(ch)
    byEntry.set(entry, ch)
    return ch
  }
  // Zuerst die bevorzugten Zeichen vergeben (stabile Paletten beim Bearbeiten).
  for (const layer of g.cells) for (const row of layer) for (const s of row) {
    if (s && preferred.has(specString(s))) charFor(s)
  }
  const layers = g.cells.map((layer) => layer.map((row) => row.map((s) => (s ? charFor(s) : '.')).join('')))
  const palette: Record<string, string> = {}
  for (const [entry, ch] of byEntry) palette[ch] = entry
  return { palette, layers }
}

/** Raster auf die belegten Felder zuschneiden (mindestens 1×1×1). */
export function trimGrid(g: CircuitGrid): CircuitGrid {
  let x0 = Infinity, y0 = Infinity, z0 = Infinity, x1 = -1, y1 = -1, z1 = -1
  g.cells.forEach((layer, y) => layer.forEach((row, z) => row.forEach((s, x) => {
    if (!s) return
    x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z)
    x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z)
  })))
  if (x1 < 0) return emptyGrid(1, 1, 1)
  const out = emptyGrid(x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1)
  for (let y = y0; y <= y1; y++) for (let z = z0; z <= z1; z++) for (let x = x0; x <= x1; x++) {
    out.cells[y - y0]![z - z0]![x - x0] = g.cells[y]![z]![x]!
  }
  return out
}

/** Raster in der Größe ändern (neue Felder = Luft, überstehende fallen weg). */
export function resizeGrid(g: CircuitGrid, x: number, y: number, z: number): CircuitGrid {
  const out = emptyGrid(x, y, z)
  for (let yy = 0; yy < Math.min(y, g.y); yy++) for (let zz = 0; zz < Math.min(z, g.z); zz++) for (let xx = 0; xx < Math.min(x, g.x); xx++) {
    out.cells[yy]![zz]![xx] = g.cells[yy]![zz]![xx]!
  }
  return out
}

/** Name → Schaltungs-ID (`Mein UND-Gatter!` → `mein_und_gatter`). */
export function slugCircuitId(name: string, fallback = 'circuit'): string {
  const s = name.normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase().replace(/ß/g, 'ss')
    .replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 40)
  return s && !RESERVED_CIRCUIT_IDS.has(s) ? s : `${s || fallback}_circuit`.slice(0, 48)
}
