// Prüft die Screenshots eines Updates (Kommentar „shots:“ in CHANGELOG.md, Dateien in public/news/<version>/):
// lesbare Einträge, höchstens SHOTS_MAX, Datei vorhanden, echtes PNG/WebP passend zur Endung, vernünftige
// Größe – und ab SHOTS_REQUIRED_FROM mindestens ein Bild. Genutzt von scripts/changelog.mjs check und den Tests.

import { existsSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { SHOTS_MAX, SHOTS_REQUIRED_FROM, compareVersions } from '../app/utils/changelog.ts'

/** Größte erlaubte Datei (Launcher liefert sie mit, die Website lädt sie von GitHub). */
export const SHOT_MAX_BYTES = 2 * 1024 * 1024
export const SHOT_MIN_SIZE = { width: 640, height: 360 }
export const SHOT_MAX_SIZE = { width: 3840, height: 2400 }

/**
 * Format und Pixelmaße aus den ersten Bytes: PNG (IHDR) oder WebP (VP8, VP8L, VP8X); sonst `null`.
 * @param {Uint8Array} bytes
 * @returns {{ format: 'png' | 'webp', width: number, height: number } | null}
 */
export function imageInfo(bytes) {
  const b = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  if (b.length >= 24 && b.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) && b.toString('ascii', 12, 16) === 'IHDR') {
    return { format: 'png', width: b.readUInt32BE(16), height: b.readUInt32BE(20) }
  }
  if (b.length >= 30 && b.toString('ascii', 0, 4) === 'RIFF' && b.toString('ascii', 8, 12) === 'WEBP') {
    const chunk = b.toString('ascii', 12, 16)
    if (chunk === 'VP8 ' && b[23] === 0x9d && b[24] === 0x01 && b[25] === 0x2a) {
      return { format: 'webp', width: b.readUInt16LE(26) & 0x3fff, height: b.readUInt16LE(28) & 0x3fff }
    }
    if (chunk === 'VP8L' && b[20] === 0x2f) {
      const bits = b.readUInt32LE(21)
      return { format: 'webp', width: (bits & 0x3fff) + 1, height: ((bits >>> 14) & 0x3fff) + 1 }
    }
    if (chunk === 'VP8X') {
      return { format: 'webp', width: b.readUIntLE(24, 3) + 1, height: b.readUIntLE(27, 3) + 1 }
    }
  }
  return null
}

/**
 * Alle Probleme mit den Screenshots eines Changelog-Abschnitts (leer = alles gut).
 * @param {import('../app/utils/changelog.ts').ChangelogEntry} entry
 * @param {string} publicDir Ordner „public“ des Launchers
 * @returns {string[]}
 */
export function checkShots(entry, publicDir) {
  const version = entry.version ?? ''
  const problems = entry.shotIssues.map(
    (raw) => `Eintrag „${raw}“ ist ungültig – Form: /news/${version}/<datei>.png | English caption | Deutsche Bildunterschrift`,
  )
  if (entry.shots.length > SHOTS_MAX) problems.push(`${entry.shots.length} Screenshots – höchstens ${SHOTS_MAX} je Update.`)
  if (entry.version && compareVersions(entry.version, SHOTS_REQUIRED_FROM) >= 0 && entry.shots.length === 0) {
    problems.push(`Kein Screenshot – ab ${SHOTS_REQUIRED_FROM} braucht jedes Update mindestens einen (Kommentar „shots:“ unter dem Banner).`)
  }
  for (const shot of entry.shots) {
    if (!shot.src.startsWith(`/news/${version}/`)) {
      problems.push(`${shot.src}: gehört nach /news/${version}/.`)
      continue
    }
    const file = join(publicDir, shot.src)
    if (!existsSync(file)) {
      problems.push(`${shot.src}: Datei fehlt (public${shot.src}).`)
      continue
    }
    const size = statSync(file).size
    if (size > SHOT_MAX_BYTES) problems.push(`${shot.src}: ${(size / 1048576).toFixed(1)} MB – höchstens ${SHOT_MAX_BYTES / 1048576} MB.`)
    const info = imageInfo(readFileSync(file).subarray(0, 64))
    const ext = shot.src.endsWith('.webp') ? 'webp' : 'png'
    if (!info) {
      problems.push(`${shot.src}: kein gültiges PNG/WebP.`)
      continue
    }
    if (info.format !== ext) problems.push(`${shot.src}: Inhalt ist ${info.format.toUpperCase()}, Endung .${ext}.`)
    const { width, height } = info
    if (width < SHOT_MIN_SIZE.width || height < SHOT_MIN_SIZE.height || width > SHOT_MAX_SIZE.width || height > SHOT_MAX_SIZE.height) {
      problems.push(
        `${shot.src}: ${width}×${height} px – erlaubt ${SHOT_MIN_SIZE.width}×${SHOT_MIN_SIZE.height} bis ${SHOT_MAX_SIZE.width}×${SHOT_MAX_SIZE.height}.`,
      )
    }
  }
  return problems
}
