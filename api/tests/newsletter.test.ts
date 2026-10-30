import { readFileSync } from 'node:fs'
import { DatabaseSync } from 'node:sqlite'
import { PNG } from 'pngjs'
import { afterEach, describe, expect, it } from 'vitest'
import { loadConfig } from '../server/lib/config'
import { one, run } from '../server/lib/db'
import { migrate } from '../server/lib/db'
import { migrateMail } from '../server/lib/migrations'
import {
  cancelCampaign,
  changelogItems,
  contentSchema,
  createCampaign,
  generateBlockCode,
  getCampaign,
  hydrate,
  previewContent,
  scheduleCampaign,
  sendCampaign,
  testCampaign,
  updateCampaign,
} from '../server/lib/mail/campaigns'
import { headUrl, renderTitleSpec, titleImage, verifyHead, verifyTitle, faceFromSkin } from '../server/lib/mail/images'
import { authenticateKey, createKey, revokeKey, type NewsletterActor } from '../server/lib/mail/keys'
import { mdToHtml, mdToText } from '../server/lib/mail/markdown'
import { inlineImages } from '../server/lib/mail/inline'
import { processOutbox, recoverOutbox } from '../server/lib/mail/outbox'
import { layout, parseRuns, pixelText, renderTitle } from '../server/lib/mail/pixelfont'
import {
  audienceCount,
  confirmToken,
  deleteMyNewsletter,
  myNewsletter,
  normalizeEmail,
  setMyNewsletter,
  subscribe,
  subscriberStats,
  sweepSubscribers,
  unsubscribeByToken,
} from '../server/lib/mail/subscribers'
import { flushTeam, notifyTeam, setTeamPrefs, teamPrefsView, TEAM_BUNDLE_MS } from '../server/lib/mail/team-notify'
import { autoLook, LOOKS } from '../server/lib/mail/theme'
import { MailService, type OutgoingMail, type Sender, SendError } from '../server/lib/mail/transport'
import { ownerStaff, teamOf } from '../server/lib/team'
import type { CampaignContent } from '../shared/newsletter'
import { ADMIN, login, makeEnv, seedCosmeticFixtures, seedFixtures, solidPng, type TestEnv } from './helpers'
import { SmtpSink } from './smtpsink'

class FakeSender implements Sender {
  sent: OutgoingMail[] = []
  fail: SendError | null = null
  async send(m: OutgoingMail) {
    if (this.fail) {
      const e = this.fail
      this.fail = null
      throw e
    }
    this.sent.push(m)
    return { messageId: `m${this.sent.length}` }
  }
}

function mailEnv(): TestEnv & { sender: FakeSender } {
  const env = makeEnv({ env: { SITE_URL: 'https://site.example.test' } })
  const sender = new FakeSender()
  env.ctx.mail.setSender(sender)
  return Object.assign(env, { sender })
}

/** Festes Mail-Bild (public/mail) als CID-Anhang eingebettet? */
const embeds = (m: OutgoingMail, file: string) => !!m.attachments?.some((a) => a.content.equals(readFileSync(`public/mail/${file}`)))
const token = (m: OutgoingMail, re: RegExp) => re.exec(m.text)?.[1] ?? null
const confirmFrom = (m: OutgoingMail) => token(m, /token=([ct][A-Za-z0-9_-]{43})/)
const owner = (): NewsletterActor => ({ kind: 'staff', staff: ownerStaff(ADMIN) })

const sample: CampaignContent = {
  subject: { de: 'Version 0.31.0 – Der Coup', en: 'Version 0.31.0 – The Heist' },
  preheader: { de: 'Schwarzes Schaf und Pinguin-Gefolge', en: 'Black sheep and penguin entourage' },
  blocks: [
    { id: 'h', type: 'title', size: 'h1', kicker: { de: 'Hey,' }, text: { de: 'Version 0.31.0 – *Der Coup*', en: 'Version 0.31.0 – *The Heist*' } },
    { id: 't', type: 'text', text: { de: 'Ein **neuer** Begleiter treibt sein Unwesen. [Mehr](/blog)\n\n- Punkt eins\n- Punkt *zwei*', en: 'A **new** companion.' } },
    { id: 'u', type: 'update', version: '0.31.0', accent: '#ff5a4d', motif: '/news/0.31.0/banner.png', items: { de: [{ title: 'Schwarzes Schaf', text: 'Schleicht und schnappt.' }], en: [{ title: 'Black sheep', text: 'Sneaks and grabs.' }] } },
    { id: 'i', type: 'images', images: [{ src: 'https://example.test/a.png', caption: { de: 'Bild A' } }, { src: '/news/b.png', caption: { de: 'Bild B' } }] },
    { id: 'b', type: 'button', label: { de: 'Zum Blog' }, url: '/blog' },
    { id: 'd', type: 'divider' },
  ],
}

