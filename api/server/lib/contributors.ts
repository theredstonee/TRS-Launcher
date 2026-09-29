// Mitwirkende eines Releases („Thanks to / Danke an“) aus dem Text des GitHub-Releases (oder eines Changelog-Abschnitts).
//
// Der Release-Build hängt an den Release-Text einen Abschnitt wie
//   ## Thanks to / Danke an
//   @alice, @bob
//   <!-- contributors: alice,bob -->
// an. Maschinenlesbar ist der Kommentar (kommagetrennte GitHub-Logins ohne „@“); fehlt er, zählen die `@name` im
// Abschnitt. Jeder Login wird streng geprüft – was nicht wie ein GitHub-Login aussieht, fällt weg. Für die Anzeige
// wird der Abschnitt (samt Kommentar) aus dem Text entfernt, damit die Namen nicht doppelt erscheinen.

/** GitHub-Login: Buchstaben, Ziffern, Bindestrich; beginnt mit Buchstabe/Ziffer, höchstens 39 Zeichen. */
export const GITHUB_LOGIN = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/
/** Mehr Namen zeigt die Website nicht (Schutz vor riesigen Listen). */
export const CONTRIBUTORS_MAX = 100

const COMMENT = /<!--\s*contributors:([\s\S]*?)-->/gi
/** Überschrift des Abschnitts – egal in welcher Sprache sie beginnt („Thanks to / Danke an“, „Danke an“, „Gracias a“). */
// Nur genau diese Wörter, danach Zeilenende oder „/ : ! |“ – „Thanks for playing“ ist kein Danke-Abschnitt.
const THANKS_HEADING = /^(#{1,6})[ \t]+(?:thanks[ \t]+to|danke[ \t]+an|gracias[ \t]+a|thanks|danke|gracias)[ \t]*(?:$|[/:!|])/i
const HEADING = /^(#{1,6})[ \t]/
/** `@login` im Fließtext (auch `[@login](https://github.com/login)`), nicht Teil einer E-Mail-Adresse. */
const MENTION = /(?<![\w.@-])@([A-Za-z0-9][A-Za-z0-9-]*)(?![\w-])/g

export function isGithubLogin(value: string): boolean {
  return GITHUB_LOGIN.test(value)
}

/** Gültige Logins, ohne Dubletten (Groß-/Kleinschreibung egal, erste Schreibweise gewinnt), in Reihenfolge. */
export function cleanLogins(values: Iterable<string>): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const raw of values) {
    const login = raw.trim().replace(/^@/, '')
    if (!isGithubLogin(login)) continue
    const key = login.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    out.push(login)
    if (out.length >= CONTRIBUTORS_MAX) break
  }
  return out
}

export interface ExtractedContributors {
  /** GitHub-Logins (ohne „@“), geprüft. */
  contributors: string[]
  /** Der Text ohne Danke-Abschnitt und ohne Kommentar „contributors:“. */
  markdown: string
}

/**
 * Liest die Mitwirkenden aus einem Markdown-Text: bevorzugt aus `<!-- contributors: … -->`, sonst aus den `@name` im
 * Abschnitt „Thanks to / Danke an / Gracias a“. Gibt den Text ohne diesen Abschnitt und ohne den Kommentar zurück.
 */
export function extractContributors(markdown: string): ExtractedContributors {
  const fromComment: string[] = []
  let text = markdown.replace(COMMENT, (_, list: string) => {
    fromComment.push(...list.split(','))
    return ''
  })

  const fromSection: string[] = []
  const kept: string[] = []
  let sectionLevel = 0
  let inFence = false
  for (const line of text.split(/\r?\n/)) {
    if (/^\s*(?:```|~~~)/.test(line)) inFence = !inFence
    if (!inFence) {
      const thanks = THANKS_HEADING.exec(line)
      const heading = HEADING.exec(line)
      if (thanks) {
        sectionLevel = thanks[1]!.length
        continue
      }
      if (sectionLevel && heading && heading[1]!.length <= sectionLevel) sectionLevel = 0
    }
    if (sectionLevel) {
      for (const m of line.matchAll(MENTION)) fromSection.push(m[1]!)
      continue
    }
    kept.push(line)
  }
  text = kept.join('\n').replace(/\n{3,}/g, '\n\n').trim()

  const fromCommentClean = cleanLogins(fromComment)
  return { contributors: fromCommentClean.length ? fromCommentClean : cleanLogins(fromSection), markdown: text }
}

/** Beide Listen zusammen (erst `a`, dann neue aus `b`), geprüft und ohne Dubletten. */
export function mergeContributors(a: string[], b: string[]): string[] {
  return cleanLogins([...a, ...b])
}
