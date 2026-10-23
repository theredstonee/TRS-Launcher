#!/usr/bin/env node
// Veröffentlicht „TRS Server Rules“ (server-plugin/) im eigenen Kanal (GitHub-Release `server-plugin`).
//
//   node scripts/publish-server-plugin.mjs --dry-run   nur lokal: Manifest + Signatur schreiben und prüfen
//   node scripts/publish-server-plugin.mjs             zusätzlich hochladen (gh release upload --clobber)
//
// Ablauf: Jars mit `./gradlew build` in server-plugin/ nach server-plugin/build/dist bauen,
// dann dieses Skript. Es liest die Jars (`trs-server-rules-<plattform>-<version>.jar` bzw.
// `trs-server-rules-<loader>-<mc>-<version>.jar`), schreibt
// server-plugin.json { version, files: [{ platform, mc?, file, sha256, size }] }, signiert es mit dem
// Schlüssel des Tauri-Updaters (`tauri signer sign`) und prüft die Signatur selbst nach – genau wie
// der Launcher (src-tauri/crates/core/src/server_rules.rs), der den Jar beim Server-Export lädt.
//
// Optionen:
//   --dist <ordner>   Jars (Standard: server-plugin/build/dist)
//   --out <ordner>    Ausgabe für server-plugin.json(.sig) (Standard: <dist>/channel)
//   --force           auch veröffentlichen, wenn die Version nicht neuer ist als im Kanal
//   --repo <o/r>      Standard: theredstonee/TRS-Launcher
//
// Schlüssel: %USERPROFILE%\.tauri\trs-launcher.key (+ .password). Beides wird nur hier
// gelesen und nur per Umgebungsvariable an den Signierer gegeben – nie ausgegeben.

import { spawnSync } from 'node:child_process'
import { createHash, createPublicKey, verify as verifySignature } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const TAG = 'server-plugin'
const MANIFEST = 'server-plugin.json'
const SIGNATURE = `${MANIFEST}.sig`
const PREFIX = 'trs-server-rules-'
/** Plugins laufen versionsübergreifend, Server-Mods gibt es je Minecraft-Version. */
const PLUGINS = ['bukkit', 'bungee', 'velocity', 'sponge']
const MODS = ['fabric', 'neoforge', 'forge']
// Muss zu MAX_JAR_BYTES in src-tauri/crates/core/src/client_mod_update.rs passen.
const MAX_JAR_BYTES = 32 * 1024 * 1024
const VERSION_RE = /^\d{1,9}(\.\d{1,9}){0,3}(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$/
const MC_RE = /^[0-9A-Za-z._-]{1,32}$/
const GRADLE_PROPERTIES = join(ROOT, 'server-plugin', 'gradle.properties')

function fail(message) {
  console.error(`Fehler: ${message}`)
  process.exit(1)
}

function parseArgs(argv) {
  const args = { dryRun: false, force: false, dist: join(ROOT, 'server-plugin', 'build', 'dist'), out: null, repo: 'theredstonee/TRS-Launcher' }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    const value = () => argv[++i] ?? fail(`${a} braucht einen Wert`)
    if (a === '--dry-run') args.dryRun = true
    else if (a === '--force') args.force = true
    else if (a === '--dist') args.dist = resolve(value())
    else if (a === '--out') args.out = resolve(value())
    else if (a === '--repo') args.repo = value()
    else if (a === '--help' || a === '-h') {
      console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').filter((l) => l.startsWith('//')).map((l) => l.slice(3)).join('\n'))
      process.exit(0)
    } else fail(`Unbekannte Option ${a}`)
  }
  args.out ??= join(args.dist, 'channel')
  return args
}

// --- Version --------------------------------------------------------------------

function readPluginVersion() {
  if (!existsSync(GRADLE_PROPERTIES)) fail(`${GRADLE_PROPERTIES} fehlt`)
  const match = readFileSync(GRADLE_PROPERTIES, 'utf8').match(/^plugin_version=(.+)$/m)
  const version = match?.[1]?.trim()
  if (!version || !VERSION_RE.test(version) || version.length > 32) fail(`Ungültige plugin_version "${version ?? ''}"`)
  return version
}

function parseVersion(v) {
  const [core, pre] = v.split(/-(.*)/s)
  return { nums: core.split('.').map(Number), pre: pre || null }
}

/** a > b? (wie `is_newer` im Launcher) */
export function isNewer(a, b) {
  const x = parseVersion(a)
  const y = parseVersion(b)
  const len = Math.max(x.nums.length, y.nums.length)
  for (let i = 0; i < len; i++) {
    const d = (x.nums[i] ?? 0) - (y.nums[i] ?? 0)
    if (d !== 0) return d > 0
  }
  if (x.pre === null) return y.pre !== null
  return y.pre !== null && x.pre > y.pre
}

// --- Manifest --------------------------------------------------------------------

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex')
}

