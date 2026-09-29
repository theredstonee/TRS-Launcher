// Changelog-Prüfung für Pull Requests (CI-Job „changelog“, Aufruf über scripts/changelog.mjs pr):
// Der PR muss unter „## Unreleased“ sowohl unter „### English“ als auch unter „### Deutsch“ mindestens einen
// neuen (oder geänderten) Punkt haben. Verglichen wird mit CHANGELOG.md der Ziel-Branch.
// Abschalten per PR-Label „no-changelog“ (das entscheidet der Workflow, nicht dieses Skript).
// Meldungen auf Englisch – sie lesen Mitwirkende im CI-Log.

import { parseChangelog } from '../app/utils/changelog.ts'

/**
 * Oberste Listenpunkte eines Markdown-Texts, je Punkt eine Zeile mit zusammengezogenen Leerzeichen.
 * Eingerückte Folgezeilen und Unterpunkte gehören zum Punkt davor.
 * @param {string} markdown
 * @returns {string[]}
 */
export function listPoints(markdown) {
  const points = []
  let current = null
  for (const line of markdown.split(/\r?\n/)) {
    if (/^[-*+] +\S/.test(line)) {
      if (current !== null) points.push(current)
      current = line.replace(/^[-*+] +/, '')
    } else if (current !== null && line.trim()) {
      current += ` ${line.trim()}`
    } else if (current !== null && !line.trim()) {
      points.push(current)
      current = null
    }
  }
  if (current !== null) points.push(current)
  return points.map((p) => p.replace(/\s+/g, ' ').trim()).filter(Boolean)
}

/**
 * Der Abschnitt „## Unreleased“ (per Parser – die Zeichenkette im Anleitungs-Kommentar oben zählt nicht), sonst `null`.
 * @param {string} text
 */
export function unreleasedSection(text) {
  return parseChangelog(text).find((entry) => entry.version === null) ?? null
}

/**
 * Punkte unter „## Unreleased“, die im PR neu sind oder geändert wurden (gegenüber `baseText`).
 * @param {string} baseText CHANGELOG.md der Ziel-Branch (leer, wenn es sie dort nicht gibt)
 * @param {string} headText CHANGELOG.md mit den Änderungen des PRs
 * @returns {{ en: string[], de: string[] } | null} `null`, wenn der PR keinen Abschnitt „## Unreleased“ hat
 */
export function newUnreleasedPoints(baseText, headText) {
  const head = unreleasedSection(headText)
  if (!head) return null
  const base = unreleasedSection(baseText)
  const fresh = (lang) => {
    const old = new Set(base ? listPoints(base[lang]) : [])
    return listPoints(head[lang]).filter((point) => !old.has(point))
  }
  return { en: fresh('en'), de: fresh('de') }
}

/**
 * Probleme mit dem Changelog eines PRs – leer, wenn alles passt.
 * @param {string} baseText
 * @param {string} headText
 * @returns {string[]}
 */
export function checkPrChangelog(baseText, headText) {
  const added = newUnreleasedPoints(baseText, headText)
  if (!added) return ['CHANGELOG.md has no "## Unreleased" section. Add it above the newest version.']
  const problems = []
  if (!added.en.length) problems.push('No new point under "## Unreleased" → "### English".')
  if (!added.de.length) problems.push('No new point under "## Unreleased" → "### Deutsch".')
  return problems
}
