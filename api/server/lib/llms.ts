// /llms.txt und /llms-full.txt (Konvention llmstxt.org): kurze Übersicht bzw. der ganze englische Inhalt der
// Website als Markdown-Text für Sprachmodelle. Gebaut aus denselben Texten wie die Seiten (messages.en,
// shared/landing-en.ts, FAQ, Datenschutz-Kurzfassung) – so bleibt alles gleich, wenn sich die Seiten ändern.
// Reine Funktionen, getestet in tests/llms.test.ts; die Routen liefern nur aus.

import type { Messages } from '../../app/utils/messages'
import { LANDING_IDS, landingPath, type LandingTexts } from '../../shared/landing'
import { AUTHOR_NAME, COMPETITOR_PATTERN, DISCORD_URL, LAUNCHER_ALT_NAMES, REPO_URL, SITE_NAME } from '../../shared/seo'

export interface LlmsLatest {
  version: string
  /** Name des Updates, z. B. „Achievement Unlocked“. */
  name: string | null
  date: string | null
  headlines: string[]
}

export interface LlmsSource {
  siteUrl: string
  /** Englische Texte der Website. */
  m: Messages
  landing: LandingTexts
  /** Beschreibungen der Seiten aus den anderen Textdateien (Issues, Roadmap, Schaltungen, Team). */
  pages: { issues: string, roadmap: string, circuits: string, team: string }
  /** Kurzfassung der Datenschutzerklärung. */
  privacy: string
  /** Neueste Versionen aus dem Changelog, neueste zuerst (leer, wenn GitHub nicht erreichbar war). */
  releases: LlmsLatest[]
}

function base(siteUrl: string): string {
  return siteUrl.replace(/\/+$/, '')
}

