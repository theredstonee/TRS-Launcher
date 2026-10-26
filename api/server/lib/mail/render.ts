import { DISCORD_URL } from '../../../shared/seo'
import { pickLang, type Block, type CampaignContent, type NewsletterLang } from '../../../shared/newsletter'
import type { AppContext } from '../context'
import { one } from '../db'
import { getCape } from '../capes'
import { getCosmetic, v2Assets, v2Fields } from '../cosmetics'
import { headUrl, titleImage, type TitleKind } from './images'
import { esc, mdToHtml, mdToText, safeUrl, type BlockStyle } from './markdown'
import { LOOK_NAMES, THEMES, type Look, type Theme } from './theme'
import { MAIL_TEXTS, fill, type MailLang } from './texts'

/**
 * Mail-Markup nach dem freigegebenen Entwurf (`mail-design/gen_mail.py`): Tabellen-Layout, Inline-Stile, 600 px,
 * Media Query fürs Handy, `color-scheme: dark`. Überschriften sind Pixel-Bilder vom Server (Alt-Text = Klartext).
 * Keine externen Schriften (kein Google Fonts in Mails), alle Bilder von der eigenen Website.
 */

const FONT = "'Segoe UI', 'Helvetica Neue', Arial, sans-serif"
const PIXEL = "'Silkscreen', 'Pixelify Sans', 'Courier New', monospace"
const MONO = "'Cascadia Mono', Consolas, 'Courier New', monospace"
const IMPRINT_URL = 'https://theredstonee.de/imprint/'
/** Platzhalter für die Abmelde-Adresse; je Empfänger ersetzt (Kampagnen werden je Sprache nur einmal gerendert). */
export const UNSUB_PLACEHOLDER = '%%TRS_UNSUBSCRIBE_URL%%'

export interface RenderedMail {
  subject: string
  html: string
  text: string
  preheader: string
}

interface Out {
  html: string[]
  text: string[]
}

const plain = (s: string) => s.replace(/\*/g, '').trim()

function pixelTitle(ctx: AppContext, look: Look, text: string, kind: TitleKind, opts: { w?: number, align?: 'center' } = {}): string {
  const img = titleImage(ctx, { text, kind, look, ...(opts.w ? { w: opts.w } : {}) })
  const T = THEMES[look]
  const size = kind === 'h1' ? 22 : kind === 'h2' ? 16 : 18
  const center = opts.align === 'center' ? 'margin:0 auto;' : ''
  return `<img src="${esc(img.url)}" width="${img.width}" height="${img.height}" alt="${esc(plain(text))}" style="display:block;${center}width:${img.width}px;max-width:100%;height:auto;border:0;outline:none;text-decoration:none;image-rendering:pixelated;-ms-interpolation-mode:nearest-neighbor;font-family:${PIXEL};font-size:${size}px;line-height:1.3;color:${T.text};">`
}

export function button(T: Theme, label: string, href: string): string {
  return `<table role="presentation" cellpadding="0" cellspacing="0" style="margin:6px 0;"><tr>
<td style="background:${T.accent};border-radius:10px;box-shadow:0 0 0 1px ${T.accent2} inset,0 0 18px ${T.accent}55;">
<a href="${esc(href)}" style="display:inline-block;padding:13px 24px;font-family:${FONT};font-size:15px;font-weight:700;color:${T.btnText};text-decoration:none;">${esc(label)} &rarr;</a></td></tr></table>`
}

function sectionTitle(ctx: AppContext, look: Look, kicker: string, title: string, w?: number): string {
  const T = THEMES[look]
  const k = kicker ? `<div style="font-family:${FONT};font-size:11px;letter-spacing:2px;text-transform:uppercase;color:${T.accent2};font-weight:700;">${esc(kicker)}</div>` : ''
  const t = title ? `<div style="margin:6px 0 12px 0;">${pixelTitle(ctx, look, title, 'h2', { w })}</div>` : ''
  return k + t
}

