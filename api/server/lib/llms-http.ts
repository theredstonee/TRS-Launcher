import { defineEventHandler, setResponseHeaders } from 'h3'
import { buildLlmsFullTxt, buildLlmsTxt, type LlmsSource } from './llms'

/** Klartext, eine Stunde zwischenspeichern (Inhalt ändert sich nur mit Build oder neuem Release). */
export const LLMS_HEADERS = {
  'Content-Type': 'text/plain; charset=utf-8',
  'Cache-Control': 'public, max-age=3600, stale-while-revalidate=86400',
  'X-Content-Type-Options': 'nosniff',
} as const

/** Route für /llms.txt (`short`) oder /llms-full.txt (`full`); die Quelle wird je Anfrage gelesen. */
export function llmsHandler(kind: 'short' | 'full', source: () => Promise<LlmsSource>) {
  return defineEventHandler(async (event) => {
    const s = await source()
    setResponseHeaders(event, LLMS_HEADERS)
    return kind === 'full' ? buildLlmsFullTxt(s) : buildLlmsTxt(s)
  })
}