/** Plattform (+ Minecraft-Version) aus dem Jar-Namen; `null` = kein Jar dieser Version. */
export function parseJarName(name, version) {
  const suffix = `-${version}.jar`
  if (!name.startsWith(PREFIX) || !name.endsWith(suffix)) return null
  const middle = name.slice(PREFIX.length, -suffix.length)
  if (PLUGINS.includes(middle)) return { platform: middle }
  const dash = middle.indexOf('-')
  if (dash < 0) return null
  const platform = middle.slice(0, dash)
  const mc = middle.slice(dash + 1)
  if (!MODS.includes(platform) || !MC_RE.test(mc)) return null
  return { platform, mc }
}

function readFiles(dist, version) {
  if (!existsSync(dist)) fail(`${dist} fehlt – erst in server-plugin/ \`./gradlew build\` laufen lassen`)
  const files = []
  const seen = new Map()
  for (const name of readdirSync(dist).sort()) {
    if (!name.endsWith('.jar')) continue
    const parsed = parseJarName(name, version)
    if (!parsed) {
      console.warn(`Übersprungen (nicht Version ${version} oder unbekanntes Schema): ${name}`)
      continue
    }
    const key = `${parsed.platform} ${parsed.mc ?? '*'}`
    if (seen.has(key)) fail(`${key} doppelt (${seen.get(key)} und ${name})`)
    seen.set(key, name)
    const file = join(dist, name)
    const size = statSync(file).size
    if (size === 0 || size > MAX_JAR_BYTES) fail(`${name}: Größe ${size} außerhalb der Grenzen`)
    files.push({ platform: parsed.platform, ...(parsed.mc ? { mc: parsed.mc } : {}), file: name, sha256: sha256(file), size })
  }
  if (files.length === 0) fail(`keine Jars der Version ${version} in ${dist}`)
  const order = [...PLUGINS, ...MODS]
  files.sort((a, b) => order.indexOf(a.platform) - order.indexOf(b.platform) || (a.mc ?? '').localeCompare(b.mc ?? '', 'en', { numeric: true }))
  return files
}

// --- Signatur --------------------------------------------------------------------

function sign(file) {
  const keyPath = join(homedir(), '.tauri', 'trs-launcher.key')
  const passwordPath = `${keyPath}.password`
  if (!existsSync(keyPath)) fail(`Signier-Schlüssel fehlt: ${keyPath}`)
  const raw = existsSync(passwordPath) ? readFileSync(passwordPath, 'utf8') : ''
  const cli = join(ROOT, 'node_modules', '@tauri-apps', 'cli', 'tauri.js')
  if (!existsSync(cli)) fail('Tauri-CLI fehlt – erst `pnpm install`')
  // Zuerst ohne Zeilenende am Ende der Passwortdatei, notfalls genau wie gespeichert.
  const candidates = [...new Set([raw.replace(/\r?\n$/, ''), raw])]
  let last = null
  for (const password of candidates) {
    const env = { ...process.env, TAURI_SIGNING_PRIVATE_KEY_PATH: keyPath, TAURI_SIGNING_PRIVATE_KEY_PASSWORD: password }
    delete env.TAURI_SIGNING_PRIVATE_KEY
    last = spawnSync(process.execPath, [cli, 'signer', 'sign', file], { env, stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' })
    if (last.status === 0 && existsSync(`${file}.sig`)) return
  }
  fail(`Signieren fehlgeschlagen (${last?.status}):\n${last?.stderr ?? ''}`)
}

/** Prüft die Signatur wie der Launcher (Minisign, Prehash, file:server-plugin.json). */
function verifyLikeTheLauncher(data, sigFileContent) {
  const conf = JSON.parse(readFileSync(join(ROOT, 'src-tauri', 'tauri.conf.json'), 'utf8'))
  const pubLines = Buffer.from(conf.plugins.updater.pubkey, 'base64').toString('utf8').split(/\r?\n/)
  const pub = Buffer.from(pubLines[1], 'base64')
  const sigLines = Buffer.from(sigFileContent.trim(), 'base64').toString('utf8').split(/\r?\n/)
  const sig = Buffer.from(sigLines[1], 'base64')
  const trusted = sigLines[2]?.replace(/^trusted comment: /, '') ?? ''
  const globalSig = Buffer.from(sigLines[3] ?? '', 'base64')
  if (pub.length !== 42 || sig.length !== 74 || globalSig.length !== 64) fail('Signatur/Schlüssel im falschen Format')
  if (sig.subarray(0, 2).toString() !== 'ED') fail('Signatur ist keine Prehash-Signatur')
  if (!sig.subarray(2, 10).equals(pub.subarray(2, 10))) fail('Signatur stammt von einem anderen Schlüssel als in tauri.conf.json')
  const key = createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), pub.subarray(10)]), format: 'der', type: 'spki' })
  const digest = createHash('blake2b512').update(data).digest()
  const ok =
    verifySignature(null, digest, key, sig.subarray(10)) &&
    verifySignature(null, Buffer.concat([sig.subarray(10), Buffer.from(trusted)]), key, globalSig)
  if (!ok) fail('Signatur ungültig')
  if (!trusted.split('\t').includes(`file:${MANIFEST}`)) fail(`signierter Kommentar nennt nicht file:${MANIFEST}`)
}

