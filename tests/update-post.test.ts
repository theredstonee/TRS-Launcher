import { describe, expect, it } from 'vitest'
import { parseChangelog } from '../app/utils/changelog'
import { BLOG_BASE_URL, blogPostUrl, postLang, postTitle, shotKey, stepShot } from '../app/utils/updatePost'

describe('Update-Beitrag im Launcher', () => {
  it('verlinkt den Blog-Beitrag der Website in der passenden Sprache', () => {
    expect(BLOG_BASE_URL).toBe('https://trs-launcher.theredstonee.de/blog')
    expect(blogPostUrl('0.6.4', 'de')).toBe('https://trs-launcher.theredstonee.de/blog/0.6.4?lang=de')
    expect(blogPostUrl('v0.6.4', 'en')).toBe('https://trs-launcher.theredstonee.de/blog/0.6.4?lang=en')
    expect(blogPostUrl('1.0.0-beta.1', 'es')).toBe('https://trs-launcher.theredstonee.de/blog/1.0.0-beta.1?lang=es')
    // Sprachen, die die Website nicht hat: die Website entscheidet selbst.
    expect(blogPostUrl('0.6.4', 'fr')).toBe('https://trs-launcher.theredstonee.de/blog/0.6.4')
    // Nie etwas Fremdes in die Adresse.
    expect(blogPostUrl('../admin', 'de')).toBe(BLOG_BASE_URL)
    expect(blogPostUrl('0.6.4?x=1', 'de')).toBe(BLOG_BASE_URL)
  })

  it('wählt Changelog-Sprache und Update-Namen', () => {
    expect(postLang('de')).toBe('de')
    expect(postLang('en')).toBe('en')
    expect(postLang('pt-BR')).toBe('en')
    const [named, plain] = parseChangelog(
      '## 1.0.0 – 2026-01-01 – The Big Update | Das große Update\n### English\n- a\n### Deutsch\n- b\n## 0.9.0 – 2025-12-01\n### English\n- a',
    )
    expect(postTitle(named!, 'de')).toBe('Das große Update')
    expect(postTitle(named!, 'tr')).toBe('The Big Update')
    expect(postTitle(plain!, 'de')).toBeNull()
  })

  it('blättert in der Galerie im Kreis und reagiert nur auf passende Tasten', () => {
    expect(stepShot(0, 1, 3)).toBe(1)
    expect(stepShot(2, 1, 3)).toBe(0)
    expect(stepShot(0, -1, 3)).toBe(2)
    expect(stepShot(0, -7, 3)).toBe(2)
    expect(stepShot(5, 1, 0)).toBe(0)
    expect(shotKey('ArrowRight', 0, 4)).toBe(1)
    expect(shotKey('ArrowLeft', 0, 4)).toBe(3)
    expect(shotKey('Home', 3, 4)).toBe(0)
    expect(shotKey('End', 0, 4)).toBe(3)
    expect(shotKey('Enter', 0, 4)).toBeNull()
    // Ein einzelnes Bild: Pfeiltasten bleiben frei.
    expect(shotKey('ArrowRight', 0, 1)).toBeNull()
  })
})
