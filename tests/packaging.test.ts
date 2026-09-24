import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
// @ts-expect-error – Node-Skript ohne Typdeklarationen
import { merge } from '../scripts/updater-manifest.mjs'
// @ts-expect-error – Node-Skript ohne Typdeklarationen
import { parsePkgbuild, render, srcinfo } from '../scripts/aur.mjs'

const root = path.resolve(__dirname, '..')
const read = (rel: string) => fs.readFileSync(path.join(root, rel), 'utf8')

describe('Update-Kanal (latest.json)', () => {
  it('führt Windows und Linux zusammen, AppImage auch unter dem Installer-Schlüssel', () => {
    const manifest = merge({
      version: '1.2.3',
      notes: '  Neu \n',
      repo: 'theredstonee/TRS-Launcher',
      tag: 'v1.2.3',
      now: new Date('2026-09-24T12:00:00.123Z'),
      parts: [
        { platform: 'windows-x86_64', file: 'TRS-Launcher_1.2.3_x64-setup.exe', signature: 'win' },
        { platform: 'linux-x86_64', file: 'TRS-Launcher_1.2.3_amd64.AppImage', signature: 'lin' },
      ],
    })
    expect(manifest.pub_date).toBe('2026-09-24T12:00:00Z')
    expect(manifest.notes).toBe('Neu')
    expect(Object.keys(manifest.platforms).sort()).toEqual(['linux-x86_64', 'linux-x86_64-appimage', 'windows-x86_64'])
    expect(manifest.platforms['windows-x86_64'].url).toBe(
      'https://github.com/theredstonee/TRS-Launcher/releases/download/v1.2.3/TRS-Launcher_1.2.3_x64-setup.exe',
    )
    expect(manifest.platforms['linux-x86_64-appimage']).toEqual(manifest.platforms['linux-x86_64'])
  })
})

describe('AUR-Paket', () => {
  it('committete .SRCINFO passt zur PKGBUILD', () => {
    for (const pkg of ['trs-launcher', 'trs-launcher-bin']) {
      const vars = parsePkgbuild(read(`packaging/aur/${pkg}/PKGBUILD`))
      expect(srcinfo(vars)).toBe(read(`packaging/aur/${pkg}/.SRCINFO`))
    }
  })

  it('setzt Version und Prüfsumme und löst Variablen auf', () => {
    const sha = 'a'.repeat(64)
    const pkgbuild = render(read('packaging/aur/trs-launcher-bin/PKGBUILD'), { version: '9.8.7', pkgrel: '2', sha256: sha })
    const vars = parsePkgbuild(pkgbuild)
    expect(vars.pkgver).toBe('9.8.7')
    expect(vars.pkgrel).toBe('2')
    expect(vars.sha256sums_x86_64).toEqual([sha])
    expect(vars.source_x86_64[0]).toBe(
      'trs-launcher-9.8.7_amd64.deb::https://github.com/theredstonee/TRS-Launcher/releases/download/v9.8.7/TRS-Launcher_9.8.7_amd64.deb',
    )
    expect(vars.depends).toContain('webkit2gtk-4.1')
    expect(vars.optdepends.some((d: string) => d.startsWith('xorg-xrandr:'))).toBe(true)
  })
})
