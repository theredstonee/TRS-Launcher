import { defineEventHandler, getHeader } from 'h3'
import { authenticate } from '../../../lib/auth'
import { useCtx } from '../../../lib/context'
import { playEmote } from '../../../lib/emote-play'
import { readJson } from '../../../lib/http'
import { emoteBody } from '../../../lib/schemas'

/** Emote abspielen → Ereignis an alle TRS-Clients, die diesen Spieler gerade beobachten. Höchstens 1 je 2 s. */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const auth = authenticate(ctx, getHeader(event, 'authorization'))
  const body = await readJson(event, emoteBody)
  return playEmote(ctx, auth.uuid, body.emote)
})
