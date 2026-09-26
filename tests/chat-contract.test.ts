import { describe, expect, it } from 'vitest'
import { z } from 'zod'
import {
  conversationPageSchema,
  inviteStatusSchema,
  liveEventSchema,
  messagePageSchema,
  myReportSchema,
  unreadSummarySchema,
} from '../app/utils/chat'
import {
  adminModerationEnvelopeSchema,
  adminReportEnvelopeSchema,
  adminReportListSchema,
  auditPageSchema,
  filterWordsSchema,
} from '../app/utils/moderation'
import { trsAdminStatsSchema } from '../app/utils/trs'
// Echte Daten: so, wie der Kern sie nach einem Durchlauf gegen die lokale
// TRS API ans Webview gibt (Rust-Test `live_local_api`, TRS_LIVE_SAMPLES).
import samples from './fixtures/chat-live.json'

function ok<S extends z.ZodType>(schema: S, value: unknown) {
  const r = schema.safeParse(value)
  if (!r.success) throw new Error(JSON.stringify(r.error.issues.slice(0, 3)))
  return r.data
}

describe('Vertrag: echte Antworten passen zu den Schemas der Oberfläche', () => {
  it('Unterhaltungen, Nachrichten, Ungelesen, Einladungs-Status', () => {
    const page = ok(conversationPageSchema, samples.conversationPage)
    expect(page.conversations.length).toBeGreaterThan(0)
    const messages = ok(messagePageSchema, samples.messagePage)
    expect(messages.messages.some((m) => m.attachments.length > 0)).toBe(true)
    ok(unreadSummarySchema, samples.unread)
    ok(inviteStatusSchema, samples.inviteStatus)
  })

  it('jedes Ereignis aus dem Echtzeit-Kanal', () => {
    for (const event of samples.events) ok(liveEventSchema, event)
    const types = new Set(samples.events.map((e) => (e as { type: string }).type))
    for (const t of ['hello', 'chat_message', 'chat_typing', 'chat_state', 'chat_reactions', 'chat_conversation', 'report_update']) {
      expect(types.has(t), t).toBe(true)
    }
  })

  it('Meldungen und Moderation (Admin)', () => {
    for (const r of samples.myReports) ok(myReportSchema, r)
    const list = ok(adminReportListSchema, samples.adminReports)
    expect(list.reports.length).toBeGreaterThan(0)
    const detail = ok(adminReportEnvelopeSchema, samples.adminReport)
    expect(detail.report.evidence?.messages.length).toBeGreaterThan(0)
    expect(JSON.stringify(samples.adminReport)).not.toContain('/v1/')
    ok(adminModerationEnvelopeSchema, samples.adminModeration)
    ok(filterWordsSchema, samples.wordFilter)
    ok(auditPageSchema, samples.audit)
    const stats = ok(trsAdminStatsSchema, samples.stats)
    expect(stats.chat?.messages).toBeGreaterThan(0)
  })
})