function mdStyle(T: Theme, size = 15, color = T.text): BlockStyle {
  return {
    p: `font-family:${FONT};font-size:${size}px;line-height:${Math.round(size * 1.5)}px;color:${color};margin:0 0 12px 0;`,
    li: `font-family:${FONT};font-size:${size}px;line-height:${Math.round(size * 1.47)}px;color:${color};`,
    bullet: `width:8px;height:8px;background:${T.accent};margin-top:7px;box-shadow:0 0 8px ${T.accent2};`,
    link: `color:${T.accent2};font-weight:600;`,
    strong: `color:${T.text};`,
  }
}

/** Bildadresse für Mails: absolut (relative Pfade an die Website gehängt), nur http(s). */
function imageUrl(ctx: AppContext, src: string | null | undefined): string | null {
  if (!src) return null
  const u = safeUrl(src, ctx.config.siteUrl)
  return u && /^https?:/.test(u) ? u : null
}

// ---------------------------------------------------------------- Hülle

interface ShellOpts {
  look: Look
  lang: MailLang
  preheader: string
  reason: string
  /** Abmelde-Link (Newsletter) – sonst keiner. */
  unsubscribeUrl?: string
  /** Link „Benachrichtigungen einstellen“ (Team-Mails). */
  settingsUrl?: string
  test?: boolean
}

export function shell(ctx: AppContext, inner: string, o: ShellOpts): string {
  const T = THEMES[o.look]
  const tx = MAIL_TEXTS[o.lang]
  const site = ctx.config.siteUrl
  const links = [
    o.unsubscribeUrl ? `<a href="${esc(o.unsubscribeUrl)}" style="color:${T.accent2};">${esc(tx.unsubscribe)}</a>` : null,
    o.settingsUrl ? `<a href="${esc(o.settingsUrl)}" style="color:${T.accent2};">${esc(tx.teamSettings)}</a>` : null,
    `<a href="${esc(`${site}/privacy?lang=${o.lang}`)}" style="color:${T.muted};">${esc(tx.privacy)}</a>`,
    `<a href="${IMPRINT_URL}" style="color:${T.muted};">${esc(tx.imprint)}</a>`,
  ].filter(Boolean).join(' &nbsp;·&nbsp; ')
  const badge = o.test ? `${tx.test} · ${LOOK_NAMES[o.look][o.lang]}` : LOOK_NAMES[o.look][o.lang]
  return `<!doctype html>
<html lang="${o.lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark"><meta name="supported-color-schemes" content="dark">
<title>TRS Launcher</title>
<style>
  :root{color-scheme:dark;supported-color-schemes:dark;}
  body{margin:0;padding:0;background:${T.bg};}
  a{color:${T.accent2};}
  @media (max-width:620px){
    .wrap{width:100%!important} .px{padding-left:18px!important;padding-right:18px!important}
    .col{display:block!important;width:100%!important;padding:0 0 14px 0!important}
    .h1 img{max-width:100%!important;height:auto!important}
    .hide-m{display:none!important}
  }
</style></head>
<body style="margin:0;padding:0;background:${T.bg};">
<div style="display:none;max-height:0;overflow:hidden;opacity:0;color:${T.bg};">${esc(o.preheader)}${'&#847;&zwnj;&nbsp;'.repeat(30)}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${T.bg};">
<tr><td align="center" style="padding:24px 10px;">
<table role="presentation" class="wrap" width="600" cellpadding="0" cellspacing="0" style="width:600px;max-width:600px;background:${T.card};border:1px solid ${T.line};border-radius:14px;overflow:hidden;">
  <tr><td style="padding:0;line-height:0;"><img src="${site}/mail/head-${o.look}.png" width="600" alt="" style="display:block;width:100%;height:auto;border:0;image-rendering:pixelated;"></td></tr>
  <tr><td class="px" style="padding:18px 32px 6px 32px;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
      <td style="vertical-align:middle;"><table role="presentation" cellpadding="0" cellspacing="0"><tr>
        <td style="vertical-align:middle;"><img src="${site}/mail/logo.png" width="36" height="36" alt="TRS" style="display:block;border-radius:8px;image-rendering:pixelated;"></td>
        <td style="vertical-align:middle;padding-left:10px;">${pixelTitle(ctx, o.look, 'TRS Launcher', 'logo')}</td>
      </tr></table></td>
      <td align="right" class="hide-m" style="font-family:${FONT};font-size:12px;color:${T.muted};vertical-align:middle;">
        <span style="background:${T.badge};color:${T.badgeText};padding:4px 10px;border-radius:99px;font-weight:600;">${esc(badge)}</span></td>
    </tr></table>
  </td></tr>
  ${inner}
  <tr><td style="padding:0 32px;"><img src="${site}/mail/div-${o.look}.png" width="536" alt="" style="display:block;width:100%;height:auto;"></td></tr>
  <tr><td class="px" style="padding:22px 32px 28px 32px;font-family:${FONT};font-size:12px;line-height:19px;color:${T.muted};text-align:center;">
    <a href="${esc(`${site}/?lang=${o.lang}`)}" style="color:${T.text};text-decoration:none;font-weight:600;">${esc(tx.website)}</a> &nbsp;·&nbsp;
    <a href="${esc(ctx.config.wikiUrl)}" style="color:${T.text};text-decoration:none;font-weight:600;">${esc(tx.wiki)}</a> &nbsp;·&nbsp;
    <a href="${DISCORD_URL}" style="color:${T.text};text-decoration:none;font-weight:600;">${esc(tx.discord)}</a><br><br>
    ${esc(o.reason)}<br>
    ${links}<br>
    <span style="color:${T.line};">${esc(tx.brandLine)}</span>
  </td></tr>
</table>
</td></tr></table></body></html>`
}