/** Eine Zeile ohne Zeilenumbrüche (Listen in Markdown). */
function line(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

function summary(): string {
  return `${SITE_NAME} (the Redstone Launcher by ${AUTHOR_NAME}) is a free, open-source (GPL-3.0) launcher for Minecraft: Java Edition on Windows and Linux. It installs every Minecraft version with Vanilla, Fabric, Quilt, Forge or NeoForge, installs mods and modpacks from Modrinth and CurseForge, and ships the TRS Client – a client mod with FPS boost, PvP HUD, redstone tools, minimap, emotes and capes.`
}

function latestLine(r: LlmsLatest | undefined): string | null {
  if (!r) return null
  return `Latest version: ${r.version}${r.name ? ` – “${r.name}”` : ''}${r.date ? ` (released ${r.date})` : ''}.`
}

/** Kurze Übersicht mit Links zu den wichtigen Seiten. */
export function buildLlmsTxt(s: LlmsSource): string {
  const u = base(s.siteUrl)
  const { m, landing } = s
  const out: string[] = [
    `# ${SITE_NAME}`,
    '',
    `> ${summary()}`,
    '',
    `Also known as: ${LAUNCHER_ALT_NAMES.join(', ')}. The client mod inside is called TRS Client. Developed by ${AUTHOR_NAME}. ${line(m.footer.notAffiliated)}`,
  ]
  const latest = latestLine(s.releases[0])
  if (latest) out.push('', latest)
  out.push(
    '',
    '## Main pages',
    '',
    `- [Home](${u}/): ${line(m.seo.home.description)}`,
    `- [Features](${u}/features): ${line(m.seo.features.description)}`,
    `- [Download](${u}/download): ${line(m.seo.download.description)}`,
    `- [FAQ](${u}/faq): ${line(m.seo.faq.description)}`,
    '',
    '## Topics',
    '',
    ...LANDING_IDS.map((id) => `- [${landing.pages[id].name}](${u}${landingPath(id)}): ${line(landing.pages[id].seo.description)}`),
    '',
    '## Community',
    '',
    `- [Blog and patch notes](${u}/blog): ${line(m.seo.blog.description)} RSS: ${u}/feed.xml`,
    `- [Issue tracker](${u}/issues): ${line(s.pages.issues)}`,
    `- [Roadmap](${u}/roadmap): ${line(s.pages.roadmap)}`,
    `- [Redstone circuit library](${u}/circuits): ${line(s.pages.circuits)}`,
    `- [TRS capes](${u}/capes): ${line(m.seo.capes.description)}`,
    `- [Team](${u}/team): ${line(s.pages.team)}`,
    '',
    '## Source and contact',
    '',
    `- [GitHub repository](${REPO_URL}): source code (GPL-3.0), releases with checksums, bug reports`,
    `- [Discord](${DISCORD_URL}): community and support`,
    `- [Privacy policy](${u}/privacy): ${line(m.seo.privacy.description)}`,
    '',
    '## Optional',
    '',
    `- [Full text](${u}/llms-full.txt): all features, supported versions, download, FAQ and privacy summary in one English text`,
    `- [German website](${u}/?lang=de) and [Spanish website](${u}/?lang=es): every page is also available with ?lang=de or ?lang=es`,
    `- [Sitemap](${u}/sitemap.xml)`,
    '',
  )
  return out.join('\n')
}

/** Der ganze englische Inhalt als ein Markdown-Text. */
export function buildLlmsFullTxt(s: LlmsSource): string {
  const u = base(s.siteUrl)
  const { m, landing } = s
  const out: string[] = [
    `# ${SITE_NAME}`,
    '',
    `> ${summary()}`,
    '',
    '## Key facts',
    '',
    `- Name: ${SITE_NAME} – also known as ${LAUNCHER_ALT_NAMES.join(', ')}`,
    `- Developer: ${AUTHOR_NAME}`,
    '- Price: free',
    '- License: GPL-3.0 (open source)',
    '- Platforms: Windows 10 and 11 (64-bit), Linux (AppImage, .deb, .rpm, AUR package trs-launcher-bin)',
    '- Game: Minecraft: Java Edition, every release from 1.7.10 to the newest version, snapshots included',
    '- Mod loaders: Vanilla, Fabric, Quilt, Forge, NeoForge',
    '- Built-in client mod: TRS Client (Forge from 1.7.10, Fabric and Quilt from 1.14.4, NeoForge – up to the newest version)',
    `- Website: ${u}/`,
    `- Source code: ${REPO_URL}`,
    `- ${line(m.footer.notAffiliated)}`,
  ]

  if (s.releases.length) {
    const [latest, ...older] = s.releases
    out.push('', '## Latest version', '', latestLine(latest)!)
    // Schlagzeilen aus dem Changelog; solche mit Namen anderer Launcher/Clients bleiben weg.
    const headlines = latest!.headlines.filter((h) => !COMPETITOR_PATTERN.test(h))
    if (headlines.length) out.push('', ...headlines.map((h) => `- ${line(h)}`))
    out.push('', `Patch notes: ${u}/blog/${latest!.version}`)
    if (older.length) {
      out.push('', 'Earlier versions:', '', ...older.map((r) => `- [${r.version}${r.name ? ` – ${r.name}` : ''}](${u}/blog/${r.version})${r.date ? ` (${r.date})` : ''}`))
    }
  }

  out.push('', '## What it is', '', line(landing.pages['minecraft-launcher'].lead), '', line(m.features.lead))

  out.push('', '## Features', '')
  for (const sec of m.features.sections) {
    out.push(`### ${sec.title}`, '', line(sec.text))
    if (sec.points.length) out.push('', ...sec.points.map((p) => `- ${line(p)}`))
    out.push('')
  }

  for (const id of LANDING_IDS) {
    const p = landing.pages[id]
    out.push(`## ${p.title}`, '', `Page: ${u}${landingPath(id)}`, '', line(p.lead), '')
    for (const sec of p.sections) {
      out.push(`### ${sec.title}`, '', ...sec.text.flatMap((t) => [line(t), '']))
      if (sec.points?.length) out.push(...sec.points.map((pt) => `- ${line(pt)}`), '')
    }
    out.push(`### ${landing.common.faqTitle}`, '')
    for (const f of p.faq) out.push(`**${line(f.q)}** ${line(f.a)}`, '')
  }

  out.push('## Download and installation', '', `Download page: ${u}/download`, '', line(m.download.lead), '')
  out.push(`- Windows: ${line(m.download.windowsText)} ${line(m.download.smartText)}`)
  out.push(`- Linux: ${line(m.download.linuxText)} AppImage (${line(m.download.appimageText)}), ${m.download.deb}, ${m.download.rpm}, ${m.download.aur}: yay -S trs-launcher-bin. Flatpak: ${line(m.download.flatpakSoon)}`)
  out.push(`- ${m.download.requirementsTitle}: ${m.download.requirements.map(line).join('; ')}`)
  out.push(`- All files and checksums: ${REPO_URL}/releases`, '')

  out.push('## Frequently asked questions', '', `Page: ${u}/faq`, '')
  for (const f of m.faq.items) out.push(`### ${line(f.q)}`, '', line(f.a), '')

  out.push('## Privacy', '', line(s.privacy), '', `Full privacy policy: ${u}/privacy`, '')

  out.push(
    '## Links',
    '',
    `- Home: ${u}/`,
    `- Features: ${u}/features`,
    ...LANDING_IDS.map((id) => `- ${landing.pages[id].name}: ${u}${landingPath(id)}`),
    `- Download: ${u}/download`,
    `- FAQ: ${u}/faq`,
    `- Blog: ${u}/blog`,
    `- Issue tracker: ${u}/issues`,
    `- Roadmap: ${u}/roadmap`,
    `- Redstone circuits: ${u}/circuits`,
    `- Capes: ${u}/capes`,
    `- Team: ${u}/team`,
    `- Privacy: ${u}/privacy`,
    `- GitHub: ${REPO_URL}`,
    `- Discord: ${DISCORD_URL}`,
    '',
  )
  return out.join('\n')
}
