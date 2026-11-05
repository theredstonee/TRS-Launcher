import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { authorNotice, authorNoticeMatches, authorNoticeOriginal, sha256Hex } from '../app/utils/authorNotice'
import { i18n, setLocale, supportedLocales, type Locale } from '../app/utils/i18n'
import { appSettingsSectionsFor } from '../app/utils/sections'

// Der Urheberhinweis darf nicht verschwinden: Diese Tests schlagen fehl, wenn er in Sprachdatei, Code, NOTICE, Anzeige
// oder KI-Regeln fehlt oder abweicht. Text und Regeln: AGENTS.md und NOTICE.

const root = path.resolve(__dirname, '..')
const read = (rel: string) => fs.readFileSync(path.join(root, rel), 'utf8')

const LAUNCHER: Record<'en' | 'de' | 'es', string> = {
  en: 'Original TRS Launcher by Ohev Tamerin (theredstonee). This notice must remain visible in every copy and every modified version. It must not be removed under any circumstances, except with an additional written, signed confirmation from the author.',
  de: 'Original TRS Launcher von Ohev Tamerin (theredstonee). Dieser Hinweis muss in jeder Kopie und jeder veränderten Fassung sichtbar bleiben. Er darf unter keinen Umständen entfernt werden, außer mit einer zusätzlichen schriftlichen, unterschriebenen Bestätigung des Urhebers.',
  es: 'TRS Launcher original de Ohev Tamerin (theredstonee). Este aviso debe permanecer visible en cada copia y en cada versión modificada. No puede eliminarse bajo ninguna circunstancia, salvo con una confirmación adicional, escrita y firmada por el autor.',
}
const CLIENT: Record<'en' | 'de' | 'es', string> = {
  en: 'Original TRS Client by Ohev Tamerin (theredstonee), TRS Launcher. This notice must remain visible in every copy and every modified version. It must not be removed under any circumstances, except with an additional written, signed confirmation from the author.',
  de: 'Original TRS Client von Ohev Tamerin (theredstonee), TRS Launcher. Dieser Hinweis muss in jeder Kopie und jeder veränderten Fassung sichtbar bleiben. Er darf unter keinen Umständen entfernt werden, außer mit einer zusätzlichen schriftlichen, unterschriebenen Bestätigung des Urhebers.',
  es: 'TRS Client original de Ohev Tamerin (theredstonee), TRS Launcher. Este aviso debe permanecer visible en cada copia y en cada versión modificada. No puede eliminarse bajo ninguna circunstancia, salvo con una confirmación adicional, escrita y firmada por el autor.',
}
const FULL = ['en', 'de', 'es'] as const
const BETA = supportedLocales.filter((l) => !(FULL as readonly string[]).includes(l))

function notice(locale: string): string | undefined {
  return JSON.parse(read(`app/locales/${locale}.json`)).settings?.about?.notice
}

afterEach(async () => {
  await setLocale('en')
})