function footerText(ctx: AppContext, o: ShellOpts): string {
  const tx = MAIL_TEXTS[o.lang]
  return [
    '—',
    o.reason,
    o.unsubscribeUrl ? `${tx.unsubscribe}: ${o.unsubscribeUrl}` : null,
    o.settingsUrl ? `${tx.teamSettings}: ${o.settingsUrl}` : null,
    `${tx.privacy}: ${ctx.config.siteUrl}/privacy?lang=${o.lang}`,
    `${tx.imprint}: ${IMPRINT_URL}`,
    tx.brandLine,
  ].filter(Boolean).join('\n')
}

// ---------------------------------------------------------------- Bausteine

function row(inner: string, pad = '16px 32px'): string {
  return `<tr><td class="px" style="padding:${pad};">${inner}</td></tr>`
}

function renderBlock(ctx: AppContext, b: Block, look: Look, lang: NewsletterLang, out: Out): void {
  const T = THEMES[look]
  const tx = MAIL_TEXTS[lang]
  const L = (l: Parameters<typeof pickLang>[0]) => pickLang(l, lang)
  const site = ctx.config.siteUrl
  switch (b.type) {
    case 'title': {
      const text = L(b.text)
      const kicker = L(b.kicker)
      if (!text && !kicker) return
      if (b.size === 'h1') {
        const k = kicker ? `<div style="font-family:${FONT};font-size:14px;color:${T.muted};">${esc(kicker)}</div>` : ''
        out.html.push(row(`${k}<div class="h1" style="margin:10px 0 8px 0;">${text ? pixelTitle(ctx, look, text, 'h1') : ''}</div>`, '14px 32px 4px 32px'))
      } else {
        out.html.push(row(sectionTitle(ctx, look, kicker, text), '10px 32px 0 32px'))
      }
      out.text.push([kicker, plain(text).toUpperCase()].filter(Boolean).join('\n'))
      return
    }
    case 'text': {
      const md = L(b.text)
      if (!md) return
      const st = mdStyle(T, 15, b.muted ? T.muted : T.text)
      out.html.push(row(mdToHtml(md, site, st), '6px 32px 4px 32px'))
      out.text.push(mdToText(md, site))
      return
    }
    case 'button': {
      const label = L(b.label)
      const url = safeUrl(b.url, site)
      if (!label || !url) return
      out.html.push(row(button(T, label, url), '8px 32px 12px 32px'))
      out.text.push(`${label}: ${url}`)
      return
    }
    case 'divider': {
      out.html.push(`<tr><td style="padding:10px 32px;"><img src="${site}/mail/div-${look}.png" width="536" alt="" style="display:block;width:100%;height:auto;"></td></tr>`)
      out.text.push('— — —')
      return
    }
    case 'update': {
      const items = b.items[lang]?.length ? b.items[lang]! : (b.items.en?.length ? b.items.en : b.items.de ?? [])
      const motif = imageUrl(ctx, b.motif)
      const url = safeUrl(b.url || `${site}/download?lang=${lang}`, site)!
      const label = L(b.button) || tx.update.download
      const rows = items.map((it) => `<tr><td width="18" style="vertical-align:top;padding:6px 0;"><div style="width:8px;height:8px;background:${T.accent};margin-top:6px;box-shadow:0 0 8px ${T.accent2};"></div></td>
<td style="padding:4px 0 8px 6px;font-family:${FONT};font-size:15px;line-height:22px;color:${T.text};"><b>${esc(it.title)}</b>${it.text ? `<br><span style="color:${T.muted};font-size:14px;">${esc(it.text)}</span>` : ''}</td></tr>`).join('')
      const banner = motif
        ? `<tr><td align="center" style="padding:18px;"><img src="${esc(motif)}" width="220" alt="${esc(fill(tx.update.banner, { version: b.version }))}" style="display:block;width:220px;height:auto;image-rendering:pixelated;"></td></tr>`
        : ''
      out.html.push(row(`<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${T.card2};border:1px solid ${T.line};border-radius:12px;">
      ${banner}
      <tr><td style="padding:${motif ? '0' : '18px'} 20px 14px 20px;"><table role="presentation" width="100%" cellpadding="0" cellspacing="0">${rows}</table></td></tr>
      <tr><td align="center" style="padding:0 20px 20px 20px;">${button(T, label, url)}
        <div style="font-family:${FONT};font-size:12px;color:${T.muted};margin-top:6px;">${esc(tx.update.autoUpdate)}</div></td></tr>
    </table>`))
      out.text.push([...items.map((it) => `- ${it.title}${it.text ? ` – ${it.text}` : ''}`), `${label}: ${url}`].join('\n'))
      return
    }
    case 'images': {
      const imgs = b.images.map((i) => ({ src: imageUrl(ctx, i.src), alt: L(i.alt), caption: L(i.caption) })).filter((i) => i.src)
      if (imgs.length === 0) return
      const cap = (c: string) => (c ? `<div style="font-family:${FONT};font-size:12px;color:${T.muted};padding-top:6px;">${esc(c)}</div>` : '')
      const pic = (i: typeof imgs[number], w: number) => `<img src="${esc(i.src!)}" width="${w}" alt="${esc(i.alt || i.caption)}" style="display:block;width:100%;height:auto;border-radius:10px;border:1px solid ${T.line};">`
      if (imgs.length === 1) {
        out.html.push(row(`${pic(imgs[0]!, 536)}${cap(imgs[0]!.caption)}`, '8px 32px 10px 32px'))
      } else {
        const [x, y] = imgs as [typeof imgs[number], typeof imgs[number]]
        out.html.push(row(`<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
      <td class="col" width="50%" style="padding-right:7px;vertical-align:top;">${pic(x, 261)}${cap(x.caption)}</td>
      <td class="col" width="50%" style="padding-left:7px;vertical-align:top;">${pic(y, 261)}${cap(y.caption)}</td>
    </tr></table>`, '8px 32px 10px 32px'))
      }
      out.text.push(imgs.map((i) => `[${i.caption || i.alt || 'Bild'}] ${i.src}`).join('\n'))
      return
    }
    case 'cosmetic': {
      const title = L(b.title)
      const kicker = L(b.kicker)
      const text = L(b.text)
      const img = imageUrl(ctx, b.image) ?? itemImage(ctx, b.item ?? null)
      const until = b.codeExpiresAt ? new Date(b.codeExpiresAt) : null
      const date = until && !Number.isNaN(until.getTime())
        ? new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : lang, { dateStyle: 'medium', timeZone: 'Europe/Berlin' }).format(until)
        : null
      const note = b.code ? [date ? fill(tx.cosmetic.validUntil, { date }) : null, tx.cosmetic.perAccount].filter(Boolean).join(' · ') : ''
      const st = mdStyle(T, 14, T.muted)
      const body = text ? mdToHtml(text, site, { ...st, p: `${st.p}margin:0 0 12px 0;` }) : ''
      const code = b.code
        ? `<table role="presentation" cellpadding="0" cellspacing="0"><tr><td style="border:2px dashed ${T.accent};border-radius:10px;padding:10px 18px;background:${T.bg};">
            <span style="font-family:${MONO};font-size:20px;font-weight:700;letter-spacing:3px;color:${T.accent2};">${esc(b.code)}</span></td></tr></table>
          <div style="font-family:${FONT};font-size:11px;color:${T.muted};margin-top:6px;">${esc(note)}</div>`
        : ''
      const redeem = b.code ? mdToHtml(tx.cosmetic.redeem, site, st) : ''
      const left = img
        ? `<td class="col" width="200" style="padding:16px;vertical-align:middle;"><img src="${esc(img)}" width="168" alt="${esc(plain(title))}" style="display:block;width:168px;max-width:100%;height:auto;border-radius:10px;image-rendering:pixelated;"></td>`
        : ''
      out.html.push(row(`<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${T.card2};border:1px solid ${T.line};border-radius:12px;">
      <tr>${left}
        <td class="col" style="padding:16px 18px 16px ${img ? '0' : '18px'};vertical-align:middle;">
          ${sectionTitle(ctx, look, kicker, title, img ? 300 : 500)}
          ${body}${redeem}${code}
        </td></tr>
    </table>`))
      out.text.push([kicker, plain(title).toUpperCase(), text ? mdToText(text, site) : null, b.code ? `${mdToText(tx.cosmetic.redeem, site)}\nCode: ${b.code}${note ? ` (${note})` : ''}` : null].filter(Boolean).join('\n'))
      return
    }
    case 'creator': {
      const c = creatorInfo(ctx, b.uuid)
      if (!c) return
      const text = L(b.text)
      const kicker = L(b.kicker) || 'Creator-Spotlight'
      const link = safeUrl(b.url || c.channel || `${site}/creators/${encodeURIComponent(c.name)}?lang=${lang}`, site)!
      const linkLabel = b.url || c.channel ? tx.creator.channel : tx.creator.page
      const st = mdStyle(T, 14, T.muted)
      const body = text ? mdToHtml(text, site, { ...st, p: `${st.p}margin:6px 0 6px 0;` }) : ''
      const code = c.code ? `<div style="font-family:${FONT};font-size:14px;line-height:21px;color:${T.muted};">${esc(tx.creator.code)}: <b style="color:${T.text};font-family:${MONO};letter-spacing:1px;">${esc(c.code)}</b></div>` : ''
      out.html.push(row(`${sectionTitle(ctx, look, kicker, c.name)}
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
      <td width="76" style="vertical-align:top;"><img src="${esc(headUrl(ctx, c.uuid))}" width="64" height="64" alt="${esc(c.name)}" style="display:block;border-radius:10px;border:2px solid ${T.accent};image-rendering:pixelated;"></td>
      <td style="vertical-align:top;font-family:${FONT};font-size:14px;line-height:21px;color:${T.muted};">
        <span style="background:${T.badge};color:${T.badgeText};padding:2px 8px;border-radius:99px;font-size:11px;font-weight:700;">${esc(tx.creator.tiers[c.tier])}</span>
        ${body}${code}
        <a href="${esc(link)}" style="color:${T.accent2};font-weight:700;text-decoration:none;">${esc(linkLabel)} &rarr;</a></td>
    </tr></table>`, '6px 32px 22px 32px'))
      out.text.push([kicker, c.name.toUpperCase(), tx.creator.tiers[c.tier], text ? mdToText(text, site) : null, c.code ? `${tx.creator.code}: ${c.code}` : null, `${linkLabel}: ${link}`].filter(Boolean).join('\n'))
      return
    }
  }
}