describe('pixel font', () => {
  it('uppercases, keeps accents and drops unknown characters', () => {
    expect(pixelText('Größe ÄÖÜ ñ ¡hola! 😀')).toBe('GRÖẞE ÄÖÜ Ñ ¡HOLA!')
  })

  it('splits accent runs and wraps long titles', () => {
    expect(parseRuns('Version *Der Coup*')).toEqual([{ text: 'Version ', accent: false }, { text: 'Der Coup', accent: true }])
    const lines = layout(parseRuns('Ein sehr langer Titel für eine kleine Breite'), 60)
    expect(lines.length).toBeGreaterThan(1)
    for (const l of lines) expect(l.reduce((w, c, i) => w + c.width + (i ? 1 : 0), 0)).toBeLessThanOrEqual(60)
  })

  it('renders a transparent @2x PNG with text and accent colours', () => {
    const r = renderTitle('AB *C*', { pixel: 3, maxWidth: 536, color: '#ffffff', accent: '#ff0000', shadow: '#000000', lineGap: 3 })
    const png = PNG.sync.read(r.png)
    expect(png.width).toBe(r.width * 2)
    expect(png.height).toBe(r.height * 2)
    expect(r.height).toBe(6 * 3) // 5 Zeilen Buchstabe + 1 Schatten, ohne leere Akzent-/Unterlängen-Zeilen
    const colours = new Set<string>()
    for (let i = 0; i < png.data.length; i += 4) if (png.data[i + 3]) colours.add(`${png.data[i]},${png.data[i + 1]},${png.data[i + 2]}`)
    expect([...colours].sort()).toEqual(['0,0,0', '255,0,0', '255,255,255'])
    // Mit Akzent oben wird das Bild höher.
    expect(renderTitle('Ä', { pixel: 3, maxWidth: 536, color: '#ffffff', accent: '#ff0000', shadow: null, lineGap: 3 }).height).toBe(7 * 3)
  })

  it('signs title URLs and refuses tampered ones', () => {
    const env = mailEnv()
    const img = titleImage(env.ctx, { text: 'Hallo *Welt*', kind: 'h1', look: 'winter' })
    const u = new URL(img.url)
    expect(u.origin).toBe('https://site.example.test')
    const sig = u.pathname.split('/').pop()!.slice(0, 24)
    const d = u.searchParams.get('d')!
    expect(verifyTitle(env.ctx, sig, d)).toEqual({ text: 'Hallo *Welt*', kind: 'h1', look: 'winter' })
    const forged = Buffer.from(JSON.stringify({ t: 'Böse', k: 'h1', l: 'winter' })).toString('base64url')
    expect(verifyTitle(env.ctx, sig, forged)).toBeNull()
    expect(renderTitleSpec({ text: 'Hallo *Welt*', kind: 'h1', look: 'winter' }).width).toBe(img.width)
  })

  it('signs head URLs and cuts the face with the hat layer', () => {
    const env = mailEnv()
    const uuid = 'a'.repeat(32)
    const u = new URL(headUrl(env.ctx, uuid))
    expect(verifyHead(env.ctx, uuid, u.searchParams.get('s')!)).toBe(true)
    expect(verifyHead(env.ctx, 'b'.repeat(32), u.searchParams.get('s')!)).toBe(false)
    const face = PNG.sync.read(faceFromSkin(solidPng(64, 64, [10, 20, 30, 255])))
    expect(face.width).toBe(128)
    expect([...face.data.subarray(0, 4)]).toEqual([10, 20, 30, 255])
  })
})

describe('markdown for mails', () => {
  it('escapes html, keeps safe links only and builds lists', () => {
    const st = { p: 'P;', li: 'L;', bullet: 'B;', link: 'A;', strong: 'S;' }
    const html = mdToHtml('<script>x</script> **fett** [ok](https://a.test) [bad](javascript:alert(1))\n\n- eins\n- zwei', 'https://site', st)
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;script&gt;')
    expect(html).toContain('<b style="S;">fett</b>')
    expect(html).toContain('href="https://a.test/"')
    expect(html).not.toContain('javascript:')
    expect(html).toContain('<table')
    expect(mdToText('[Blog](/blog) und **fett**', 'https://site')).toBe('Blog (https://site/blog) und fett')
  })
})

describe('config', () => {
  const base = { DATA_DIR: '/tmp/x', SECRET_KEY: 'k'.repeat(40) }
  it('mail is off without SMTP_HOST and parses a full SMTP setup', () => {
    expect(loadConfig(base).mail).toBeNull()
    const c = loadConfig({ ...base, SMTP_HOST: 'smtp.example.test', SMTP_PORT: '465', SMTP_SECURE: 'true', SMTP_USER: 'u', SMTP_PASS: 'p', MAIL_TEAM_TO: 'a@x.test, b@x.test', MAIL_RATE_PER_MIN: '30' }).mail!
    expect(c).toMatchObject({ host: 'smtp.example.test', port: 465, secure: true, teamTo: ['a@x.test', 'b@x.test'], ratePerMin: 30 })
    expect(c.system).toEqual({ user: 'u', pass: 'p', from: 'TRS Launcher <no-reply@trs-launcher.de>' })
    // Ohne NEWSLETTER_*: Kampagnen über das System-Konto.
    expect(c.newsletter).toEqual({ user: 'u', pass: 'p', from: 'TRS Launcher <no-reply@trs-launcher.de>', shared: true })
    const two = loadConfig({ ...base, SMTP_HOST: 'mail.example.test', SMTP_USER: 'no-reply@x.test', SMTP_PASS: 'a', NEWSLETTER_SMTP_USER: 'newsletter@x.test', NEWSLETTER_SMTP_PASS: 'b' }).mail!
    expect(two.newsletter).toEqual({ user: 'newsletter@x.test', pass: 'b', from: 'TRS Launcher <newsletter@trs-launcher.de>', shared: false })
    expect(() => loadConfig({ ...base, SMTP_HOST: 'mail.example.test', NEWSLETTER_SMTP_USER: 'n' })).toThrow(/NEWSLETTER_SMTP_USER and NEWSLETTER_SMTP_PASS/)
  })
  it('rejects broken values without echoing them', () => {
    expect(() => loadConfig({ ...base, SMTP_HOST: 'smtp.example.test', SMTP_USER: 'only-user' })).toThrow(/SMTP_USER and SMTP_PASS/)
    expect(() => loadConfig({ ...base, SMTP_HOST: 'smtp.example.test', MAIL_FROM: 'nope' })).toThrow(/MAIL_FROM/)
    try {
      loadConfig({ ...base, SMTP_HOST: 'smtp.example.test', MAIL_TEAM_TO: 'secret-value' })
    } catch (e) {
      expect(String(e)).not.toContain('secret-value')
    }
  })
})

