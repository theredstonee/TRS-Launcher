import { afterAll, describe, expect, it } from 'vitest'
import { setLocale } from '../app/utils/i18n'
import { crashDocsPage, DOCS_BASE_URL, docsLang, docsUrl, type DocsPage } from '../app/utils/docs'

describe('docsUrl', () => {
  afterAll(async () => {
    await setLocale('en')
  })

  it('zeigt auf die Startseite der Sprache, wenn keine Seite genannt ist', () => {
    expect(docsUrl(undefined, 'en')).toBe('https://trs-launcher.theredstonee.de/docs/en/')
    expect(docsUrl(undefined, 'de')).toBe('https://trs-launcher.theredstonee.de/docs/de/')
    expect(docsUrl(undefined, 'es')).toBe('https://trs-launcher.theredstonee.de/docs/es/')
  })

  it('baut /docs/<sprache>/<bereich>/<seite>', () => {
    expect(docsUrl('help/crash-helper', 'de')).toBe('https://trs-launcher.theredstonee.de/docs/de/help/crash-helper')
    expect(docsUrl('help/logs', 'es')).toBe(`${DOCS_BASE_URL}/es/help/logs`)
    expect(docsUrl('client/overview', 'en')).toBe(`${DOCS_BASE_URL}/en/client/overview`)
  })

  it('nimmt für Sprachen ohne Docs Englisch', () => {
    for (const locale of ['fr', 'pl', 'pt-BR', 'tr', 'nl', '', 'xx']) {
      expect(docsLang(locale), locale).toBe('en')
      expect(docsUrl('help/linux', locale), locale).toBe(`${DOCS_BASE_URL}/en/help/linux`)
    }
    // Regionale Varianten fallen auf die Grundsprache zurück.
    expect(docsLang('de-AT')).toBe('de')
    expect(docsLang('es-MX')).toBe('es')
  })

  it('folgt ohne Angabe der eingestellten Launcher-Sprache', async () => {
    await setLocale('de')
    expect(docsUrl('help/java')).toBe(`${DOCS_BASE_URL}/de/help/java`)
    await setLocale('pl')
    expect(docsUrl()).toBe(`${DOCS_BASE_URL}/en/`)
    await setLocale('es')
    expect(docsUrl('launcher/settings')).toBe(`${DOCS_BASE_URL}/es/launcher/settings`)
  })

  it('lässt keine fremden Zeichen in die Adresse', () => {
    for (const bad of ['../admin', 'help//logs', 'help/logs?x=1', 'https://evil.example', 'Help/Logs', '/help/logs']) {
      expect(docsUrl(bad as DocsPage, 'de'), bad).toBe(`${DOCS_BASE_URL}/de/`)
    }
  })
})

describe('crashDocsPage', () => {
  it('wählt die passende Hilfe-Seite zum Befund', () => {
    expect(crashDocsPage('wrong_java')).toBe('help/java')
    expect(crashDocsPage('unknown')).toBe('help/common-problems')
    expect(crashDocsPage(null)).toBe('help/common-problems')
    expect(crashDocsPage(undefined)).toBe('help/common-problems')
    expect(crashDocsPage('duplicate_mod')).toBe('help/crash-helper')
    expect(crashDocsPage('out_of_memory')).toBe('help/crash-helper')
  })
})
