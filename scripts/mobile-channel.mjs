#!/usr/bin/env node
// Update-Kanal der mobilen App (GitHub-Release `mobile`): schreibt mobile.json, signiert es mit dem Schlüssel
// des Tauri-Updaters (wie client-mod.json), prüft die Signatur selbst nach und erzeugt die AltStore-/SideStore-Quelle.
//
//   node scripts/mobile-channel.mjs --version 0.18.0 --notes release-notes.md \
//     [--apk out/TRS-Launcher_0.18.0.apk] [--ipa out/TRS-Launcher_0.18.0.ipa] [--out out/mobile] [--repo o/r] [--date ISO]
//
// Ergebnis in --out (Standard: out/mobile):
//   mobile.json        { version, notes, pubDate, android: { url, sha256, size }, ios: { url, sha256, size, altstore } }
//   mobile.json.sig    Minisign-Signatur (Base64, Format von `tauri signer sign`, Kommentar file:mobile.json)
//   altstore.json      AltStore-/SideStore-Quelle (Source v2) mit der IPA
//   TRS-Launcher-<version>.apk / .ipa   Kopien unter dem Namen, den mobile.json nennt
// Hochgeladen wird im Release-Workflow (gh release upload mobile … --clobber).
//
// Schlüssel: im CI TAURI_SIGNING_PRIVATE_KEY (+ _PASSWORD) aus den Secrets; lokal
// %USERPROFILE%\.tauri\trs-launcher.key (+ .password) wie bei publish-client-mod.mjs. Nie ausgegeben.

import { spawnSync } from 'node:child_process'
import { createHash, createPublicKey, verify as verifySignature } from 'node:crypto'
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const TAG = 'mobile'
const MANIFEST = 'mobile.json'
const SIGNATURE = `${MANIFEST}.sig`
const ALTSTORE = 'altstore.json'
const BUNDLE_ID = 'dev.theredstonee.trslauncher'
// Muss zu MAX_APK_BYTES / MAX_NOTES_CHARS in src-tauri/crates/core/src/mobile_update.rs passen.
const MAX_FILE_BYTES = 400 * 1024 * 1024
const MAX_NOTES_CHARS = 20_000
const VERSION_RE = /^\d{1,9}(\.\d{1,9}){0,3}(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$/
const REPO_RE = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/
const MIN_IOS = '15.0'

function fail(message) {
  console.error(`Fehler: ${message}`)
  process.exit(1)
}

function parseArgs(argv) {
  const args = { version: null, notes: null, apk: null, ipa: null, out: join(ROOT, 'out', 'mobile'), repo: 'theredstonee/TRS-Launcher', date: null }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    const value = () => argv[++i] ?? fail(`${a} braucht einen Wert`)
    if (a === '--version') args.version = value()
    else if (a === '--notes') args.notes = resolve(value())
    else if (a === '--apk') args.apk = resolve(value())
    else if (a === '--ipa') args.ipa = resolve(value())
    else if (a === '--out') args.out = resolve(value())
    else if (a === '--repo') args.repo = value()
    else if (a === '--date') args.date = value()
    else fail(`Unbekannte Option ${a}`)
  }
  if (!args.version || !VERSION_RE.test(args.version) || args.version.length > 32) fail('--version fehlt oder ist ungültig')
  if (!REPO_RE.test(args.repo)) fail('--repo ist ungültig')
  if (!args.apk && !args.ipa) fail('mindestens --apk oder --ipa angeben')
  return args
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex')
}

/** Kopiert die Datei unter dem Kanal-Namen nach `out` und liefert den Eintrag für mobile.json. */
function asset(file, ext, args) {
  if (!existsSync(file)) fail(`${file} fehlt`)
  const size = statSync(file).size
  if (size === 0 || size > MAX_FILE_BYTES) fail(`${file}: Größe ${size} außerhalb der Grenzen`)
  const name = `TRS-Launcher-${args.version}.${ext}`
  copyFileSync(file, join(args.out, name))
  return { url: `https://github.com/${args.repo}/releases/download/${TAG}/${name}`, sha256: sha256(file), size }
}

// --- Signatur (wie publish-client-mod.mjs) ------------------------------------------------

