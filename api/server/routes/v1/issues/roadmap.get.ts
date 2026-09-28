import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { queryWith } from '../../../lib/http'
import { publicIssueRead, roadmapQuery } from '../../../lib/issue-http'
import { mergeFilter, roadmap, roadmapColumn } from '../../../lib/issues'

/**
 * Roadmap (§28.3): sechs Spalten (Offen, Geplant, In Arbeit, In Prüfung, Erledigt, Abgelehnt) mit Zähler und den
 * ersten Karten, gefiltert mit der Suchsyntax. `column` + `offset` = eine Spalte weiterblättern („Mehr laden“).
 */
export default defineEventHandler((event) => {
  const viewer = publicIssueRead(event)
  const q = queryWith(event, roadmapQuery)
  const filter = mergeFilter({ filter: q.filter })
  const ctx = useCtx()
  if (q.column) return { column: roadmapColumn(ctx, q.column, filter, viewer, q.offset, q.per) }
  return roadmap(ctx, filter, viewer, q.per)
})
