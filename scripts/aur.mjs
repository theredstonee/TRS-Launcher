#!/usr/bin/env node
// Erzeugt PKGBUILD + .SRCINFO für das AUR-Paket `trs-launcher-bin`.
//
//   node scripts/aur.mjs --version 0.4.3 --deb out/TRS-Launcher_0.4.3_amd64.deb [--pkgrel 1] [--out <ordner>]
//   node scripts/aur.mjs --srcinfo packaging/aur/trs-launcher-bin/PKGBUILD   (nur .SRCINFO ausgeben)
//
// Vorlage ist packaging/aur/trs-launcher-bin/PKGBUILD; ersetzt werden pkgver,
// pkgrel und die sha256-Prüfsumme des .deb. `.SRCINFO` entspricht der Ausgabe
// von `makepkg --printsrcinfo` (im Arch-Container gegengeprüft).
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const TEMPLATE = path.join(root, 'packaging', 'aur', 'trs-launcher-bin', 'PKGBUILD')

function fail(message) {
  console.error(message)
  process.exit(1)
}

/** Wörter einer Bash-Zuweisung: 'a' "b" c – mit Zeilenumbrüchen in Arrays. */
function words(text) {
  const out = []
  const re = /'([^']*)'|"([^"]*)"|([^\s'"()]+)/g
  let m
  while ((m = re.exec(text))) out.push(m[1] ?? m[2] ?? m[3])
  return out
}

/** Liest die einfachen Zuweisungen (Skalare und Arrays) einer PKGBUILD. */
export function parsePkgbuild(text) {
  const vars = {}
  const lines = text.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const m = /^([a-z_0-9]+)=(.*)$/.exec(lines[i])
    if (!m) continue
    let value = m[2]
    if (value.startsWith('(')) {
      while (!value.trimEnd().endsWith(')') && i + 1 < lines.length) value += '\n' + lines[++i]
      vars[m[1]] = words(value.trim().slice(1, -1))
    } else {
      vars[m[1]] = words(value)[0] ?? ''
    }
  }
  // ${var} in Werten auflösen (pkgver, _pkgname …).
  const expand = (s) => s.replace(/\$\{([a-z_0-9]+)\}/g, (_, k) => (typeof vars[k] === 'string' ? vars[k] : ''))
  for (const [k, v] of Object.entries(vars)) vars[k] = Array.isArray(v) ? v.map(expand) : expand(v)
  return vars
}

/** Gleiche Reihenfolge wie `makepkg --printsrcinfo`. */
export function srcinfo(vars) {
  const lines = [`pkgbase = ${vars.pkgname}`]
  const scalar = (k) => vars[k] && lines.push(`\t${k} = ${vars[k]}`)
  const list = (k) => (vars[k] ?? []).forEach((v) => lines.push(`\t${k} = ${v}`))
  scalar('pkgdesc')
  scalar('pkgver')
  scalar('pkgrel')
  scalar('url')
  list('arch')
  list('license')
  list('makedepends')
  list('depends')
  list('optdepends')
  list('provides')
  list('conflicts')
  list('noextract')
  list('options')
  list('source')
  list('source_x86_64')
  list('sha256sums')
  list('sha256sums_x86_64')
  lines.push('', `pkgname = ${vars.pkgname}`, '')
  return lines.join('\n')
}

export function render(template, { version, pkgrel = '1', sha256 }) {
  if (!/^\d+\.\d+\.\d+([.+~][0-9A-Za-z.]+)?$/.test(version)) fail(`Ungültige Version: ${version}`)
  if (!/^[0-9a-f]{64}$/.test(sha256)) fail('Ungültige SHA-256-Prüfsumme')
  return template
    .replace(/^pkgver=.*$/m, `pkgver=${version}`)
    .replace(/^pkgrel=.*$/m, `pkgrel=${pkgrel}`)
    .replace(/^sha256sums_x86_64=.*$/m, `sha256sums_x86_64=('${sha256}')`)
}

function arg(name) {
  const i = process.argv.indexOf(`--${name}`)
  return i > 0 ? process.argv[i + 1] : undefined
}

const isCli = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
if (isCli) {
  const only = arg('srcinfo')
  if (only) {
    process.stdout.write(srcinfo(parsePkgbuild(fs.readFileSync(only, 'utf8'))))
  } else {
    const version = arg('version')
    const deb = arg('deb')
    if (!version || !deb) fail('Aufruf: --version <x.y.z> --deb <datei.deb> [--pkgrel n] [--out ordner]')
    const sha256 = crypto.createHash('sha256').update(fs.readFileSync(deb)).digest('hex')
    const pkgbuild = render(fs.readFileSync(TEMPLATE, 'utf8'), { version, pkgrel: arg('pkgrel') ?? '1', sha256 })
    const out = arg('out') ?? path.join(root, 'packaging', 'aur', 'out')
    fs.mkdirSync(out, { recursive: true })
    fs.writeFileSync(path.join(out, 'PKGBUILD'), pkgbuild)
    fs.writeFileSync(path.join(out, '.SRCINFO'), srcinfo(parsePkgbuild(pkgbuild)))
    console.log(`PKGBUILD + .SRCINFO für ${version} (sha256 ${sha256}) in ${out}`)
  }
}
