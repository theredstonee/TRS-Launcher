import { defineEventHandler } from 'h3'
import { audit } from '../../../../../lib/admin'
import { useCtx } from '../../../../../lib/context'
import { noContent, paramWith, requireStaff } from '../../../../../lib/http'
import { removeFilterWord } from '../../../../../lib/safety'
import { wordIdSchema } from '../../../../../lib/schemas'

export default defineEventHandler((event) => {
  const actor = requireStaff(event, 'wordfilter').uuid
  const id = paramWith(event, 'id', wordIdSchema)
  const ctx = useCtx()
  const word = removeFilterWord(ctx, id)
  audit(ctx, actor, 'chat.filter.remove', null, word)
  return noContent(event)
})