describe('subscribers (double opt-in)', () => {
  it('normalizes addresses', () => {
    expect(normalizeEmail('  Steve@Example.TEST ')).toBe('steve@example.test')
    expect(normalizeEmail('nope')).toBeNull()
    expect(normalizeEmail('a@b')).toBeNull()
  })

  it('website: pending → confirm mail → confirmed; repeated sign-ups stay quiet', async () => {
    const env = mailEnv()
    expect(subscribe(env.ctx, { email: 'Steve@Example.test', lang: 'de', source: 'website' })).toEqual({ mail: true, state: 'new' })
    await processOutbox(env.ctx, 10)
    expect(env.sender.sent).toHaveLength(1)
    const m = env.sender.sent[0]!
    expect(m.to).toBe('steve@example.test')
    expect(m.subject).toContain('bestätige')
    expect(m.html).toContain('color-scheme')
    expect(m.headers).toBeUndefined() // Bestätigung ist keine Listen-Mail
    // Zweite Anmeldung kurz danach: gedrosselt, keine zweite Mail.
    expect(subscribe(env.ctx, { email: 'steve@example.test', lang: 'en', source: 'website' }).state).toBe('throttled')
    const tok = confirmFrom(m)!
    expect(confirmToken(env.ctx, tok)).toEqual({ kind: 'newsletter', email: 's***e@example.test' })
    expect(() => confirmToken(env.ctx, tok)).toThrow(/not valid/)
    const row = one<{ status: string, lang: string, consent_version: string, consent_source: string, confirmed_at: number }>(env.ctx.db, 'SELECT * FROM newsletter_subscribers')!
    expect(row).toMatchObject({ status: 'confirmed', lang: 'en', consent_source: 'website' })
    expect(row.consent_version).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    // Schon bestätigt: still.
    expect(subscribe(env.ctx, { email: 'steve@example.test', lang: 'de', source: 'website' })).toEqual({ mail: false, state: 'confirmed' })
    expect(one<{ n: number }>(env.ctx.db, 'SELECT COUNT(*) AS n FROM newsletter_subscribers WHERE confirm_hash IS NOT NULL')!.n).toBe(0)
  })

  it('confirm links expire after 48 hours', async () => {
    const env = mailEnv()
    subscribe(env.ctx, { email: 'late@example.test', lang: 'en', source: 'website' })
    await processOutbox(env.ctx, 10)
    env.clock.advance(49 * 60 * 60 * 1000)
    expect(() => confirmToken(env.ctx, confirmFrom(env.sender.sent[0]!)!)).toThrow(/expired/)
  })

  it('without SMTP every sign-up answers 503 mail_unavailable', () => {
    const env = makeEnv()
    expect(env.ctx.mail.available).toBe(false)
    expect(() => subscribe(env.ctx, { email: 'x@example.test', lang: 'de', source: 'website' })).toThrow(/not configured/)
  })

  it('launcher: links the account only after confirmation, then unsubscribes', async () => {
    const env = mailEnv()
    const { user } = await login(env, 'Steve')
    expect(myNewsletter(env.ctx, user.uuid).status).toBe('none')
    expect(setMyNewsletter(env.ctx, user.uuid, { email: 'steve@example.test', lang: 'de' })).toMatchObject({ status: 'pending', email: 'steve@example.test', lang: 'de' })
    await processOutbox(env.ctx, 10)
    confirmToken(env.ctx, confirmFrom(env.sender.sent[0]!)!)
    expect(myNewsletter(env.ctx, user.uuid)).toMatchObject({ status: 'confirmed', source: 'launcher' })
    // Gleiche Adresse, andere Sprache: keine Mail, nur Sprache.
    setMyNewsletter(env.ctx, user.uuid, { email: 'STEVE@example.test', lang: 'es' })
    await processOutbox(env.ctx, 10)
    expect(env.sender.sent).toHaveLength(1)
    expect(myNewsletter(env.ctx, user.uuid)).toMatchObject({ status: 'confirmed', lang: 'es' })
    expect(deleteMyNewsletter(env.ctx, user.uuid).status).toBe('unsubscribed')
  })

  it('launcher: an address confirmed by someone else stays theirs until the link is clicked', async () => {
    const env = mailEnv()
    subscribe(env.ctx, { email: 'owner@example.test', lang: 'de', source: 'website' })
    await processOutbox(env.ctx, 10)
    confirmToken(env.ctx, confirmFrom(env.sender.sent[0]!)!)
    const { user } = await login(env, 'Intruder')
    expect(setMyNewsletter(env.ctx, user.uuid, { email: 'owner@example.test', lang: 'en' }).status).toBe('pending')
    // Löschen im Launcher meldet die fremde, bestätigte Adresse NICHT ab.
    deleteMyNewsletter(env.ctx, user.uuid)
    expect(one<{ status: string, uuid: string | null }>(env.ctx.db, "SELECT status, uuid FROM newsletter_subscribers WHERE email = 'owner@example.test'")).toEqual({ status: 'confirmed', uuid: null })
  })

  it('one-click unsubscribe works once per sign-up and resubscribing needs a new confirmation', async () => {
    const env = mailEnv()
    subscribe(env.ctx, { email: 'a@example.test', lang: 'de', source: 'website' })
    await processOutbox(env.ctx, 10)
    confirmToken(env.ctx, confirmFrom(env.sender.sent[0]!)!)
    const c = createCampaign(env.ctx, owner(), { content: sample })
    sendCampaign(env.ctx, owner(), c.id, {}, (l) => audienceCount(env.ctx, l))
    await processOutbox(env.ctx, 10)
    const nl = env.sender.sent[1]!
    expect(nl.headers!['List-Unsubscribe-Post']).toBe('List-Unsubscribe=One-Click')
    const unsub = /<(https:\/\/site\.example\.test\/newsletter\/unsubscribe\?token=([^>]+))>/.exec(nl.headers!['List-Unsubscribe']!)!
    expect(nl.html).toContain(unsub[1])
        expect(unsubscribeByToken(env.ctx, unsub[2]!).email).toBe('a@example.test')
    expect(() => unsubscribeByToken(env.ctx, `${unsub[2]!.slice(0, -1)}${unsub[2]!.endsWith('0') ? '1' : '0'}`)).toThrow(/not valid/)
    // Erneut anmelden: wieder ausstehend, der alte Abmelde-Link gilt nicht mehr.
    expect(subscribe(env.ctx, { email: 'a@example.test', lang: 'de', source: 'website' }).state).toBe('resubscribed')
    expect(() => unsubscribeByToken(env.ctx, unsub[2]!)).toThrow(/not valid/)
  })

  it('stats count by status, language and source; old pending/unsubscribed rows are swept', async () => {
    const env = mailEnv()
    subscribe(env.ctx, { email: 'a@example.test', lang: 'de', source: 'website' })
    subscribe(env.ctx, { email: 'b@example.test', lang: 'en', source: 'website' })
    await processOutbox(env.ctx, 10)
    confirmToken(env.ctx, confirmFrom(env.sender.sent[1]!)!)
    expect(subscriberStats(env.ctx)).toMatchObject({ total: 2, byStatus: { pending: 1, confirmed: 1, unsubscribed: 0 }, byLang: { en: 1, de: 0, es: 0 }, bySource: { website: 1, launcher: 0 } })
    env.clock.advance(8 * 24 * 60 * 60 * 1000)
    expect(sweepSubscribers(env.ctx)).toBe(1)
    expect(audienceCount(env.ctx, null)).toBe(1)
    expect(audienceCount(env.ctx, ['de'])).toBe(0)
  })
})

