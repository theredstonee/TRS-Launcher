// Falle (Vite 8 / oxc): Beim Transformieren sucht oxc die tsconfig auch in der Repo-Wurzel. Deren tsconfig.json
// verweist auf ../.nuxt/tsconfig.*.json (Launcher-App) – in einem frischen Checkout oder Worktree ohne
// `nuxt prepare` in der Wurzel fehlen diese Dateien und der Build bricht mit „Tsconfig not found“ ab.
// Dieses Skript legt dann leere Platzhalter an (.nuxt ist in .gitignore). Vorhandene Dateien bleiben unberührt.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const tsconfig = join(root, 'tsconfig.json')
if (existsSync(tsconfig)) {
  const text = readFileSync(tsconfig, 'utf8')
  const refs = [...text.matchAll(/"path"\s*:\s*"([^"]+)"/g)].map((m) => m[1])
  for (const ref of refs) {
    const file = join(root, ref)
    if (!file.includes(`${join(root, '.nuxt')}`) || existsSync(file)) continue
    mkdirSync(dirname(file), { recursive: true })
    writeFileSync(file, '{ "compilerOptions": {} }\n')
    console.log(`[docs] Platzhalter angelegt: ${file}`)
  }
}
