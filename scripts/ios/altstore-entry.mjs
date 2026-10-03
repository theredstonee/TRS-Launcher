#!/usr/bin/env node
// Baut den App-Eintrag für die AltStore/SideStore-Quelle (Format „source v2“) aus der fertigen IPA.
// AltStore prüft, dass Entitlements und Datenschutz-Schlüssel (NS…UsageDescription) genau zur IPA
// passen – deshalb werden beide aus der IPA gelesen (package-ipa.sh/CI liefert sie als JSON).
//
//   node scripts/ios/altstore-entry.mjs app \
//     --version 0.18.0 --date 2026-10-03 --url https://…/TRS-Launcher.ipa --ipa TRS-Launcher.ipa \
//     --info-plist info.json --entitlements entitlements.json [--notes notes.md] [--min-os 14.0]
//       → App-Objekt (für "apps" einer Quelle) auf stdout
//   node scripts/ios/altstore-entry.mjs source <app.json> [--source-url https://…/altstore.json]
//       → komplette Quelle mit genau dieser App
//
// info.json:         `plutil -convert json -o info.json Payload/TRS.app/Info.plist`
// entitlements.json: `ldid -e Payload/TRS.app/<exe> > e.plist && plutil -convert json -o entitlements.json e.plist`
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

export const BUNDLE_ID = 'dev.theredstonee.trslauncher'
const SOURCE_ID = 'dev.theredstonee.trslauncher.source'
const TINT = '#E5322D'
const ICON_URL = 'https://raw.githubusercontent.com/theredstonee/TRS-Launcher/main/src-tauri/icons/icon.png'
const DESCRIPTION =
  'TRS Launcher for iPhone and iPad: Minecraft Java Edition with your instances, modpacks and the TRS Client – plus friends, chat and your wardrobe. ' +
  'Playing needs JIT (SideStore + StikDebug, AltServer or TrollStore) and your own Minecraft account.'

function fail(message) {
  throw new Error(message)
}

/** Nur die Schlüssel, die AltStore als Datenschutz-Angaben erwartet. */
export function privacyFromInfoPlist(info) {
  const privacy = {}
  for (const [key, value] of Object.entries(info ?? {})) {
    if (/^NS[A-Za-z]+UsageDescription$/.test(key) && typeof value === 'string') privacy[key] = value
  }
  return privacy
}

/** Entitlement-Namen (ohne Signatur-interne Schlüssel, die AltStore selbst setzt). */
export function entitlementNames(entitlements) {
  const ignored = new Set(['application-identifier', 'com.apple.developer.team-identifier', 'keychain-access-groups'])
  return Object.keys(entitlements ?? {})
    .filter((key) => !ignored.has(key))
    .sort()
}

export function versionEntry({ version, buildVersion, date, notes, url, size, sha256, minOSVersion = '14.0' }) {
  if (!/^\d+\.\d+\.\d+([-.][0-9A-Za-z.]+)?$/.test(version ?? '')) fail(`Ungültige Version: ${version}`)
  if (!/^\d{4}-\d{2}-\d{2}/.test(date ?? '')) fail(`Ungültiges Datum: ${date}`)
  if (!/^https:\/\//.test(url ?? '')) fail('Download-URL muss https sein')
  if (!Number.isInteger(size) || size <= 0) fail('Ungültige Größe')
  if (!/^[0-9a-f]{64}$/.test(sha256 ?? '')) fail('Ungültige SHA-256')
  const entry = {
    version,
    date,
    localizedDescription: (notes ?? '').trim() || `TRS Launcher ${version}`,
    downloadURL: url,
    size,
    sha256,
    minOSVersion,
  }
  if (buildVersion) entry.buildVersion = String(buildVersion)
  return entry
}

export function appEntry({ info, entitlements, ...version }) {
  if (info?.CFBundleIdentifier && info.CFBundleIdentifier !== BUNDLE_ID) fail(`Falsche Bundle-ID: ${info.CFBundleIdentifier}`)
  return {
    name: 'TRS Launcher',
    bundleIdentifier: BUNDLE_ID,
    developerName: 'TheRedstonee',
    subtitle: 'Minecraft Java launcher with TRS Client',
    localizedDescription: DESCRIPTION,
    iconURL: ICON_URL,
    tintColor: TINT,
    category: 'games',
    versions: [versionEntry({ buildVersion: info?.CFBundleVersion, ...version })],
    appPermissions: {
      entitlements: entitlementNames(entitlements),
      privacy: privacyFromInfoPlist(info),
    },
  }
}

export function sourceJson(app, { sourceURL } = {}) {
  const source = {
    name: 'TRS Launcher',
    identifier: SOURCE_ID,
    subtitle: 'Official TRS Launcher builds',
    iconURL: ICON_URL,
    tintColor: TINT,
    apps: [app],
    news: [],
  }
  if (sourceURL) source.sourceURL = sourceURL
  return source
}

function option(args, name) {
  const i = args.indexOf(name)
  return i >= 0 ? args[i + 1] : undefined
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

const isCli = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
if (isCli) {
  const [command, ...args] = process.argv.slice(2)
  try {
    if (command === 'app') {
      const ipa = option(args, '--ipa')
      if (!ipa) fail('--ipa fehlt')
      const bytes = fs.readFileSync(ipa)
      const notesFile = option(args, '--notes')
      const app = appEntry({
        version: option(args, '--version'),
        date: option(args, '--date') ?? new Date().toISOString().slice(0, 10),
        url: option(args, '--url'),
        minOSVersion: option(args, '--min-os') ?? '14.0',
        notes: notesFile ? fs.readFileSync(notesFile, 'utf8') : '',
        size: bytes.length,
        sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
        info: readJson(option(args, '--info-plist') ?? fail('--info-plist fehlt')),
        entitlements: readJson(option(args, '--entitlements') ?? fail('--entitlements fehlt')),
      })
      process.stdout.write(JSON.stringify(app, null, 2) + '\n')
    } else if (command === 'source') {
      const appFile = args[0] ?? fail('Aufruf: source <app.json> [--source-url …]')
      process.stdout.write(JSON.stringify(sourceJson(readJson(appFile), { sourceURL: option(args, '--source-url') }), null, 2) + '\n')
    } else {
      fail('Befehle: app | source')
    }
  } catch (e) {
    console.error(e instanceof Error ? e.message : e)
    process.exit(1)
  }
}