describe('campaigns', () => {
  async function confirmed(env: ReturnType<typeof mailEnv>, email: string, lang: 'de' | 'en' | 'es') {
    const before = env.sender.sent.length
    subscribe(env.ctx, { email, lang, source: 'website' })
    await processOutbox(env.ctx, 10)
    confirmToken(env.ctx, confirmFrom(env.sender.sent[before]!)!)
  }

  it('validates content (block ids unique, safe urls)', () => {
    expect(contentSchema.safeParse({ blocks: [{ id: 'a', type: 'divider' }, { id: 'a', type: 'divider' }] }).success).toBe(false)
    expect(contentSchema.safeParse({ blocks: [{ id: 'a', type: 'button', label: { de: 'x' }, url: 'javascript:alert(1)' }] }).success).toBe(false)
    expect(contentSchema.safeParse({ blocks: [{ id: 'a', type: 'images', images: [{ src: 'http://insecure.test/a.png' }] }] }).success).toBe(false)
    expect(contentSchema.safeParse(sample).success).toBe(true)
  })

  it('renders every block in all four looks with pixel titles, alt text and a text part', () => {
    const env = mailEnv()
    seedFixtures(env)
    for (const look of LOOKS) {
      const p = previewContent(env.ctx, sample, look, 'de')
      expect(p.look).toBe(look)
      expect(p.subject).toBe('Version 0.31.0 – Der Coup')
      expect(p.html).toContain(`/mail/head-${look}.png`)
      expect(p.html).toContain(`/mail/div-${look}.png`)
      expect(p.html).toMatch(/\/v1\/mail\/px\/[0-9a-f]{24}\.png\?d=/)
      expect(p.html).toContain('alt="Version 0.31.0 – Der Coup"')
      expect(p.html).toContain('https://site.example.test/news/0.31.0/banner.png')
      expect(p.html).toContain('Schwarzes Schaf')
      expect(p.html).toContain('@media (max-width:620px)')
      expect(p.html).not.toContain('fonts.googleapis.com')
      expect(p.text).toContain('VERSION 0.31.0 – DER COUP')
      expect(p.text).toContain('Zum Blog: https://site.example.test/blog')
    }
    // Spanisch fehlt → Englisch.
    const es = previewContent(env.ctx, sample, 'redstone', 'es')
    expect(es.subject).toBe('Version 0.31.0 – The Heist')
    expect(es.html).toContain('Black sheep')
  })

  it('auto look follows the active website event', () => {
    const env = mailEnv()
    expect(autoLook(env.ctx)).toBe('redstone')
    run(env.ctx.db, "UPDATE events SET enabled = 1 WHERE id = 'halloween'")
    expect(autoLook(env.ctx)).toBe('halloween')
    run(env.ctx.db, "INSERT INTO events (id, enabled) VALUES ('winter2026', 1)")
    expect(autoLook(env.ctx)).toBe('halloween')
    run(env.ctx.db, "UPDATE events SET enabled = 0 WHERE id = 'halloween'")
    expect(autoLook(env.ctx)).toBe('winter')
    const c = createCampaign(env.ctx, owner(), { content: sample })
    expect(c.look).toBe('auto')
    expect(c.resolvedLook).toBe('winter')
  })

  it('protects edits with rev and locks sent campaigns', async () => {
    const env = mailEnv()
    const c = createCampaign(env.ctx, owner(), { name: 'Coup', content: sample })
    const u = updateCampaign(env.ctx, owner(), c.id, { rev: c.rev, look: 'fox' })
    expect(u.rev).toBe(c.rev + 1)
    expect(() => updateCampaign(env.ctx, owner(), c.id, { rev: c.rev, look: 'winter' })).toThrow(/changed in the meantime/)
    await confirmed(env, 'x@example.test', 'de')
    sendCampaign(env.ctx, owner(), c.id, {}, (l) => audienceCount(env.ctx, l))
    expect(() => updateCampaign(env.ctx, owner(), c.id, { rev: getCampaign(env.ctx, c.id).rev, look: 'winter' })).toThrow(/cannot be changed/)
  })

  it('sends to confirmed subscribers in their language, counts and never sends twice after a restart', async () => {
    const env = mailEnv()
    await confirmed(env, 'de@example.test', 'de')
    await confirmed(env, 'en@example.test', 'en')
    await confirmed(env, 'es@example.test', 'es')
    subscribe(env.ctx, { email: 'pending@example.test', lang: 'de', source: 'website' })
    await processOutbox(env.ctx, 10)
    const before = env.sender.sent.length
    const c = createCampaign(env.ctx, owner(), { content: sample, look: 'winter' })
    expect(() => sendCampaign(env.ctx, owner(), c.id, { expectedCount: 2 }, (l) => audienceCount(env.ctx, l))).toThrow(/changed/)
    const r = sendCampaign(env.ctx, owner(), c.id, { expectedCount: 3 }, (l) => audienceCount(env.ctx, l))
    expect(r.queued).toBe(3)
    expect(r.campaign.status).toBe('sending')
    // Ein Durchgang mit Budget 2 → zwei raus, dann „Neustart“ mitten im dritten.
    await processOutbox(env.ctx, 2)
    run(env.ctx.db, "UPDATE mail_outbox SET status = 'sending' WHERE kind = 'campaign' AND status = 'queued'")
    expect(recoverOutbox(env.ctx)).toBe(1)
    await processOutbox(env.ctx, 10)
    const sent = env.sender.sent.slice(before)
    expect(sent).toHaveLength(2)
    const byTo = Object.fromEntries(sent.map((m) => [m.to, m]))
    expect(byTo['de@example.test']?.subject ?? byTo['en@example.test']?.subject).toBeTruthy()
    if (byTo['en@example.test']) expect(byTo['en@example.test'].subject).toBe('Version 0.31.0 – The Heist')
    for (const m of sent) {
      expect(embeds(m, 'head-winter.png')).toBe(true)
      expect(m.html).not.toContain('%%TRS_UNSUBSCRIBE_URL%%')
      expect(m.headers!['List-Unsubscribe']).toMatch(/^<https:\/\/site\.example\.test\/newsletter\/unsubscribe\?token=\d+\.[0-9a-f]{40}>$/)
    }
    const done = getCampaign(env.ctx, c.id)
    expect(done).toMatchObject({ status: 'sent', total: 3, sent: 2, failed: 1, queued: 0, resolvedLook: 'winter' })
  })

  it('uses the no-reply account for confirmations and the newsletter account for campaigns and tests', async () => {
    const env = makeEnv({ env: { SITE_URL: 'https://site.example.test' } })
    const sys = new FakeSender()
    const nl = new FakeSender()
    env.ctx.mail.setSender(sys, 'system')
    env.ctx.mail.setSender(nl, 'newsletter')
    expect(env.ctx.mail.shared).toBe(false)
    subscribe(env.ctx, { email: 'a@example.test', lang: 'de', source: 'website' })
    await processOutbox(env.ctx, 10)
    confirmToken(env.ctx, confirmFrom(sys.sent[0]!)!)
    const c = createCampaign(env.ctx, owner(), { content: sample })
    sendCampaign(env.ctx, owner(), c.id, {}, (l) => audienceCount(env.ctx, l))
    await processOutbox(env.ctx, 10)
    expect(sys.sent.map((m) => m.subject)).toEqual([expect.stringContaining('bestätige')])
    expect(nl.sent.map((m) => m.subject)).toEqual(['Version 0.31.0 – Der Coup'])
  })

  it('sends only to chosen languages, retries temporary errors and gives up on permanent ones', async () => {
    const env = mailEnv()
    await confirmed(env, 'de1@example.test', 'de')
    await confirmed(env, 'de2@example.test', 'de')
    await confirmed(env, 'en@example.test', 'en')
    const before = env.sender.sent.length
    const c = createCampaign(env.ctx, owner(), { content: sample })
    expect(sendCampaign(env.ctx, owner(), c.id, { langs: ['de'] }, (l) => audienceCount(env.ctx, l)).queued).toBe(2)
    env.sender.fail = new SendError('451 try later', false)
    await processOutbox(env.ctx, 10)
    expect(env.sender.sent.length - before).toBe(1)
    env.clock.advance(61_000)
    await processOutbox(env.ctx, 10)
    expect(getCampaign(env.ctx, c.id)).toMatchObject({ status: 'sent', sent: 2, failed: 0 })
    expect(env.sender.sent.slice(before).every((m) => m.to.startsWith('de'))).toBe(true)
    const c2 = createCampaign(env.ctx, owner(), { content: sample })
    sendCampaign(env.ctx, owner(), c2.id, { langs: ['en'] }, (l) => audienceCount(env.ctx, l))
    env.sender.fail = new SendError('550 no such user', true)
    await processOutbox(env.ctx, 10)
    expect(getCampaign(env.ctx, c2.id)).toMatchObject({ status: 'sent', sent: 0, failed: 1 })
  })

  it('schedules, starts when due and can be cancelled', async () => {
    const env = mailEnv()
    await confirmed(env, 'x@example.test', 'de')
    const c = createCampaign(env.ctx, owner(), { content: sample })
    expect(() => scheduleCampaign(env.ctx, owner(), c.id, { at: new Date(env.clock.t - 1000).toISOString() })).toThrow(/future/)
    const s = scheduleCampaign(env.ctx, owner(), c.id, { at: new Date(env.clock.t + 3600_000).toISOString(), langs: ['de'] })
    expect(s).toMatchObject({ status: 'scheduled', langs: ['de'] })
    await processOutbox(env.ctx, 10)
    expect(getCampaign(env.ctx, c.id).status).toBe('scheduled')
    env.clock.advance(3600_001)
    await processOutbox(env.ctx, 10)
    expect(getCampaign(env.ctx, c.id)).toMatchObject({ status: 'sent', sent: 1 })
    const c2 = createCampaign(env.ctx, owner(), { content: sample })
    scheduleCampaign(env.ctx, owner(), c2.id, { at: new Date(env.clock.t + 3600_000).toISOString() })
    expect(cancelCampaign(env.ctx, owner(), c2.id).status).toBe('cancelled')
  })

  it('test mails go to the own confirmed team address with [TEST] in the subject', async () => {
    const env = mailEnv()
    await login(env, 'Owner', ADMIN)
    const c = createCampaign(env.ctx, owner(), { content: sample })
    expect(() => testCampaign(env.ctx, owner(), c.id, { lang: 'de' })).toThrow(/own e-mail address/)
    const staff = teamOf(env.ctx, ADMIN)!
    setTeamPrefs(env.ctx, staff, { email: 'me@example.test', kinds: [], lang: 'de' }, normalizeEmail)
    await processOutbox(env.ctx, 10)
    expect(confirmToken(env.ctx, confirmFrom(env.sender.sent[0]!)!)).toEqual({ kind: 'team', email: 'm@example.test' })
    expect(testCampaign(env.ctx, owner(), c.id, { lang: 'en', look: 'fox' })).toEqual({ to: 'm@example.test' })
    await processOutbox(env.ctx, 10)
    const m = env.sender.sent.at(-1)!
    expect(m.subject).toBe('[TEST] Version 0.31.0 – The Heist')
    expect(embeds(m, 'head-fox.png')).toBe(true)
  })

  it('cosmetic cards get a code from the code system', () => {
    const env = mailEnv()
    seedFixtures(env)
    seedCosmeticFixtures(env)
    const content: CampaignContent = { subject: { de: 'Code' }, preheader: {}, blocks: [{ id: 'k', type: 'cosmetic', item: { kind: 'cape', id: 'emerald' }, title: { de: 'Smaragd *gratis*' }, text: { de: 'Nur für Leser.' } }] }
    const c = createCampaign(env.ctx, owner(), { content })
    const g = generateBlockCode(env.ctx, owner(), c.id, { blockId: 'k', maxUses: 100, expiresAt: new Date(env.clock.t + 30 * 86400_000).toISOString(), rev: c.rev })
    const b = g.content.blocks[0] as Extract<CampaignContent['blocks'][number], { type: 'cosmetic' }>
    expect(b.code).toMatch(/^[A-Z0-9-]{8,}$/)
    expect(one<{ max_uses: number, note: string }>(env.ctx.db, 'SELECT max_uses, note FROM codes WHERE id = ?', b.codeId!)).toEqual({ max_uses: 100, note: `newsletter ${c.id}` })
    const p = previewContent(env.ctx, g.content, 'redstone', 'de')
    expect(p.html).toContain(b.code!)
    expect(p.html).toContain('/v1/capes/emerald.png')
    expect(() => generateBlockCode(env.ctx, owner(), c.id, { blockId: 'k', maxUses: 1, rev: g.rev })).toThrow(/already has a code/)
  })

  it('creator spotlight shows head, tier, main code and channel', async () => {
    const env = mailEnv()
    const { user } = await login(env, 'PixelCST')
    const t = env.clock.t
    run(env.ctx.db, "INSERT INTO creators (uuid, tier, state, channels, created_at, updated_at) VALUES (?, 'top', 'active', ?, ?, ?)", user.uuid, JSON.stringify([{ platform: 'youtube', url: 'https://youtube.com/@pixelcst', followers: 1000 }]), t, t)
    run(env.ctx.db, "INSERT INTO creator_codes (creator_uuid, code, kind, active, created_at) VALUES (?, 'PIXELCST', 'main', 1, ?)", user.uuid, t)
    const p = previewContent(env.ctx, { subject: { de: 'x' }, preheader: {}, blocks: [{ id: 'c', type: 'creator', uuid: user.uuid, text: { de: 'Der Pinguin-König.' } }] }, 'redstone', 'de')
    expect(p.html).toContain(`/v1/mail/head/${user.uuid}.png?s=`)
    expect(p.html).toContain('TOP-CREATOR')
    expect(p.html).toContain('PIXELCST')
    expect(p.html).toContain('https://youtube.com/@pixelcst')
  })

  it('fills update blocks from the changelog', async () => {
    const md = '- **Black sheep.** Sneaks around [and](https://x) grabs *loot*.\n- **Penguins:** More of them.\nOther text'
    expect(changelogItems(md)).toEqual([{ title: 'Black sheep', text: 'Sneaks around and grabs loot.' }, { title: 'Penguins', text: 'More of them.' }])
    const post = { version: '0.31.0', date: '2026-10-25', title: { en: 'The Heist', de: 'Der Coup' }, banner: { accent: '#ff5a4d', motif: 'https://raw.example/b.png' }, markdown: { en: md, de: '- **Schaf.** Schleicht.' }, headlines: { en: [], de: [] }, gallery: { en: [], de: [] }, contributors: [] }
    const out = await hydrate({ subject: {}, preheader: {}, blocks: [{ id: 'u', type: 'update', version: '0.31.0', items: {} }] }, async () => post)
    expect(out.blocks[0]).toMatchObject({ type: 'update', title: { en: 'The Heist', de: 'Der Coup' }, accent: '#ff5a4d', motif: 'https://raw.example/b.png', items: { de: [{ title: 'Schaf', text: 'Schleicht.' }] } })
    await expect(hydrate({ subject: {}, preheader: {}, blocks: [{ id: 'u', type: 'update', version: '9.9.9', items: {} }] }, async () => null)).rejects.toThrow(/not in the changelog/)
  })
})

