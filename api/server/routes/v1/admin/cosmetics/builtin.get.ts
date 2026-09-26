import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { all } from '../../../../lib/db'
import { requireStaff } from '../../../../lib/http'

/**
 * Mitgelieferte Kosmetik und Emotes, die man per Code freischalten kann (nicht `free`, nicht ausgemustert) –
 * für die Code-Erstellung im Admin. Enthält auch versteckte Teile (`hidden`), die sonst niemand sieht.
 */
export default defineEventHandler((event) => {
  requireStaff(event)
  const rows = all<{ id: string, name: string, slot: string, unlock: string, hidden: number }>(
    useCtx().db,
    `SELECT id, name, slot, unlock, hidden FROM cosmetics
     WHERE kind = 'builtin' AND retired = 0 AND unlock <> 'free'
     ORDER BY slot = 'emote', sort`,
  )
  return { cosmetics: rows.map((r) => ({ id: r.id, name: r.name, slot: r.slot, unlock: r.unlock, hidden: r.hidden === 1 })) }
})