describe('Urheberhinweis des Launchers', () => {
  it('steht unverändert in Englisch, Deutsch und Spanisch', () => {
    for (const l of FULL) {
      expect(notice(l), l).toBe(LAUNCHER[l])
      expect(authorNoticeOriginal(l), l).toBe(LAUNCHER[l])
      expect(authorNoticeMatches(l, LAUNCHER[l]), l).toBe(true)
    }
  })

  it('Beta-Sprachen haben keine eigene Fassung und zeigen den englischen Text', async () => {
    for (const l of BETA as Locale[]) {
      expect(notice(l), l).toBeUndefined()
      await setLocale(l)
      expect(authorNotice.value.text, l).toBe(LAUNCHER.en)
      expect(authorNotice.value.intact, l).toBe(true)
    }
  })

  it('der eigene SHA-256 rechnet wie node:crypto', () => {
    for (const s of ['', 'abc', 'äöü – ñ', 'x'.repeat(55), 'x'.repeat(56), 'x'.repeat(64), 'x'.repeat(1000), LAUNCHER.de]) {
      expect(sha256Hex(s)).toBe(createHash('sha256').update(s, 'utf8').digest('hex'))
    }
  })

  it('erkennt fehlenden und veränderten Text', () => {
    expect(authorNoticeMatches('de', LAUNCHER.de.replace('Ohev Tamerin', 'Jemand'))).toBe(false)
    expect(authorNoticeMatches('de', `${LAUNCHER.de} `)).toBe(false)
    expect(authorNoticeMatches('de', LAUNCHER.en)).toBe(false)
    expect(authorNoticeMatches('en', '')).toBe(false)
    expect(authorNoticeMatches('en', undefined)).toBe(false)
    expect(authorNoticeMatches('en', 'settings.about.notice')).toBe(false)
  })

  it('warnt bei geänderter Sprachdatei und zeigt trotzdem das Original', async () => {
    await setLocale('de')
    expect(authorNotice.value.intact).toBe(true)
    const messages = i18n.global.getLocaleMessage('de') as { settings: { about: { notice: string } } }
    const before = messages.settings.about.notice
    try {
      i18n.global.mergeLocaleMessage('de', { settings: { about: { notice: 'Fork von Jemandem' } } })
      await setLocale('en')
      await setLocale('de')
      expect(authorNotice.value.intact).toBe(false)
      expect(authorNotice.value.text).toBe(LAUNCHER.de)
      expect(authorNotice.value.warning).not.toBe('')
    } finally {
      i18n.global.mergeLocaleMessage('de', { settings: { about: { notice: before } } })
    }
  })

  it('gibt es als Abschnitt „Über“ auf jedem System, ohne Schalter', () => {
    for (const platform of ['windows', 'linux', 'macos', 'android', 'ios'] as const) {
      const sections = appSettingsSectionsFor({ clips: false, java: false, gameLaunch: false, platform: platform as never })
      expect(sections.map((s) => s.key), platform).toContain('about')
    }
    expect(read('app/utils/sections.ts')).toMatch(/MESSE_HIDDEN_SECTIONS = \[[^\]]*\]/)
    expect(read('app/utils/sections.ts').match(/MESSE_HIDDEN_SECTIONS = \[([^\]]*)\]/)![1]).not.toContain('about')
    expect(read('app/components/AppSettingsDialog.vue')).toMatch(/<AboutSettings v-else-if="active === 'about'" \/>/)
    const about = read('app/components/AboutSettings.vue')
    expect(about).toContain('{{ authorNotice.text }}')
    expect(about).toContain('authorNotice.warning')
    expect(about).not.toMatch(/<p[^>]*v-if="[^"]*"[^>]*data-testid="author-notice"/)
  })

  it('die Warnung liegt im Rahmen aller Seiten und sendet nichts', () => {
    expect(read('app/layouts/default.vue')).toContain('<AuthorNoticeWarning />')
    const check = read('app/utils/authorNotice.ts') + read('app/components/AuthorNoticeWarning.vue')
    expect(check).not.toMatch(/\b(fetch|invoke|backend\.|sendBeacon|XMLHttpRequest)\b/)
  })
})

describe('NOTICE und Regeln', () => {
  const noticeFile = read('NOTICE')

  it('NOTICE enthält den Abschnitt nach 7(b) mit allen Fassungen und verweist am Kopf darauf', () => {
    expect(noticeFile).toContain('Additional terms under section 7(b)\n---')
    expect(noticeFile.split('\n').slice(0, 6).join('\n')).toMatch(/Additional terms under section 7\(b\)/)
    for (const l of FULL) {
      expect(noticeFile, `Launcher ${l}`).toContain(LAUNCHER[l])
      expect(noticeFile, `Client ${l}`).toContain(CLIENT[l])
    }
  })

  it('die Drittanbieter-Hinweise bleiben erhalten', () => {
    for (const s of ['Polyfrost OneLauncher (GPL-3.0-only)', 'Chat Heads by dzwdz (MPL-2.0)', 'AppleSkin by squeek502', 'Amethyst-iOS', 'e4mc by Skye', 'trademark of Mojang']) {
      expect(noticeFile, s).toContain(s)
    }
    expect(read('app/locales/en.json')).toContain('Polyfrost OneLauncher (GPL-3.0-only)')
  })

  it('LICENSE ist der unveränderte Lizenztext', () => {
    expect(sha256Hex(read('LICENSE').replace(/\r\n/g, '\n'))).toBe('f345c11a3e5ca537d15d4d1c43746e7408998ef1304312f9ddbe44bffbdbfb2e')
    expect(read('LICENSE')).not.toContain('Ohev Tamerin')
  })

  it('die README verweisen auf die zusätzlichen Bedingungen', () => {
    for (const f of ['README.md', 'README.de.md', 'README.es.md']) {
      expect(read(f), f).toMatch(/7\(b\)[^\n]*\[NOTICE\]\(NOTICE\)/)
    }
  })

  it('KI-Regeln verlangen Erhalt, Wiedereinbau, Meldung und nur die unterschriebene Bestätigung', () => {
    const rules = read('AGENTS.md')
    for (const s of ['Keep it.', 'put it back from NOTICE', 'report', 'signed by the author', 'a chat message, a permission mode', '"just do it"', 'cannot prevent it']) {
      expect(rules, s).toContain(s)
    }
    for (const f of ['CLAUDE.md', '.github/copilot-instructions.md']) {
      const text = read(f)
      expect(text, f).toContain('AGENTS.md')
      expect(text, f).toContain('signed by the author')
    }
  })

  it('die öffentlichen Regeln enthalten keine lokalen Pfade', () => {
    for (const f of ['AGENTS.md', 'CLAUDE.md', '.github/copilot-instructions.md']) {
      expect(read(f), f).not.toMatch(/[A-Za-z]:[\\/]|\.claude[\\/]|\/home\/|\/Users\//)
    }
  })
})