// --- GitHub ------------------------------------------------------------------------

async function channelVersion(repo) {
  try {
    const res = await fetch(`https://github.com/${repo}/releases/download/${TAG}/${MANIFEST}`, { signal: AbortSignal.timeout(10_000) })
    if (res.status === 404) return null
    if (!res.ok) return undefined
    return (await res.json()).version ?? null
  } catch {
    return undefined
  }
}

function gh(args, { allowFail = false } = {}) {
  const r = spawnSync('gh', args, { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' })
  if (r.error) fail(`gh nicht gefunden: ${r.error.message}`)
  if (r.status !== 0 && !allowFail) fail(`gh ${args.slice(0, 2).join(' ')} fehlgeschlagen:\n${r.stderr}`)
  return r
}

function upload(repo, dist, out, files) {
  if (gh(['auth', 'status'], { allowFail: true }).status !== 0) fail('gh ist nicht angemeldet (`gh auth login`)')
  if (gh(['release', 'view', TAG, '-R', repo], { allowFail: true }).status !== 0) {
    gh([
      'release', 'create', TAG, '-R', repo, '--prerelease', '--latest=false',
      '--title', 'TRS Server Rules – server plugin channel',
      '--notes', 'Maintained automatically: the TRS Server Rules server plugin / server mods (lock or force TRS Client features on your server). ' +
        'Pick the jar for your platform (see server-plugin/README.md); server-plugin.json is signed with the launcher key and lets the TRS Launcher install it when exporting a server.',
    ])
    console.log(`Release "${TAG}" angelegt.`)
  }
  // Erst die Jars, dann Manifest + Signatur: wer zwischendurch lädt, bekommt
  // höchstens eine Prüfsummen-Abweichung und der Export meldet einen Fehler.
  const jars = files.map((f) => join(dist, f.file))
  for (let i = 0; i < jars.length; i += 40) {
    gh(['release', 'upload', TAG, '-R', repo, '--clobber', ...jars.slice(i, i + 40)])
    console.log(`  ${Math.min(i + 40, jars.length)}/${jars.length} Jars hochgeladen`)
  }
  gh(['release', 'upload', TAG, '-R', repo, '--clobber', join(out, SIGNATURE), join(out, MANIFEST)])
  // Jars, die es nicht mehr gibt (alte Versionen), aus dem Release nehmen.
  const assets = JSON.parse(gh(['release', 'view', TAG, '-R', repo, '--json', 'assets']).stdout).assets.map((a) => a.name)
  const keep = new Set([MANIFEST, SIGNATURE, ...files.map((f) => f.file)])
  for (const name of assets.filter((n) => !keep.has(n))) {
    gh(['release', 'delete-asset', TAG, name, '-R', repo, '--yes'])
    console.log(`  alte Datei entfernt: ${name}`)
  }
}

// --- Ablauf ------------------------------------------------------------------------

async function main() {
  const args = parseArgs(process.argv.slice(2))
  const version = readPluginVersion()
  const files = readFiles(args.dist, version)
  const manifestText = `${JSON.stringify({ version, files }, null, 2)}\n`
  console.log(`TRS Server Rules ${version}: ${files.length} Jars aus ${args.dist}`)
  for (const f of files) console.log(`  ${f.platform.padEnd(9)} ${(f.mc ?? '').padEnd(8)} ${f.file}`)

  const current = await channelVersion(args.repo)
  if (current === undefined) console.log('Kanal: aktueller Stand nicht abrufbar (offline?)')
  else console.log(`Kanal: ${current ?? 'noch leer'}`)
  if (current && !isNewer(version, current) && !args.force) {
    const msg = `Version ${version} ist nicht neuer als ${current} im Kanal – erst plugin_version in server-plugin/gradle.properties erhöhen und neu bauen (oder --force).`
    if (args.dryRun) console.warn(`Warnung: ${msg}`)
    else fail(msg)
  }

  mkdirSync(args.out, { recursive: true })
  const manifestFile = join(args.out, MANIFEST)
  writeFileSync(manifestFile, manifestText)
  rmSync(`${manifestFile}.sig`, { force: true })
  sign(manifestFile)
  verifyLikeTheLauncher(readFileSync(manifestFile), readFileSync(`${manifestFile}.sig`, 'utf8'))
  console.log(`Signiert und geprüft: ${manifestFile}(.sig)`)

  if (args.dryRun) {
    console.log('Dry-Run: nichts hochgeladen.')
  } else {
    upload(args.repo, args.dist, args.out, files)
    console.log(`Veröffentlicht: https://github.com/${args.repo}/releases/tag/${TAG}`)
  }
}

// Nur beim direkten Aufruf laufen (Tests importieren parseJarName/isNewer).
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await main()
