import { createApp, toWebHandler } from 'h3'
import { describe, expect, it } from 'vitest'
import { WEBSITE_PRIVACY } from '../app/content/website-privacy'
import { messages } from '../app/utils/messages'
import { buildLlmsFullTxt, buildLlmsTxt, type LlmsSource } from '../server/lib/llms'
import { llmsHandler } from '../server/lib/llms-http'
import { LANDING_IDS, landingPath, landingTexts } from '../shared/landing'
import { buildRobots, COMPETITOR_PATTERN as COMPETITORS } from '../shared/seo'

const SITE = 'https://trs-launcher.theredstonee.de'

const source: LlmsSource = {
  siteUrl: `${SITE}/`,
  m: messages.en,
  landing: landingTexts.en,
  pages: { issues: 'Report bugs.', roadmap: 'What is planned.', circuits: 'Free redstone circuits.', team: 'Meet the team.' },
  privacy: WEBSITE_PRIVACY.en.intro,
  releases: [
    // Schlagzeilen kommen aus dem Changelog – eine mit fremdem Client-Namen muss herausfallen.
    { version: '0.14.0', name: 'Achievement Unlocked', date: '2026-09-28', headlines: ['Screenshot preview (TRS Client)', 'Works with Essential', 'Achievements'] },
    { version: '0.13.0', name: 'Your Say', date: '2026-09-28', headlines: [] },
  ],
}

describe('llms.txt', () => {
  const txt = buildLlmsTxt(source)

  it('folgt der Konvention: H1, Zitat-Zusammenfassung, Abschnitte mit Link-Listen', () => {
    const lines = txt.split('\n')
    expect(lines[0]).toBe('# TRS Launcher')
    expect(lines[2]!.startsWith('> TRS Launcher (the Redstone Launcher by TheRedstonee) is a free, open-source')).toBe(true)
    for (const h of ['## Main pages', '## Topics', '## Documentation', '## Community', '## Source and contact', '## Optional']) expect(lines).toContain(h)
    expect(txt).toMatch(/^- \[[^\]]+\]\(https:\/\/[^)]+\): .+$/m)
  })

  it('verlinkt Start, Funktionen, Download, FAQ, Themen-Seiten, Blog, Issues, Roadmap, GitHub, Datenschutz und den Volltext', () => {
    for (const p of ['/', '/features', '/download', '/faq', '/blog', '/issues', '/roadmap', '/circuits', '/privacy', '/llms-full.txt']) {
      expect(txt, p).toContain(`](${SITE}${p})`)
    }
    for (const id of LANDING_IDS) expect(txt).toContain(`](${SITE}${landingPath(id)})`)
    expect(txt).toContain('](https://github.com/theredstonee/TRS-Launcher)')
    expect(txt).toContain('Latest version: 0.14.0 – “Achievement Unlocked” (released 2026-09-28).')
    expect(txt).not.toContain(`${SITE}//`)
  })

  it('Abschnitt Documentation verlinkt die Doku und deren llms.txt/llms-full.txt', () => {
    expect(txt).toContain(`](${SITE}/docs/en)`)
    expect(txt).toContain(`](${SITE}/docs/llms.txt)`)
    expect(txt).toContain(`](${SITE}/docs/llms-full.txt)`)
    expect(txt).toContain(`](${SITE}/docs/de)`)
    expect(txt).toContain(`](${SITE}/docs/es)`)
  })

  it('Namensvarianten und keine anderen Launcher/Clients', () => {
    expect(txt).toContain('Redstone Launcher')
    expect(txt).toContain('TheRedstonee Launcher')
    expect(txt).not.toMatch(COMPETITORS)
  })
})

describe('llms-full.txt', () => {
  const full = buildLlmsFullTxt(source)

  it('enthält alle Hauptabschnitte', () => {
    const lines = full.split('\n')
    expect(lines[0]).toBe('# TRS Launcher')
    for (const h of ['## Key facts', '## Latest version', '## What it is', '## Features', '## Download and installation', '## Frequently asked questions', '## Privacy', '## Links']) {
      expect(lines, h).toContain(h)
    }
  })

  it('kommt aus denselben Texten wie die Seiten (Funktionen, Themen-Seiten, FAQ, Datenschutz)', () => {
    for (const s of messages.en.features.sections) expect(full).toContain(`### ${s.title}`)
    for (const id of LANDING_IDS) {
      const p = landingTexts.en.pages[id]
      expect(full).toContain(`## ${p.title}`)
      for (const s of p.sections) expect(full).toContain(`### ${s.title}`)
    }
    for (const f of messages.en.faq.items) expect(full).toContain(`### ${f.q}`)
    expect(full).toContain('no tracking, no analytics, no advertising')
    expect(full).toContain('yay -S trs-launcher-bin')
    expect(full).toContain('- Screenshot preview (TRS Client)')
    expect(full).toContain(`[0.13.0 – Your Say](${SITE}/blog/0.13.0)`)
  })

  it('ohne Changelog (GitHub nicht erreichbar) fehlt nur der Versions-Abschnitt', () => {
    const noRel = buildLlmsFullTxt({ ...source, releases: [] })
    expect(noRel).not.toContain('## Latest version')
    expect(noRel).toContain('## Features')
    expect(buildLlmsTxt({ ...source, releases: [] })).not.toContain('Latest version')
  })

  it('verweist in den Links auf die Doku und deren Volltext', () => {
    expect(full).toContain(`- Documentation: ${SITE}/docs/en (LLM index: ${SITE}/docs/llms.txt, full text: ${SITE}/docs/llms-full.txt)`)
  })

  it('keine anderen Launcher oder Clients mit Namen', () => {
    expect(full).not.toMatch(COMPETITORS)
  })
})

describe('Auslieferung', () => {
  const app = createApp()
  app.use('/llms.txt', llmsHandler('short', async () => source))
  app.use('/llms-full.txt', llmsHandler('full', async () => source))
  const handle = toWebHandler(app)

  it.each([
    ['/llms.txt', '## Main pages'],
    ['/llms-full.txt', '## Key facts'],
  ])('%s als text/plain (UTF-8) mit Cache-Header', async (path, marker) => {
    const res = await handle(new Request(`http://localhost${path}`))
    expect(res.status).toBe(200)
    expect(res.headers.get('content-type')).toBe('text/plain; charset=utf-8')
    expect(res.headers.get('cache-control')).toMatch(/^public, max-age=\d+/)
    expect(res.headers.get('x-content-type-options')).toBe('nosniff')
    const body = await res.text()
    expect(body.startsWith('# TRS Launcher\n')).toBe(true)
    expect(body).toContain(marker)
    expect(body).toContain('–') // UTF-8 bleibt erhalten
  })

  it('robots.txt erlaubt beide Dateien', () => {
    const lines = buildRobots(SITE).split('\n')
    expect(lines).toContain('Allow: /llms.txt')
    expect(lines).toContain('Allow: /llms-full.txt')
  })
})
