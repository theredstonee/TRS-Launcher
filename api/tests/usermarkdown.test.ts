import { describe, expect, it } from 'vitest'
import { renderUserMarkdown } from '../app/utils/markdown'

describe('user markdown (issues)', () => {
  it('never lets HTML, scripts or dangerous links through', () => {
    const out = renderUserMarkdown([
      '<script>alert(1)</script>',
      '<img src=x onerror=alert(1)>',
      '[click](javascript:alert(1)) [data](data:text/html;base64,PHNjcmlwdD4=) [vb](  JaVaScRiPt:alert(1))',
      '![remote](https://evil.example/x.png)',
      '"><svg onload=alert(1)>',
      '`<b>code</b>`',
      '<a href="https://x.test" onclick="alert(1)">x</a>',
      '[x](https://ok.test" onmouseover="alert(1))',
    ].join('\n\n'))
    expect(out).not.toMatch(/<script|<img|<svg|<a href="https:\/\/x\.test"|javascript:|data:text/i)
    expect(out).not.toMatch(/<[^>]*\son\w+=/i)
    expect(out).toContain('&lt;script&gt;')
    expect(out).toContain('<code>&lt;b&gt;code&lt;/b&gt;</code>')
    expect(out).toContain('remote')
  })

  it('renders safe markdown with ugc links and lowered headings', () => {
    const out = renderUserMarkdown('# Title\n\n**bold** [site](https://example.com) [local](/issues/2)\nnext line')
    expect(out).toContain('<h3>Title</h3>')
    expect(out).toContain('<strong>bold</strong>')
    expect(out).toContain('<a href="https://example.com" rel="nofollow ugc noopener noreferrer" target="_blank">site</a>')
    expect(out).toContain('<a href="/issues/2" rel="nofollow ugc noopener noreferrer">local</a>')
    expect(out).toContain('<br>')
  })
})
