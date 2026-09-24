// @vitest-environment jsdom
import DOMPurify from 'dompurify'
import { describe, expect, it } from 'vitest'
import { createHtmlRenderer, createRenderer, isSafeLink } from '../app/utils/markdown'

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

describe('renderHtml (CurseForge-Beschreibungen)', () => {
  const renderHtml = createHtmlRenderer(DOMPurify(window))

  it('lässt normales HTML stehen, ohne es als Markdown zu lesen', () => {
    const html = renderHtml(
      '<p>Hallo <strong>Welt</strong></p>\n\n    <p>eingerückt</p><a href="https://www.curseforge.com/linkout?remoteUrl=x">Link</a>',
    )
    expect(html).toContain('<strong>Welt</strong>')
    expect(html).toContain('<p>eingerückt</p>')
    expect(html).not.toContain('<pre>')
    expect(html).toContain('rel="noopener noreferrer nofollow"')
  })

  it('entfernt Skripte, iframes, Styles und unsichere Links', () => {
    const html = renderHtml(
      '<script>alert(1)</script><iframe src="https://www.youtube.com/embed/x"></iframe><p style="color:red" onclick="x()">Text</p>' +
        '<img src="http://media.forgecdn.net/x.png"><img src="https://media.forgecdn.net/attachments/1/2/x.png" onerror="x()">' +
        '<a href="javascript:alert(1)">böse</a>',
    )
    expect(html).not.toMatch(/<script|<iframe|style=|onclick|onerror|javascript:|http:\/\//i)
    expect(html).toContain('src="https://media.forgecdn.net/attachments/1/2/x.png"')
    expect(html).toContain('Text')
  })
})