function sign(file) {
  const cli = join(ROOT, 'node_modules', '@tauri-apps', 'cli', 'tauri.js')
  if (!existsSync(cli)) fail('Tauri-CLI fehlt – erst `pnpm install`')
  const candidates = []
  if (process.env.TAURI_SIGNING_PRIVATE_KEY) {
    candidates.push({ ...process.env })
  } else {
    const keyPath = join(homedir(), '.tauri', 'trs-launcher.key')
    if (!existsSync(keyPath)) fail(`Signier-Schlüssel fehlt: ${keyPath} (oder TAURI_SIGNING_PRIVATE_KEY setzen)`)
    const raw = existsSync(`${keyPath}.password`) ? readFileSync(`${keyPath}.password`, 'utf8') : ''
    for (const password of new Set([raw.replace(/\r?\n$/, ''), raw])) {
      const env = { ...process.env, TAURI_SIGNING_PRIVATE_KEY_PATH: keyPath, TAURI_SIGNING_PRIVATE_KEY_PASSWORD: password }
      delete env.TAURI_SIGNING_PRIVATE_KEY
      candidates.push(env)
    }
  }
  let last = null
  for (const env of candidates) {
    last = spawnSync(process.execPath, [cli, 'signer', 'sign', file], { env, stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' })
    if (last.status === 0 && existsSync(`${file}.sig`)) return
  }
  fail(`Signieren fehlgeschlagen (${last?.status}):\n${last?.stderr ?? ''}`)
}

/** Prüft die Signatur wie die App (Minisign, Prehash, file:mobile.json, Schlüssel aus tauri.conf.json). */
function verifyLikeTheApp(data, sigFileContent) {
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

// --- AltStore-/SideStore-Quelle (Source v2) ------------------------------------------------

function altstoreSource(args, notes, pubDate, ios) {
  const raw = `https://raw.githubusercontent.com/${args.repo}/main/src-tauri/icons/ios/AppIcon-512@2x.png`
  const description =
    'Companion app of the TRS Launcher: accounts, friends and chat, skins and capes, modpacks and news. ' +
    'Minecraft itself still starts in the desktop version.'
  return {
    name: 'TRS Launcher',
    identifier: `${BUNDLE_ID}.source`,
    subtitle: 'Minecraft launcher by TheRedstonee',
    description,
    iconURL: raw,
    website: 'https://trs-launcher.theredstonee.de',
    tintColor: '#e0281e',
    featuredApps: [BUNDLE_ID],
    apps: [
      {
        name: 'TRS Launcher',
        bundleIdentifier: BUNDLE_ID,
        developerName: 'TheRedstonee',
        subtitle: 'Minecraft launcher companion',
        localizedDescription: description,
        iconURL: raw,
        tintColor: '#e0281e',
        category: 'games',
        screenshots: [],
        versions: [
          {
            version: args.version,
            buildVersion: args.version,
            date: pubDate,
            localizedDescription: notes,
            downloadURL: ios.url,
            size: ios.size,
            sha256: ios.sha256,
            minOSVersion: MIN_IOS,
          },
        ],
        appPermissions: {
          entitlements: [],
          privacy: {},
        },
      },
    ],
    news: [],
  }
}

// --- Ablauf ------------------------------------------------------------------------------------

const args = parseArgs(process.argv.slice(2))
const notes = (args.notes && existsSync(args.notes) ? readFileSync(args.notes, 'utf8') : '').trim().slice(0, MAX_NOTES_CHARS)
const pubDate = args.date ?? new Date().toISOString()
if (Number.isNaN(Date.parse(pubDate)) || pubDate.length > 64) fail('--date ist kein gültiges Datum')

mkdirSync(args.out, { recursive: true })
const manifest = { version: args.version, notes, pubDate }
if (args.apk) manifest.android = asset(args.apk, 'apk', args)
if (args.ipa) {
  manifest.ios = { ...asset(args.ipa, 'ipa', args), altstore: `https://github.com/${args.repo}/releases/download/${TAG}/${ALTSTORE}` }
  const source = altstoreSource(args, notes, pubDate, manifest.ios)
  writeFileSync(join(args.out, ALTSTORE), `${JSON.stringify(source, null, 2)}\n`)
}

const manifestFile = join(args.out, MANIFEST)
writeFileSync(manifestFile, `${JSON.stringify(manifest, null, 2)}\n`)
rmSync(join(args.out, SIGNATURE), { force: true })
sign(manifestFile)
verifyLikeTheApp(readFileSync(manifestFile), readFileSync(join(args.out, SIGNATURE), 'utf8'))
console.log(`mobile.json ${args.version} signiert und geprüft (${[manifest.android && 'Android', manifest.ios && 'iOS'].filter(Boolean).join(' + ')})`)
