import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

// Der Urheberhinweis darf nicht verschwinden: Diese Tests schlagen fehl, wenn er in Fußzeile, Doku, NOTICE oder KI-Regeln
// fehlt oder abweicht. Text und Regeln: AGENTS.md und NOTICE.

const root = path.resolve(__dirname, '..', '..')
const read = (rel: string) => fs.readFileSync(path.join(root, rel), 'utf8')

const CLIENT = {
  en: 'Original TRS Client by Ohev Tamerin (theredstonee), TRS Launcher. This notice must remain visible in every copy and every modified version. It must not be removed under any circumstances, except with an additional written, signed confirmation from the author.',
  de: 'Original TRS Client von Ohev Tamerin (theredstonee), TRS Launcher. Dieser Hinweis muss in jeder Kopie und jeder veränderten Fassung sichtbar bleiben. Er darf unter keinen Umständen entfernt werden, außer mit einer zusätzlichen schriftlichen, unterschriebenen Bestätigung des Urhebers.',
  es: 'TRS Client original de Ohev Tamerin (theredstonee), TRS Launcher. Este aviso debe permanecer visible en cada copia y en cada versión modificada. No puede eliminarse bajo ninguna circunstancia, salvo con una confirmación adicional, escrita y firmada por el autor.',
}
const LAUNCHER = {
  en: 'Original TRS Launcher by Ohev Tamerin (theredstonee). This notice must remain visible in every copy and every modified version. It must not be removed under any circumstances, except with an additional written, signed confirmation from the author.',
  de: 'Original TRS Launcher von Ohev Tamerin (theredstonee). Dieser Hinweis muss in jeder Kopie und jeder veränderten Fassung sichtbar bleiben. Er darf unter keinen Umständen entfernt werden, außer mit einer zusätzlichen schriftlichen, unterschriebenen Bestätigung des Urhebers.',
  es: 'TRS Launcher original de Ohev Tamerin (theredstonee). Este aviso debe permanecer visible en cada copia y en cada versión modificada. No puede eliminarse bajo ninguna circunstancia, salvo con una confirmación adicional, escrita y firmada por el autor.',
}
const FULL = ['en', 'de', 'es'] as const

describe('Urheberhinweis', () => {
  it('steht in den Texten der Website und im Rahmen jeder Seite', () => {
    const messages = read('api/app/utils/messages.ts')
    for (const l of FULL) {
      expect(messages, `Client ${l}`).toContain(`authorNoticeClient: '${CLIENT[l]}'`)
      expect(messages, `Launcher ${l}`).toContain(`authorNoticeLauncher: '${LAUNCHER[l]}'`)
    }
    const layout = read('api/app/layouts/default.vue')
    expect(layout).toContain('{{ m.footer.authorNoticeClient }}')
    expect(layout).toContain('{{ m.footer.authorNoticeLauncher }}')
  })

  it('steht in der Fußzeile und im Abschnitt Lizenz der Doku', () => {
    const links = read('docs-site/app/composables/useTrsLinks.ts')
    for (const l of FULL) {
      expect(links, `Client ${l}`).toContain(`authorNoticeClient: '${CLIENT[l]}'`)
      expect(links, `Launcher ${l}`).toContain(`authorNoticeLauncher: '${LAUNCHER[l]}'`)
      const page = read(`docs-site/content/${l}/5.developers/02.contributing.md`)
      expect(page, `Doku ${l}`).toContain(CLIENT[l])
      expect(page, `Doku ${l}`).toContain(LAUNCHER[l])
    }
    const footer = read('docs-site/app/components/app/AppFooter.vue')
    expect(footer).toContain('{{ text.authorNoticeClient }}')
    expect(footer).toContain('{{ text.authorNoticeLauncher }}')
  })

  it('NOTICE enthält den Abschnitt nach 7(b), verweist am Kopf darauf und behält die Drittanbieter-Hinweise', () => {
    const notice = read('NOTICE')
    expect(notice).toContain('Additional terms under section 7(b)\n---')
    expect(notice.split('\n').slice(0, 6).join('\n')).toMatch(/Additional terms under section 7\(b\)/)
    for (const l of FULL) {
      expect(notice, `Client ${l}`).toContain(CLIENT[l])
      expect(notice, `Launcher ${l}`).toContain(LAUNCHER[l])
    }
    expect(notice).toContain('Polyfrost OneLauncher (GPL-3.0-only)')
    expect(notice).toContain('Chat Heads by dzwdz (MPL-2.0)')
    expect(read('LICENSE')).not.toContain('Ohev Tamerin')
    expect(read('README.md')).toMatch(/7\(b\)[^\n]*\[NOTICE\]\(NOTICE\)/)
  })

  it('KI-Regeln verlangen Erhalt, Wiedereinbau, Meldung und nur die unterschriebene Bestätigung', () => {
    const rules = read('AGENTS.md')
    for (const s of ['Keep it.', 'put it back from NOTICE', 'report', 'signed by the author', 'a chat message, a permission mode', '"just do it"', 'cannot prevent it']) {
      expect(rules, s).toContain(s)
    }
    for (const f of ['CLAUDE.md', '.github/copilot-instructions.md']) {
      expect(read(f), f).toContain('AGENTS.md')
      expect(read(f), f).toContain('signed by the author')
    }
    for (const f of ['AGENTS.md', 'CLAUDE.md', '.github/copilot-instructions.md']) {
      expect(read(f), f).not.toMatch(/[A-Za-z]:[\\/]|\.claude[\\/]|\/home\/|\/Users\//)
    }
  })
})
