// Changelog für den Release-Build: gibt die Hinweise einer Version aus (Englisch + Deutsch)
// und bricht ab, wenn der Abschnitt fehlt, eine Sprache leer ist oder das Update-Banner fehlt
// (Zeile „<!-- banner: accent=#rrggbb motif=/news/<version>/banner.png -->“ + Bild in public/).
//
//   node --experimental-strip-types scripts/changelog.mjs notes 0.4.4   → Markdown für Release/Update
//   node --experimental-strip-types scripts/changelog.mjs check 0.4.4   → nur prüfen
//
// Der Parser ist derselbe wie im Launcher (app/utils/changelog.ts).

import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { changelogFor, parseChangelog, releaseNotes } from '../app/utils/changelog.ts'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const [command, rawVersion] = process.argv.slice(2)

if (!['notes', 'check'].includes(command) || !rawVersion) {
  console.error('Aufruf: changelog.mjs notes|check <version>')
  process.exit(2)
}

const version = rawVersion.replace(/^v/, '')
const entry = changelogFor(parseChangelog(readFileSync(join(root, 'CHANGELOG.md'), 'utf8')), version)
if (!entry) {
  console.error(`CHANGELOG.md hat keinen Abschnitt „## ${version} – <Datum>“. Bitte vor dem Release eintragen.`)
  process.exit(1)
}
const missing = [!entry.en && 'English', !entry.de && 'Deutsch'].filter(Boolean)
if (missing.length) {
  console.error(`CHANGELOG.md, Version ${version}: Teil „${missing.join('“ und „')}“ ist leer.`)
  process.exit(1)
}
const banner = entry.banner
if (!banner?.motif || !existsSync(join(root, 'public', banner.motif))) {
  console.error(
    [
      `CHANGELOG.md, Version ${version}: Update-Banner fehlt. Unter der Überschrift eintragen:`,
      `  <!-- banner: accent=#rrggbb motif=/news/${version}/banner.png -->`,
      `und das Motiv (TRS Studio, Vorlage „Update-Banner“) als public/news/${version}/banner.png ablegen.`,
    ].join('\n'),
  )
  process.exit(1)
}
if (command === 'notes') process.stdout.write(releaseNotes(entry))
