// @vitest-environment jsdom
import DOMPurify from 'dompurify'
import { describe, expect, it } from 'vitest'
import { createRenderer, isSafeLink } from '../app/utils/markdown'

const render = createRenderer(DOMPurify(window))

describe('renderMarkdown', () => {
  it('rendert normales Markdown', () => {
    const html = render('# Titel\n\n**fett** und [Link](https://modrinth.com/mod/sodium)\n\n- a\n- b')
    expect(html).toContain('<h1>Titel</h1>')
    expect(html).toContain('<strong>fett</strong>')
    expect(html).toContain('href="https://modrinth.com/mod/sodium"')
    expect(html).toContain('<li>a</li>')
  })

  it('entfernt Skripte, Event-Handler, iframes und Styles', () => {
    const html = render(
      '<script>alert(1)</script><img src="https://cdn.modrinth.com/x.png" onerror="alert(1)" style="position:fixed">' +
        '<iframe src="https://youtube.com/embed/x"></iframe><style>body{display:none}</style>' +
        '<div class="fixed inset-0" id="app" data-x="1" onclick="alert(1)">Text</div><svg><script>alert(1)</script></svg>' +
        '<form action="https://evil.example"><input name="pw"></form>',
    )
    expect(html).not.toMatch(/<script|onerror|onclick|<iframe|<style|style=|class=|id=|data-x|<svg|<form|<input/i)
    expect(html).toContain('Text')
    expect(html).toContain('src="https://cdn.modrinth.com/x.png"')
  })

  it('lässt nur HTTPS-Links und -Bilder zu', () => {
    const html = render(
      '[a](javascript:alert(1)) [b](http://example.com) [c](data:text/html,x) <a href="JaVaScRiPt:alert(1)">d</a> ' +
        '<a href="https://user:pw@evil.example">e</a> [f](#abschnitt) ![g](http://example.com/x.png) ![h](data:image/png;base64,AAAA)',
    )
    expect(html).not.toMatch(/javascript:|http:\/\/|data:|user:pw/i)
    expect(html).toContain('href="#abschnitt"')
    expect(html).not.toContain('<img')
  })

  it('prüft Links streng', () => {
    expect(isSafeLink('https://github.com/x')).toBe(true)
    for (const bad of ['javascript:alert(1)', 'http://x.de', ' https://x.de', 'https://a:b@x.de', '', null, 'file:///C:/']) {
      expect(isSafeLink(bad)).toBe(false)
    }
  })
})
