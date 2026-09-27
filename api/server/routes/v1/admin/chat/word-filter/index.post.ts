import { defineEventHandler } from 'h3'
import { audit } from '../../../../../lib/admin'
import { useCtx } from '../../../../../lib/context'
import { created, readJson, requireStaff } from '../../../../../lib/http'
import { addFilterWord } from '../../../../../lib/safety'
import { wordFilterBody } from '../../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const actor = requireStaff(event, 'wordfilter').uuid
  const body = await readJson(event, wordFilterBody)
  const ctx = useCtx()
  const entry = addFilterWord(ctx, actor, body.word, body.mode, body.action)
  audit(ctx, actor, 'chat.filter.add', null, `${entry.word} (${entry.mode}, ${entry.action})`)
  return created(event, { word: entry })
})
