// Changelog für den Release-Build: gibt die Hinweise einer Version aus (Englisch + Deutsch)
// und bricht ab, wenn der Abschnitt fehlt, eine Sprache leer ist oder das Update-Banner fehlt
// (Zeile „<!-- banner: accent=#rrggbb motif=/news/<version>/banner.png -->“ + Bild in public/) oder die
// Screenshots nicht passen (Kommentar „<!-- shots: … -->“, ab 0.6.5 Pflicht, siehe scripts/news-shots.mjs).
//
//   node --experimental-strip-types scripts/changelog.mjs notes 0.4.4   → Markdown für Release/Update
//   node --experimental-strip-types scripts/changelog.mjs check 0.4.4   → nur prüfen
//   node --experimental-strip-types scripts/changelog.mjs set-contributors 0.4.4 alice,bob
//       → schreibt „<!-- contributors: alice,bob -->“ in den Abschnitt (ändert CHANGELOG.md; leere Liste = nichts
//         tun). Die Namen liefert scripts/contributors.mjs; der Release-Build ruft beides vor „notes“ auf.
//   node --experimental-strip-types scripts/changelog.mjs pr <CHANGELOG.md der Ziel-Branch>
//       → PR-Prüfung (CI): neuer Punkt unter „## Unreleased“ in Englisch UND Deutsch (scripts/changelog-pr.mjs)
//
// Der Parser ist derselbe wie im Launcher (app/utils/changelog.ts).

import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { changelogFor, parseChangelog, releaseNotes, setContributors } from '../app/utils/changelog.ts'
import { checkPrChangelog } from './changelog-pr.mjs'
import { checkShots } from './news-shots.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const changelogPath = join(root, 'CHANGELOG.md')
const [command, arg, extra] = process.argv.slice(2)

if (command === 'pr') {
  if (!arg) {
    console.error('Aufruf: changelog.mjs pr <CHANGELOG.md der Ziel-Branch>')
    process.exit(2)
  }
  const base = existsSync(arg) ? readFileSync(arg, 'utf8') : ''
  const problems = checkPrChangelog(base, readFileSync(changelogPath, 'utf8'))
  if (problems.length) {
    console.error(
      [
        'CHANGELOG.md needs an entry for this pull request:',
        ...problems.map((p) => `  - ${p}`),
        '',
        'Describe what changes for players under "## Unreleased", in English and German (same points):',
        '  ### English',
        '  - **Log search.** The log view now finds text in folded stack traces too.',
        '  ### Deutsch',
        '  - **Log-Suche.** Die Log-Ansicht findet Text jetzt auch in eingeklappten Stacktraces.',
        '',
        'Nothing players notice (CI, refactoring, docs)? Ask for the label "no-changelog".',
        'More: CONTRIBUTING.md and https://trs-launcher.theredstonee.de/docs/en/developers/contributing',
      ].join('\n'),
    )
    process.exit(1)
  }
  console.log('CHANGELOG.md: new points under "## Unreleased" in English and German.')
  process.exit(0)
}

if (!['notes', 'check', 'set-contributors'].includes(command) || !arg) {
  console.error('Aufruf: changelog.mjs notes|check <version> | set-contributors <version> <a,b> | pr <datei>')
  process.exit(2)
}

const version = arg.replace(/^v/, '')

if (command === 'set-contributors') {
  try {
    const text = readFileSync(changelogPath, 'utf8')
    const next = setContributors(text, version, (extra ?? '').split(','))
    if (next !== text) writeFileSync(changelogPath, next)
    const names = changelogFor(parseChangelog(next), version)?.contributors ?? []
    console.error(`CHANGELOG.md, Version ${version}: Mitwirkende ${names.length ? names.join(', ') : '(keine)'}`)
  } catch (e) {
    console.error(e instanceof Error ? e.message : String(e))
    process.exit(1)
  }
  process.exit(0)
}

const entry = changelogFor(parseChangelog(readFileSync(changelogPath, 'utf8')), version)
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
const shotProblems = checkShots(entry, join(root, 'public'))
if (shotProblems.length) {
  console.error(
    [
      `CHANGELOG.md, Version ${version}: Screenshots passen nicht.`,
      ...shotProblems.map((p) => `  - ${p}`),
      'Unter der Banner-Zeile eintragen (Dateien in public/news/<version>/, PNG oder WebP):',
      '  <!-- shots:',
      `  /news/${version}/<datei>.png | English caption | Deutsche Bildunterschrift`,
      '  -->',
    ].join('\n'),
  )
  process.exit(1)
}
if (command === 'notes') process.stdout.write(releaseNotes(entry))