/** Kartenbild eines Kosmetik-Teils (Format v2) bzw. Textur eines Umhangs, sonst `null`. */
export function itemImage(ctx: AppContext, item: { kind: 'cape' | 'cosmetic', id: string } | null): string | null {
  if (!item) return null
  const site = ctx.config.siteUrl
  if (item.kind === 'cosmetic') {
    const c = getCosmetic(ctx, item.id)
    if (!c) return null
    const a = v2Assets(ctx, c)
    if (a) return v2Fields(a, site).card
    return c.sha256 ? `${site}/v1/cosmetics/${c.id}.png?v=${c.sha256.slice(0, 12)}` : null
  }
  const cape = getCape(ctx, item.id)
  return cape ? `${site}/v1/capes/${cape.id}.png?v=${cape.sha256.slice(0, 12)}` : null
}

export interface CreatorInfo {
  uuid: string
  name: string
  tier: 'starter' | 'partner' | 'top'
  code: string | null
  channel: string | null
}

/** Aktiver Creator für den Spotlight-Baustein (Name, Stufe, Haupt-Code, erster Kanal), sonst `null`. */
export function creatorInfo(ctx: AppContext, uuid: string): CreatorInfo | null {
  const r = one<{ uuid: string, tier: CreatorInfo['tier'], channels: string, name: string | null, code: string | null }>(
    ctx.db,
    `SELECT c.uuid, c.tier, c.channels, u.name, k.code FROM creators c JOIN users u ON u.uuid = c.uuid
     LEFT JOIN creator_codes k ON k.creator_uuid = c.uuid AND k.kind = 'main' AND k.active = 1
     WHERE c.uuid = ? AND c.state = 'active'`,
    uuid,
  )
  if (!r || !r.name) return null
  let channel: string | null = null
  try {
    const list = JSON.parse(r.channels) as { url?: unknown }[]
    const first = list.find((x) => typeof x.url === 'string' && /^https:\/\//.test(x.url))
    channel = first ? String(first.url) : null
  } catch {
    channel = null
  }
  return { uuid: r.uuid, name: r.name, tier: r.tier, code: r.code, channel }
}

// ---------------------------------------------------------------- Mails

/** Newsletter in einer Sprache und einem Look. Die Abmelde-Adresse ist ein Platzhalter (UNSUB_PLACEHOLDER). */
export function renderCampaign(ctx: AppContext, content: CampaignContent, look: Look, lang: NewsletterLang, opts: { test?: boolean } = {}): RenderedMail {
  const out: Out = { html: [], text: [] }
  for (const b of content.blocks) renderBlock(ctx, b, look, lang, out)
  const subject = pickLang(content.subject, lang) || 'TRS Launcher'
  const preheader = pickLang(content.preheader, lang)
  const tx = MAIL_TEXTS[lang]
  const shellOpts: ShellOpts = { look, lang, preheader, reason: tx.reasonNewsletter, unsubscribeUrl: UNSUB_PLACEHOLDER, test: opts.test }
  // Abstand unten vor dem Trenner wie im Entwurf.
  out.html.push('<tr><td style="height:10px;line-height:10px;font-size:0;">&nbsp;</td></tr>')
  return {
    subject: opts.test ? `[${tx.test}] ${subject}` : subject,
    preheader,
    html: shell(ctx, out.html.join('\n'), shellOpts),
    text: `${preheader ? `${preheader}\n\n` : ''}${out.text.join('\n\n')}\n\n${footerText(ctx, shellOpts)}\n`,
  }
}

/** Bestätigungs-Mail (Double-Opt-in) bzw. Adress-Bestätigung für Team-Benachrichtigungen. */
export function renderConfirm(ctx: AppContext, kind: 'newsletter' | 'team', look: Look, lang: MailLang, url: string, email: string): RenderedMail {
  const T = THEMES[look]
  const c = kind === 'newsletter' ? MAIL_TEXTS[lang].confirm : MAIL_TEXTS[lang].teamConfirm
  const inner = `
  <tr><td class="px" align="center" style="padding:26px 32px 30px 32px;">
    <div class="h1">${pixelTitle(ctx, look, c.title, 'h1', { align: 'center' })}</div>
    <div style="font-family:${FONT};font-size:15px;line-height:23px;color:${T.muted};margin:14px 0 20px 0;">${c.lead.split('\n').map(esc).join('<br>')}</div>
    ${button(T, c.button, url).replace('style="margin:6px 0;"', 'style="margin:6px auto;"')}
    <div style="font-family:${FONT};font-size:12px;color:${T.muted};margin-top:16px;">${esc(c.note)}</div>
  </td></tr>`
  const reason = fill(c.reason, { email })
  const shellOpts: ShellOpts = { look, lang, preheader: c.preheader, reason }
  return {
    subject: c.subject,
    preheader: c.preheader,
    html: shell(ctx, inner, shellOpts),
    text: `${plain(c.title)}\n\n${c.lead}\n\n${c.button}: ${url}\n\n${c.note}\n\n${footerText(ctx, shellOpts)}\n`,
  }
}

export interface TeamItem {
  /** Zeilen „Feld → Wert“ (Feldnamen aus MAIL_TEXTS.team.fields). */
  fields: [string, string][]
  /** Pfad im Admin (`/admin/…`). */
  path: string
}

/** Team-Benachrichtigung (Vorlage „team“): eine Art, ein oder mehrere Vorgänge (gebündelt). */
export function renderTeam(ctx: AppContext, look: Look, lang: MailLang, kind: keyof typeof MAIL_TEXTS.de.team.kinds, kindName: string, items: TeamItem[]): RenderedMail {
  const T = THEMES[look]
  const tx = MAIL_TEXTS[lang]
  const k = tx.team.kinds[kind]
  const title = items.length === 1 ? k.one : fill(k.many, { n: items.length })
  const shown = items.slice(0, 10)
  const table = (it: TeamItem) => {
    const tr = it.fields.map(([a, b]) => `<tr><td style="padding:7px 0;font-family:${FONT};font-size:13px;color:${T.muted};width:110px;">${esc(tx.team.fields[a] ?? a)}</td>
<td style="padding:7px 0;font-family:${FONT};font-size:14px;color:${T.text};font-weight:600;">${esc(b)}</td></tr>`).join('')
    return `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${T.card2};border:1px solid ${T.line};border-left:4px solid ${T.accent};border-radius:10px;margin-bottom:10px;">
      <tr><td style="padding:12px 18px;"><table role="presentation" width="100%" cellpadding="0" cellspacing="0">${tr}</table></td></tr></table>`
  }
  const more = items.length > shown.length ? `<div style="font-family:${FONT};font-size:13px;color:${T.muted};margin:4px 0 8px 0;">${esc(fill(tx.team.more, { n: items.length - shown.length }))}</div>` : ''
  const link = `${ctx.config.siteUrl}${items.length === 1 ? items[0]!.path : basePath(items[0]!.path)}`
  const inner = `
  <tr><td class="px" style="padding:20px 32px 26px 32px;">
    ${sectionTitle(ctx, look, tx.team.kicker, title)}
    ${shown.map(table).join('')}${more}
    <div style="height:6px"></div>${button(T, tx.team.open, link)}
  </td></tr>`
  const shellOpts: ShellOpts = { look, lang, preheader: title, reason: fill(tx.reasonTeam, { kind: kindName }), settingsUrl: `${ctx.config.siteUrl}/admin/newsletter?tab=notifications` }
  return {
    subject: `[TRS Team] ${title}`,
    preheader: title,
    html: shell(ctx, inner, shellOpts),
    text: `${tx.team.kicker}: ${title}\n\n${shown.map((it) => it.fields.map(([a, b]) => `${tx.team.fields[a] ?? a}: ${b}`).join('\n')).join('\n\n')}\n\n${tx.team.open}: ${link}\n\n${footerText(ctx, shellOpts)}\n`,
  }
}

/** `/admin/reports/r123` → `/admin/reports` (Liste bei gebündelten Mails). */
function basePath(p: string): string {
  const parts = p.split('/').filter(Boolean)
  return `/${parts.slice(0, 2).join('/')}`
}
