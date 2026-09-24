#!/usr/bin/env node
// Baut `latest.json` für den Tauri-Updater aus den Teilen der Build-Jobs.
//
//   node scripts/updater-manifest.mjs platform <schlüssel> <datei>
//       → JSON-Teil {platform, file, signature} (Signatur aus <datei>.sig)
//   node scripts/updater-manifest.mjs merge <version> <notes.md> <owner/repo> <tag> <teil.json>…
//       → fertiges latest.json auf stdout
//
// Linux: Nur das AppImage kann sich selbst ersetzen. Es steht unter
// `linux-x86_64` und zusätzlich unter `linux-x86_64-appimage` (danach sucht
// der Updater zuerst). .deb/.rpm/AUR/Flatpak aktualisiert die Paketverwaltung.
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const ALIASES = { 'linux-x86_64': ['linux-x86_64-appimage'] }

function fail(message) {
  console.error(message)
  process.exit(1)
}

export function platformEntry(key, file) {
  if (!/^[a-z0-9_-]+$/.test(key)) fail(`Ungültiger Plattform-Schlüssel: ${key}`)
  const signature = fs.readFileSync(`${file}.sig`, 'utf8').trim()
  if (!signature) fail(`Leere Signatur: ${file}.sig`)
  return { platform: key, file: path.basename(file), signature }
}

export function merge({ version, notes, repo, tag, parts, now = new Date() }) {
  const platforms = {}
  for (const part of parts) {
    const url = `https://github.com/${repo}/releases/download/${tag}/${encodeURIComponent(part.file)}`
    for (const key of [part.platform, ...(ALIASES[part.platform] ?? [])]) {
      if (platforms[key]) fail(`Plattform doppelt: ${key}`)
      platforms[key] = { signature: part.signature, url }
    }
  }
  if (!Object.keys(platforms).length) fail('Keine Plattformen')
  return {
    version,
    notes: notes.trim(),
    pub_date: now.toISOString().replace(/\.\d+Z$/, 'Z'),
    platforms,
  }
}

const isCli = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
const [command, ...args] = isCli ? process.argv.slice(2) : []
if (command === 'platform') {
  const [key, file] = args
  if (!key || !file) fail('Aufruf: platform <schlüssel> <datei>')
  process.stdout.write(JSON.stringify(platformEntry(key, file), null, 2) + '\n')
} else if (command === 'merge') {
  const [version, notesFile, repo, tag, ...files] = args
  if (!version || !notesFile || !repo || !tag || !files.length) fail('Aufruf: merge <version> <notes.md> <owner/repo> <tag> <teil.json>…')
  const parts = files.map((f) => JSON.parse(fs.readFileSync(f, 'utf8')))
  const manifest = merge({ version, notes: fs.readFileSync(notesFile, 'utf8'), repo, tag, parts })
  process.stdout.write(JSON.stringify(manifest, null, 2) + '\n')
} else if (command) {
  fail(`Unbekannter Befehl: ${command}`)
}
