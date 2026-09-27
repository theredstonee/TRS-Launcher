import { defineEventHandler, setResponseHeader } from 'h3'
import { z } from 'zod'
import { publishedCircuit, siteCircuitView } from '../../../../lib/circuits'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { clientIp, limit, paramWith } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { storedSkins } from '../../../../lib/skins'

/** Website (§25.3): eine veröffentlichte Schaltung mit Maßen, Materialliste und Versionen. */
export default defineEventHandler((event) => {
  limit(`circuitPublic:${clientIp(event)}`, RULES.circuitPublicIp)
  const id = paramWith(event, 'id', z.string().regex(/^[a-z0-9_]{1,48}$/))
  const row = publishedCircuit(useCtx(), id)
  if (!row) throw notFound('circuit_not_found', 'Circuit not found')
  setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
  const view = siteCircuitView(row)
  // Kopf des Erstellers aus dem gespeicherten Skin (keine Mojang-Abfrage beim Seitenaufruf).
  const author = view.circuit.author
  if (!author) return view
  const skin = storedSkins(useCtx().db, [author.uuid]).get(author.uuid)?.url ?? null
  return { ...view, circuit: { ...view.circuit, author: { ...author, skin } } }
})
