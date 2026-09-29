import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { v2Assets, v2Fields } from '../../../../lib/cosmetics'
import { all } from '../../../../lib/db'
import { requireStaff } from '../../../../lib/http'

/**
 * Mitgelieferte Kosmetik und Emotes, die man per Code freischalten kann (nicht `free`, nicht ausgemustert) –
 * für die Code-Erstellung im Admin. Enthält auch versteckte Teile (`hidden`), die sonst niemand sieht.
 * `card` = Vorschaubild (relativ) bei Kosmetik im Format v2, sonst `null`.
 */
export default defineEventHandler((event) => {
  requireStaff(event, ['codes', 'items.grant'])
  const ctx = useCtx()
  const rows = all<{ id: string, name: string, slot: string, unlock: string, hidden: number, format: number }>(
    ctx.db,
    `SELECT id, name, slot, unlock, hidden, format FROM cosmetics
     WHERE kind = 'builtin' AND retired = 0 AND unlock <> 'free'
     ORDER BY slot = 'emote', sort`,
  )
  return {
    cosmetics: rows.map((r) => {
      const v2 = v2Assets(ctx, r)
      return { id: r.id, name: r.name, slot: r.slot, unlock: r.unlock, hidden: r.hidden === 1, card: v2 ? v2Fields(v2, '').card : null }
    }),
  }
})
