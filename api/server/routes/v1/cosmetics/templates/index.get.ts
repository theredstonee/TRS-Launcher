import { defineEventHandler, getHeader, setResponseHeaders, setResponseStatus } from 'h3'
import { useCtx } from '../../../../lib/context'

/** Alle Vorlagen (öffentlich, mit ETag) – der Mod braucht sie, um fremde Kosmetik zu rendern. */
export default defineEventHandler((event) => {
  const t = useCtx().templates
  setResponseHeaders(event, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'public, max-age=300',
    ETag: t.etag,
  })
  if (getHeader(event, 'if-none-match') === t.etag) {
    setResponseStatus(event, 304)
    return ''
  }
  return t.json
})
