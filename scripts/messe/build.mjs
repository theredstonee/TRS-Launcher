#!/usr/bin/env node
// Baut den Messe-Build als einzelne EXE (ohne Installer, ohne Updater) und legt sie mit Anleitung ab.
// Aufruf: node scripts/messe/build.mjs [--pitch <Ordner>] [--out <Ordner>] [--debug] [--with-client-mod]
//   --pitch            Präsentations-Paket (index.html + assets/) – sonst bleibt das zuletzt importierte bzw. der Platzhalter
//   --out              Zielordner (Standard: release/messe) → TRS-Launcher-Messe.exe + README.txt
//   --debug            Debug-Build (schneller gebaut, nur zum Testen)
//   --with-client-mod  mitgelieferte TRS-Client-Jars (src-tauri/resources/client-mod, ~500 MB) daneben kopieren
// CARGO_TARGET_DIR wird beachtet.
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..', '..')
const args = process.argv.slice(2)
const opt = (name) => {
  const i = args.indexOf(name)
  return i >= 0 ? args[i + 1] : undefined
}
const debug = args.includes('--debug')
const out = path.resolve(opt('--out') ?? path.join(root, 'release', 'messe'))
const pitch = opt('--pitch')

function run(cmd, cmdArgs, env = {}) {
  const r = spawnSync(cmd, cmdArgs, { cwd: root, stdio: 'inherit', shell: process.platform === 'win32', env: { ...process.env, ...env } })
  if (r.status !== 0) {
    console.error(`[messe] fehlgeschlagen: ${cmd} ${cmdArgs.join(' ')}`)
    process.exit(r.status ?? 1)
  }
}

if (pitch || !fs.existsSync(path.join(root, '.messe-pitch', 'index.html'))) {
  run(process.execPath, [path.join(here, 'import-pitch.mjs'), ...(pitch ? [pitch] : [])])
}

const tauriArgs = ['tauri', 'build', '--config', 'src-tauri/tauri.messe.conf.json', '--no-bundle']
if (debug) tauriArgs.push('--debug')
run('pnpm', tauriArgs, { TRS_MESSE: '1' })

const targetDir = process.env.CARGO_TARGET_DIR ? path.resolve(process.env.CARGO_TARGET_DIR) : path.join(root, 'src-tauri', 'target')
const binary = path.join(targetDir, debug ? 'debug' : 'release', process.platform === 'win32' ? 'trs-launcher.exe' : 'trs-launcher')
if (!fs.existsSync(binary)) {
  console.error(`[messe] EXE nicht gefunden: ${binary}`)
  process.exit(1)
}
fs.mkdirSync(out, { recursive: true })
const exe = path.join(out, process.platform === 'win32' ? 'TRS-Launcher-Messe.exe' : 'trs-launcher-messe')
fs.copyFileSync(binary, exe)
fs.copyFileSync(path.join(here, 'README.txt'), path.join(out, 'README.txt'))
if (args.includes('--with-client-mod')) {
  fs.cpSync(path.join(root, 'src-tauri', 'resources', 'client-mod'), path.join(out, 'client-mod'), { recursive: true })
}
const source = JSON.parse(fs.readFileSync(path.join(root, '.messe-pitch', '.source.json'), 'utf8'))
console.log(`[messe] fertig: ${exe} (${(fs.statSync(exe).size / 1048576).toFixed(1)} MB, ${debug ? 'debug' : 'release'}, Präsentation: ${source.source})`)