describe('API keys', () => {
  it('only owners/admins create keys; keys carry exactly their permissions and can be revoked', () => {
    const env = mailEnv()
    const staff = ownerStaff(ADMIN)
    const { key, token } = createKey(env.ctx, staff, { name: 'Claude', permissions: ['newsletter.manage', 'newsletter.send'], testEmail: 'claude@example.test' })
    expect(token).toMatch(/^trsk_[A-Za-z0-9_-]{43}$/)
    expect(key).toMatchObject({ name: 'Claude', hint: token.slice(-4), permissions: ['newsletter.manage', 'newsletter.send'] })
    expect(one<{ key_hash: string }>(env.ctx.db, 'SELECT key_hash FROM mail_api_keys')!.key_hash).not.toContain(token.slice(5))
    const a = authenticateKey(env.ctx, token)
    expect(a.kind === 'key' && a.perms.has('newsletter.send')).toBe(true)
    const c = createCampaign(env.ctx, a, { content: sample })
    expect(c.createdBy).toEqual({ kind: 'key', uuid: null, name: 'Claude' })
    const ro = createKey(env.ctx, staff, { name: 'Drafts only', permissions: ['newsletter.manage'], testEmail: null })
    const b = authenticateKey(env.ctx, ro.token)
    expect(() => scheduleCampaign(env.ctx, b, c.id, { at: new Date(env.clock.t + 3600_000).toISOString() })).toThrow(/newsletter.send/)
    revokeKey(env.ctx, staff, key.id)
    expect(() => authenticateKey(env.ctx, token)).toThrow(/revoked/)
    const mod = { ...ownerStaff('c'.repeat(32)), owner: false, rank: 500 }
    expect(() => createKey(env.ctx, mod, { name: 'x', permissions: ['newsletter.manage'], testEmail: null })).toThrow(/owners and admins/)
  })
})

