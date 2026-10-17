import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { crc32 } from 'node:zlib'
import type { AppContext } from './context'
import { one, run } from './db'
import { resolveInside } from './private-assets'

/**
 * Halloween-Schreck-Erweiterung, Part A – der persönliche Gag für MiyakoMC (einvernehmlich). ALLES hier ist privat und
 * nur für sein Konto: jede Route antwortet für jedes andere Konto mit 404, nichts darf durchsickern. Die Medien liegen
 * NICHT im Repo, sondern unter `<PRIVATE_ASSETS_DIR>/miyako/` (`frames/0001.jpg … 0320.jpg`, `intro.ogg`, `jumpscare.png`);
 * ein fehlender Ordner darf den Start nie stören (nur diese Routen lesen ihn, und sie geben dann 404).
 */

/** Nur dieses Konto (MiyakoMC). */
export const MIYAKO_UUID = '7f5eb4a770b24574ad99382d42163623'

/** Zeitfenster (ms) für den zufälligen Schreck-Timer – der Client würfelt selbst (z. B. 60–300 s). */
export const MIYAKO_TIMER_MAX_MS = 300_000

/** Höchstgröße je Einzeldatei beim Packen (großzügig; die echten Dateien sind klein). */
const MAX_FILE_BYTES = 8 * 1024 * 1024

/** Konto-Flag „Intro einmal gesehen“. */
const INTRO_FLAG = 'miyako_intro_done'

export function isMiyako(uuid: string): boolean {
  return uuid === MIYAKO_UUID
}

/** Hat MiyakoMC das Intro schon gesehen? (Standard false). */
export function miyakoIntroDone(ctx: AppContext, uuid: string): boolean {
  return one(ctx.db, 'SELECT 1 AS x FROM user_event_flags WHERE uuid = ? AND flag = ?', uuid, INTRO_FLAG) !== undefined
}

/** Setzt das Intro-Flag (idempotent). */
export function setMiyakoIntroDone(ctx: AppContext, uuid: string): void {
  run(ctx.db, 'INSERT INTO user_event_flags (uuid, flag, set_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING', uuid, INTRO_FLAG, ctx.now())
}

// ---------------------------------------------------------------- Bündel (ZIP aus den privaten Dateien)

function isDir(path: string): boolean {
  try {
    return statSync(path).isDirectory()
  } catch {
    return false
  }
}

/** Liest eine private Datei mit Obergrenze. `null` = fehlt, zu groß oder unlesbar. */
function readPrivate(root: string, parts: readonly string[]): Buffer | null {
  const path = resolveInside(root, parts)
  if (!path) return null
  try {
    const st = statSync(path)
    if (!st.isFile() || st.size > MAX_FILE_BYTES) return null
    return readFileSync(path)
  } catch {
    return null
  }
}

interface ZipEntry {
  name: string
  data: Buffer
}

/**
 * Minimaler ZIP-Writer (Methode „store“, ohne Kompression): JPEG/OGG/PNG sind schon komprimiert, Deflate brächte nichts.
 * Reicht für ein Bündel, das der Client einmal lädt und lokal entpackt.
 */
function buildStoreZip(entries: ZipEntry[]): Buffer {
  const locals: Buffer[] = []
  const centrals: Buffer[] = []
  let offset = 0
  for (const { name, data } of entries) {
    const nameBuf = Buffer.from(name, 'utf8')
    const crc = crc32(data) >>> 0
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4) // version needed
    local.writeUInt16LE(0, 6) // flags
    local.writeUInt16LE(0, 8) // method: store
    local.writeUInt16LE(0, 10) // mod time
    local.writeUInt16LE(0x21, 12) // mod date (1980-01-01)
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(data.length, 18) // compressed size
    local.writeUInt32LE(data.length, 22) // uncompressed size
    local.writeUInt16LE(nameBuf.length, 26)
    local.writeUInt16LE(0, 28) // extra len
    locals.push(local, nameBuf, data)

    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4) // version made by
    central.writeUInt16LE(20, 6) // version needed
    central.writeUInt16LE(0, 8) // flags
    central.writeUInt16LE(0, 10) // method: store
    central.writeUInt16LE(0, 12) // mod time
    central.writeUInt16LE(0x21, 14) // mod date
    central.writeUInt32LE(crc, 16)
    central.writeUInt32LE(data.length, 20)
    central.writeUInt32LE(data.length, 24)
    central.writeUInt16LE(nameBuf.length, 28)
    central.writeUInt16LE(0, 30) // extra len
    central.writeUInt16LE(0, 32) // comment len
    central.writeUInt16LE(0, 34) // disk number start
    central.writeUInt16LE(0, 36) // internal attrs
    central.writeUInt32LE(0, 38) // external attrs
    central.writeUInt32LE(offset, 42) // local header offset
    centrals.push(central, nameBuf)

    offset += local.length + nameBuf.length + data.length
  }
  const centralSize = centrals.reduce((n, b) => n + b.length, 0)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(0, 4) // disk number
  end.writeUInt16LE(0, 6) // disk with central dir
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(centralSize, 12)
  end.writeUInt32LE(offset, 16) // central dir offset
  end.writeUInt16LE(0, 20) // comment len
  return Buffer.concat([...locals, ...centrals, end])
}

/**
 * Baut das gegatete Bündel (Frames + intro.ogg + jumpscare.png) aus `<PRIVATE_ASSETS_DIR>/miyako/`.
 * `null`, wenn der private Ordner fehlt oder eine Pflichtdatei nicht lesbar ist (die Route gibt dann 404).
 */
export function miyakoBundle(ctx: AppContext): { buf: Buffer, sha256: string } | null {
  const root = ctx.config.privateAssetsDir
  const framesDir = resolveInside(root, ['miyako', 'frames'])
  if (!framesDir || !isDir(framesDir)) return null
  let names: string[]
  try {
    names = readdirSync(framesDir).filter((n) => /^\d{4}\.jpg$/.test(n)).sort()
  } catch {
    return null
  }
  if (names.length === 0) return null
  const entries: ZipEntry[] = []
  for (const n of names) {
    const data = readPrivate(root, ['miyako', 'frames', n])
    if (!data) return null
    entries.push({ name: `frames/${n}`, data })
  }
  const ogg = readPrivate(root, ['miyako', 'intro.ogg'])
  const png = readPrivate(root, ['miyako', 'jumpscare.png'])
  if (!ogg || !png) return null
  entries.push({ name: 'intro.ogg', data: ogg }, { name: 'jumpscare.png', data: png })
  const buf = buildStoreZip(entries)
  return { buf, sha256: createHash('sha256').update(buf).digest('hex') }
}
