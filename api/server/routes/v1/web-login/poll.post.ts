import { defineEventHandler, setCookie } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { clientIp, limit, readJson } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { WEB_SESSION_COOKIE, WEB_SESSION_TTL_MS, pollWebLogin } from '../../../lib/weblogin'

const Body = z.object({ pollSecret: z.string().min(1).max(64) }).strict()

/**
 * Website: Status des Codes abfragen. Bei Bestätigung im Launcher kommt die Sitzung als
 * httpOnly-Cookie; die Antwort enthält nur Name, CSRF-Token und Ablaufzeit.
 */
export default defineEventHandler(async (event) => {
  limit(`weblogin-poll:${clientIp(event)}`, RULES.webLoginPollIp)
  const { pollSecret } = await readJson(event, Body)
  const result = pollWebLogin(useCtx(), pollSecret)
  if (result.status !== 'approved') return { status: result.status }
  setCookie(event, WEB_SESSION_COOKIE, result.token, {
    httpOnly: true,
    secure: true,
    sameSite: 'strict',
    path: '/',
    maxAge: Math.floor(WEB_SESSION_TTL_MS / 1000),
  })
  return { status: 'approved', name: result.name, csrf: result.csrf, expiresAt: result.expiresAt }
})
