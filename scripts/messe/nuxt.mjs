#!/usr/bin/env node
// Nuxt für den Messe-Build starten (`generate` oder `dev`) – mit TRS_MESSE=1, damit Kiosk-Ebene und Präsentation
// eingebaut werden. Fehlt die Präsentation (.messe-pitch/), kommt der Platzhalter.
// Aufruf über src-tauri/tauri.messe.conf.json (beforeBuildCommand/beforeDevCommand).
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..', '..')
const task = process.argv[2] === 'dev' ? 'dev' : 'generate'

if (!fs.existsSync(path.join(root, '.messe-pitch', 'index.html'))) {
  const r = spawnSync(process.execPath, [path.join(here, 'import-pitch.mjs')], { cwd: root, stdio: 'inherit' })
  if (r.status !== 0) process.exit(r.status ?? 1)
}

const r = spawnSync('pnpm', ['exec', 'nuxt', task], {
  cwd: root,
  stdio: 'inherit',
  shell: process.platform === 'win32',
  env: { ...process.env, TRS_MESSE: '1' },
})
process.exit(r.status ?? 1)
