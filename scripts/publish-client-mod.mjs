#!/usr/bin/env node
// Veröffentlicht den TRS Client im eigenen Update-Kanal (GitHub-Release `client-mod`).
//
//   node scripts/publish-client-mod.mjs --dry-run      nur lokal: Manifest + Signatur schreiben und prüfen
//   node scripts/publish-client-mod.mjs                 zusätzlich hochladen (gh release upload --clobber)
//   node scripts/publish-client-mod.mjs --bump minor   mod_version in allen gradle.properties erhöhen
//
// Ablauf: Jars mit `collectLauncherJars` nach client-mod/dist bauen (Projekte nacheinander),
// dann dieses Skript. Es liest dist/builds.json + dist/builds-*.json, schreibt
// client-mod.json { version, builds[] mit sha256/size }, signiert es mit dem Schlüssel
// des Tauri-Updaters (`tauri signer sign`) und prüft die Signatur selbst nach.
// Dieselbe Datei (ohne Signatur) landet mit den Jars in src-tauri/resources/client-mod,
// damit der nächste Launcher-Release den Stand mitbringt.
//
// Optionen:
//   --dist <ordner>   Jars + builds*.json (Standard: client-mod/dist)
//   --out <ordner>    Ausgabe für client-mod.json(.sig) (Standard: client-mod/dist/channel)
//   --no-bundle       src-tauri/resources/client-mod nicht anfassen
//   --force           auch veröffentlichen, wenn die Version nicht neuer ist als im Kanal
//   --repo <o/r>      Standard: theredstonee/TRS-Launcher
//
// Schlüssel: %USERPROFILE%\.tauri\trs-launcher.key (+ .password). Beides wird nur hier
// gelesen und nur per Umgebungsvariable an den Signierer gegeben – nie ausgegeben.

