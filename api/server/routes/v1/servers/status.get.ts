import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, queryWith, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { serverStatusQuery } from '../../../lib/schemas'

/**
 * Status eines Minecraft-Servers für Einladungskarten (Icon, Spielerzahl, MOTD). Der TRS-Server
 * pingt (gecacht, SSRF-geschützt) – Empfänger verraten dem fremden Server so nie ihre IP.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  const { address } = queryWith(event, serverStatusQuery)
  limit(`serverStatus:${auth.uuid}`, RULES.serverStatusUser)
  return { status: await useCtx().servers.status(address) }
})