describe('team notifications', () => {
  it('bundles events per kind (max one mail per 5 minutes) and falls back to MAIL_TEAM_TO', async () => {
    const env = makeEnv({ env: { SITE_URL: 'https://site.example.test', SMTP_HOST: '127.0.0.1', MAIL_TEAM_TO: 'team@example.test' } })
    const sender = new FakeSender()
    env.ctx.mail.setSender(sender)
    notifyTeam(env.ctx, 'application', { fields: [['job', 'Moderator (DE)'], ['from', 'Lencraft151']], path: '/admin/applications/a1' })
    notifyTeam(env.ctx, 'application', { fields: [['job', 'Moderator (DE)'], ['from', 'Steve']], path: '/admin/applications/a2' })
    notifyTeam(env.ctx, 'report', { fields: [['reason', 'spam']], path: '/admin/reports/r1' })
    await processOutbox(env.ctx, 10)
    expect(sender.sent.map((m) => [m.to, m.subject])).toEqual([
      ['team@example.test', '[TRS Team] Neue Bewerbung eingegangen'],
      ['team@example.test', '[TRS Team] Neue Meldung eingegangen'],
    ])
    expect(sender.sent[0]!.html).toContain('Lencraft151')
    expect(sender.sent[0]!.html).toContain('https://site.example.test/admin/applications/a1')
    expect(flushTeam(env.ctx)).toBe(0)
    env.clock.advance(TEAM_BUNDLE_MS)
    expect(flushTeam(env.ctx)).toBe(1)
    await processOutbox(env.ctx, 10)
    expect(sender.sent[2]!.subject).toBe('[TRS Team] Neue Bewerbung eingegangen')
    expect(sender.sent[2]!.html).toContain('Steve')
  })

  it('members with the permission and a confirmed address get the mail instead of the fallback', async () => {
    const env = makeEnv({ env: { SITE_URL: 'https://site.example.test', SMTP_HOST: '127.0.0.1', MAIL_TEAM_TO: 'team@example.test' } })
    const sender = new FakeSender()
    env.ctx.mail.setSender(sender)
    const { user } = await login(env, 'Mod')
    run(env.ctx.db, "INSERT INTO team_members (uuid, role_id, granted_at, granted_by) VALUES (?, 'moderator', 0, 'test')", user.uuid)
    const staff = teamOf(env.ctx, user.uuid)!
    expect(teamPrefsView(env.ctx, staff).available).toEqual(['report', 'appeal'])
    const r = setTeamPrefs(env.ctx, staff, { email: 'mod@example.test', kinds: ['report', 'application'], lang: 'en' }, normalizeEmail)
    expect(r.confirmationSent).toBe(true)
    expect(r.view.kinds).toEqual(['report'])
    await processOutbox(env.ctx, 10)
    confirmToken(env.ctx, confirmFrom(sender.sent[0]!)!)
    notifyTeam(env.ctx, 'report', { fields: [['reason', 'spam']], path: '/admin/reports/r1' })
    await processOutbox(env.ctx, 10)
    expect(sender.sent[1]).toMatchObject({ to: 'mod@example.test', subject: '[TRS Team] New report received' })
  })
})