import { spawnSync } from 'node:child_process'
import { createHash, createPublicKey, verify as verifySignature } from 'node:crypto'
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const TAG = 'client-mod'
const MANIFEST = 'client-mod.json'
const SIGNATURE = `${MANIFEST}.sig`
const LOADERS = new Set(['fabric', 'forge', 'neoforge'])
// Muss zu MAX_JAR_BYTES in src-tauri/crates/core/src/client_mod_update.rs passen.
const MAX_JAR_BYTES = 32 * 1024 * 1024
const VERSION_RE = /^\d{1,9}(\.\d{1,9}){0,3}(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$/
const FILE_RE = /^[A-Za-z0-9][A-Za-z0-9._+-]*\.jar$/
const BUNDLE_DIR = join(ROOT, 'src-tauri', 'resources', 'client-mod')
const GRADLE_PROPERTIES = join(ROOT, 'client-mod', 'gradle.properties')

function fail(message) {
  console.error(`Fehler: ${message}`)
  process.exit(1)
}

function parseArgs(argv) {
  const args = {
    dryRun: false,
    bundle: true,
    force: false,
    bump: null,
    dist: join(ROOT, 'client-mod', 'dist'),
    out: null,
    repo: 'theredstonee/TRS-Launcher',
  }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    const value = () => argv[++i] ?? fail(`${a} braucht einen Wert`)
    if (a === '--dry-run') args.dryRun = true
    else if (a === '--no-bundle') args.bundle = false
    else if (a === '--force') args.force = true
    else if (a === '--bump') args.bump = value()
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

/** Alle gradle.properties mit mod_version (Wurzel + Projekte mit eigenem Build). */
function versionFiles() {
  const dir = join(ROOT, 'client-mod')
  const files = [GRADLE_PROPERTIES]
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const file = join(dir, entry.name, 'gradle.properties')
    if (entry.isDirectory() && existsSync(file) && /^mod_version=/m.test(readFileSync(file, 'utf8'))) files.push(file)
  }
  return files
}

function readModVersion() {
  const versions = new Map()
  for (const file of versionFiles()) {
    const match = readFileSync(file, 'utf8').match(/^mod_version=(.+)$/m)
    if (match) versions.set(file, match[1].trim())
  }
  const distinct = [...new Set(versions.values())]
  if (distinct.length !== 1) {
    fail(`mod_version ist nicht überall gleich:\n${[...versions].map(([f, v]) => `  ${v}  ${f}`).join('\n')}`)
  }
  const version = distinct[0]
  if (!VERSION_RE.test(version) || version.length > 32) fail(`Ungültige mod_version "${version}"`)
  return version
}

function parseVersion(v) {
  const [core, pre] = v.split(/-(.*)/s)
  return { nums: core.split('.').map(Number), pre: pre || null }
}

/** a > b? */
function isNewer(a, b) {
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

function bump(kind) {
  const current = readModVersion()
  const [major, minor, patch] = [...parseVersion(current).nums, 0, 0, 0]
  const next = { major: `${major + 1}.0.0`, minor: `${major}.${minor + 1}.0`, patch: `${major}.${minor}.${patch + 1}` }[kind]
  if (!next) fail('--bump braucht major, minor oder patch')
  for (const file of versionFiles()) {
    const text = readFileSync(file, 'utf8')
    writeFileSync(file, text.replace(/^mod_version=.*$/m, `mod_version=${next}`))
  }
  console.log(`mod_version ${current} → ${next} (${versionFiles().length} Dateien).`)
  console.log('Jetzt die Jars neu bauen (collectLauncherJars je Projekt), dann veröffentlichen.')
}

// --- Manifest --------------------------------------------------------------------

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex')
}

function readBuilds(dist) {
  if (!existsSync(dist)) fail(`${dist} fehlt – erst collectLauncherJars laufen lassen`)
  const lists = readdirSync(dist)
    .filter((f) => f === 'builds.json' || /^builds-.+\.json$/.test(f))
    // builds.json (Fabric) zuerst, dann die anderen alphabetisch – wie bisher im Launcher-Paket.
    .sort((a, b) => (a === 'builds.json' ? -1 : b === 'builds.json' ? 1 : a.localeCompare(b)))
  if (lists.length === 0) fail(`keine builds*.json in ${dist}`)
  const builds = []
  const seen = new Map()
  for (const list of lists) {
    const entries = JSON.parse(readFileSync(join(dist, list), 'utf8'))
    if (!Array.isArray(entries)) fail(`${list} ist keine Liste`)
    for (const e of entries) {
      if (!LOADERS.has(e.loader)) fail(`${list}: unbekannter Loader ${e.loader}`)
      if (typeof e.file !== 'string' || !FILE_RE.test(e.file)) fail(`${list}: unsicherer Dateiname ${e.file}`)
      if (!Array.isArray(e.minecraft) || e.minecraft.length === 0) fail(`${list}: ${e.file} ohne Minecraft-Version`)
      for (const mc of e.minecraft) {
        const key = `${e.loader} ${mc}`
        if (seen.has(key)) fail(`${key} doppelt (${seen.get(key)} und ${e.file})`)
        seen.set(key, e.file)
      }
      const file = join(dist, e.file)
      if (!existsSync(file)) fail(`${e.file} fehlt in ${dist}`)
      const size = statSync(file).size
      if (size === 0 || size > MAX_JAR_BYTES) fail(`${e.file}: Größe ${size} außerhalb der Grenzen`)
      const { loader, minecraft, file: name, requires = [], ...extra } = e
      builds.push({ loader, minecraft, file: name, requires, ...extra, sha256: sha256(file), size })
    }
  }
  return builds
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

/** Prüft die Signatur wie der Launcher (Minisign, Prehash, file:client-mod.json). */
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

// --- Launcher-Paket ------------------------------------------------------------

function bundle(dist, manifestText, builds) {
  mkdirSync(BUNDLE_DIR, { recursive: true })
  const wanted = new Set(builds.map((b) => b.file))
  for (const f of readdirSync(BUNDLE_DIR)) {
    if (f.endsWith('.jar') && !wanted.has(f)) rmSync(join(BUNDLE_DIR, f))
  }
  let copied = 0
  for (const b of builds) {
    const target = join(BUNDLE_DIR, b.file)
    if (!existsSync(target) || sha256(target) !== b.sha256) {
      copyFileSync(join(dist, b.file), target)
      copied++
    }
  }
  writeFileSync(join(BUNDLE_DIR, 'builds.json'), manifestText)
  console.log(`Launcher-Paket: ${BUNDLE_DIR} (${copied} Jars aktualisiert, builds.json im neuen Format)`)
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

function upload(repo, dist, out, builds) {
  if (gh(['auth', 'status'], { allowFail: true }).status !== 0) fail('gh ist nicht angemeldet (`gh auth login`)')
  if (gh(['release', 'view', TAG, '-R', repo], { allowFail: true }).status !== 0) {
    gh([
      'release', 'create', TAG, '-R', repo, '--prerelease', '--latest=false',
      '--title', 'TRS Client – Update-Kanal',
      '--notes', 'Wird automatisch gepflegt: neue Versionen des TRS Clients (In-Game-Mod) für den TRS Launcher. ' +
        'client-mod.json ist mit dem Launcher-Schlüssel signiert, der Launcher lädt die Jars selbst – nichts davon von Hand installieren.',
    ])
    console.log(`Release "${TAG}" angelegt.`)
  }
  // Erst die Jars, dann Manifest + Signatur: wer zwischendurch lädt, bekommt
  // höchstens eine Prüfsummen-Abweichung und nimmt die mitgelieferte Version.
  const jars = builds.map((b) => join(dist, b.file))
  for (let i = 0; i < jars.length; i += 40) {
    gh(['release', 'upload', TAG, '-R', repo, '--clobber', ...jars.slice(i, i + 40)])
    console.log(`  ${Math.min(i + 40, jars.length)}/${jars.length} Jars hochgeladen`)
  }
  gh(['release', 'upload', TAG, '-R', repo, '--clobber', join(out, SIGNATURE), join(out, MANIFEST)])
  // Jars, die es nicht mehr gibt, aus dem Release nehmen.
  const assets = JSON.parse(gh(['release', 'view', TAG, '-R', repo, '--json', 'assets']).stdout).assets.map((a) => a.name)
  const keep = new Set([MANIFEST, SIGNATURE, ...builds.map((b) => b.file)])
  for (const name of assets.filter((n) => !keep.has(n))) {
    gh(['release', 'delete-asset', TAG, name, '-R', repo, '--yes'])
    console.log(`  alte Datei entfernt: ${name}`)
  }
}

// --- Ablauf ------------------------------------------------------------------------

const args = parseArgs(process.argv.slice(2))
if (args.bump) {
  bump(args.bump)
  process.exit(0)
}

const version = readModVersion()
const builds = readBuilds(args.dist)
const manifestText = `${JSON.stringify({ version, builds }, null, 2)}\n`
console.log(`TRS Client ${version}: ${builds.length} Builds aus ${args.dist}`)

const current = await channelVersion(args.repo)
if (current === undefined) console.log('Kanal: aktueller Stand nicht abrufbar (offline?)')
else console.log(`Kanal: ${current ?? 'noch leer'}`)
if (current && !isNewer(version, current) && !args.force) {
  const msg = `Version ${version} ist nicht neuer als ${current} im Kanal – Launcher würden sie ignorieren. Erst \`--bump patch\` und neu bauen (oder --force).`
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

if (args.bundle) bundle(args.dist, manifestText, builds)

if (args.dryRun) {
  console.log('Dry-Run: nichts hochgeladen.')
} else {
  upload(args.repo, args.dist, args.out, builds)
  console.log(`Veröffentlicht: https://github.com/${args.repo}/releases/tag/${TAG}`)
}
console.log(`Fertig (${basename(args.out)}).`)