/** Teile einer Mail dekodieren (base64 / quoted-printable), damit Links im Text lesbar sind. */
function decodeMime(raw: string): string {
  const out = [raw]
  for (const m of raw.matchAll(/Content-Transfer-Encoding: (base64|quoted-printable)\r\n(?:[^\r\n]+\r\n)*\r\n([\s\S]*?)(?=\r\n--)/gi)) {
    const body = m[2]!
    out.push(m[1]!.toLowerCase() === 'base64'
      ? Buffer.from(body.replace(/\s+/g, ''), 'base64').toString('utf8')
      : body.replace(/=\r\n/g, '').replace(/=([0-9A-F]{2})/g, (_, h: string) => String.fromCharCode(Number.parseInt(h, 16))))
  }
  return out.join('\n')
}

describe('inline images', () => {
  it('embeds own images as CID attachments and leaves foreign or broken ones alone', async () => {
    const env = mailEnv()
    const title = titleImage(env.ctx, { text: 'Hallo', kind: 'h1', look: 'redstone' })
    const shot = solidPng(4, 4, [10, 20, 30, 255])
    const seen: string[] = []
    const fetcher = (async (u: string) => {
      seen.push(u)
      if (u.endsWith('/shot.png')) return new Response(shot, { headers: { 'content-type': 'image/png' } })
      if (u.endsWith('/page')) return new Response('<html>', { headers: { 'content-type': 'text/html' } })
      return new Response('nope', { status: 404 })
    }) as typeof fetch
    const html = [
      `<img src="https://site.example.test/mail/logo.png" alt="">`,
      `<img src="${title.url.replace(/&/g, '&amp;')}" width="${title.width}">`,
      `<img src="https://site.example.test/shot.png"><img src="https://site.example.test/shot.png">`,
      `<img src="https://site.example.test/page">`,
      `<img src="https://site.example.test/missing.png">`,
      `<img src="https://elsewhere.example.test/x.png">`,
    ].join('')
    const out = await inlineImages(env.ctx, { to: 'a@example.test', subject: 's', html, text: 't' }, fetcher)
    expect(out.attachments).toHaveLength(3)
    expect(out.attachments!.map((a) => a.contentType)).toEqual(['image/png', 'image/png', 'image/png'])
    expect(out.attachments![1]!.content.equals(renderTitleSpec({ text: 'Hallo', kind: 'h1', look: 'redstone' }).png)).toBe(true)
    expect(out.html.match(/src="cid:/g)).toHaveLength(4) // Screenshot zweimal, ein Anhang
    expect(out.html).toContain('src="https://site.example.test/page"')
    expect(out.html).toContain('src="https://site.example.test/missing.png"')
    expect(out.html).toContain('src="https://elsewhere.example.test/x.png"')
    // Logo von der Platte, Überschrift gerendert, fremde Adressen nie abgerufen.
    expect(seen.some((u) => u.includes('/mail/logo.png') || u.includes('/v1/mail/px/') || u.includes('elsewhere'))).toBe(false)
  })
})

describe('real SMTP (nodemailer → local sink)', () => {
  const sinks: SmtpSink[] = []
  afterEach(async () => {
    for (const s of sinks.splice(0)) await s.stop()
  })

  it('delivers a newsletter with list headers, html and text parts', async () => {
    const sink = new SmtpSink()
    sinks.push(sink)
    const port = await sink.start()
    const env = makeEnv({ env: { SITE_URL: 'https://site.example.test', SMTP_HOST: '127.0.0.1', SMTP_PORT: String(port), MAIL_FROM: 'TRS Launcher <news@trs-launcher.de>' } })
    expect(env.ctx.mail.available).toBe(true)
    subscribe(env.ctx, { email: 'steve@example.test', lang: 'de', source: 'website' })
    await processOutbox(env.ctx, 10)
    const tok = /token=(c[A-Za-z0-9_-]{43})/.exec(decodeMime(sink.mails[0]!.data))![1]!
    confirmToken(env.ctx, tok)
    const c = createCampaign(env.ctx, owner(), { content: sample })
    sendCampaign(env.ctx, owner(), c.id, {}, (l) => audienceCount(env.ctx, l))
    try {
      await processOutbox(env.ctx, 10)
    } finally {
      env.ctx.mail.close()
    }
    expect(sink.mails).toHaveLength(2)
    const m = sink.mails[1]!
    expect(m.from).toBe('news@trs-launcher.de')
    expect(m.to).toEqual(['steve@example.test'])
    expect(m.data).toMatch(/^List-Unsubscribe-Post: List-Unsubscribe=One-Click/m)
    expect(m.data).toMatch(/^List-Unsubscribe:\s+<https:\/\/site\.example\.test\/newsletter\/unsubscribe\?token=/m)
    expect(m.data).toContain('multipart/alternative')
    expect(m.data).toContain('text/plain')
    expect(m.data).toContain('text/html')
    // Bilder der eigenen Website hängen eingebettet an (sonst laden viele Mail-Programme sie erst nach einem Klick).
    expect(m.data).toContain('multipart/related')
    expect(m.data).toMatch(/^Content-ID: <img1\.[0-9a-f]{12}@site\.example\.test>/m)
    expect(decodeMime(m.data)).toContain('src="cid:img1.')
  })

  it('a permanent SMTP error marks the mail failed without retry', async () => {
    const sink = new SmtpSink()
    sinks.push(sink)
    const port = await sink.start()
    const svc = new MailService(loadConfig({ DATA_DIR: '/tmp/x', SECRET_KEY: 'k'.repeat(40), SMTP_HOST: '127.0.0.1', SMTP_PORT: String(port) }).mail)
    sink.failNext = 550
    await expect(svc.send({ to: 'x@example.test', subject: 's', html: '<b>h</b>', text: 't' })).rejects.toMatchObject({ permanent: true })
    sink.failNext = 451
    await expect(svc.send({ to: 'x@example.test', subject: 's', html: '<b>h</b>', text: 't' })).rejects.toMatchObject({ permanent: false })
    svc.close()
  })
})

describe('migration 38', () => {
  it('is idempotent and grants the newsletter permissions to owner and admin', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    migrateMail(db)
    const perms = (id: string) => JSON.parse((db.prepare('SELECT permissions FROM team_roles WHERE id = ?').get(id) as { permissions: string }).permissions) as string[]
    expect(perms('admin')).toEqual(expect.arrayContaining(['newsletter.manage', 'newsletter.send']))
    expect(perms('moderator')).not.toContain('newsletter.manage')
    expect(perms('admin').filter((p) => p === 'newsletter.send')).toHaveLength(1)
    db.close()
  })
})
