import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { t } from './i18n'

// Wegpunkt-Karten im Chat (API §18.10): der TRS Client verschickt sie, der
// Launcher zeigt sie an und kopiert die Koordinaten. Alles kommt schon
// gesäubert aus dem Kern – das Schema prüft trotzdem jede Grenze noch einmal.

export const WAYPOINT_MAX_XZ = 30_000_000

export const waypointWorldSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('server'), address: z.string().min(1).max(261) }),
  z.object({ type: z.literal('world'), id: z.string().regex(/^[0-9a-f]{16}$/) }),
])

export const chatWaypointSchema = z.object({
  name: z.string().min(1).max(32),
  x: z.number().int().min(-WAYPOINT_MAX_XZ).max(WAYPOINT_MAX_XZ),
  y: z.number().int().min(-2048).max(4096),
  z: z.number().int().min(-WAYPOINT_MAX_XZ).max(WAYPOINT_MAX_XZ),
  dimension: z.string().regex(/^[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}$/),
  world: waypointWorldSchema,
  color: z.number().int().min(0).max(0xffffff).nullable().default(null),
})

export type ChatWaypoint = z.infer<typeof chatWaypointSchema>

/** Zum Kopieren: „x y z“ – so versteht es auch `/tp` im Spiel. */
export function waypointCoords(w: Pick<ChatWaypoint, 'x' | 'y' | 'z'>): string {
  return `${w.x} ${w.y} ${w.z}`
}

/** Zur Anzeige: „X 100 · Y 64 · Z -20“ (ohne Tausendertrenner – wie im Spiel). */
export function waypointCoordsLabel(w: Pick<ChatWaypoint, 'x' | 'y' | 'z'>): string {
  return `X ${w.x} · Y ${w.y} · Z ${w.z}`
}

const KNOWN_DIMENSIONS = {
  'minecraft:overworld': 'overworld',
  'minecraft:the_nether': 'nether',
  'minecraft:the_end': 'end',
} as const

/** Oberwelt/Nether/End in der Sprache, andere Dimensionen als ID. */
export function dimensionLabel(dimension: string): string {
  const known = KNOWN_DIMENSIONS[dimension as keyof typeof KNOWN_DIMENSIONS]
  if (known === 'overworld') return t('social.waypoint.dimensions.overworld')
  if (known === 'nether') return t('social.waypoint.dimensions.nether')
  if (known === 'end') return t('social.waypoint.dimensions.end')
  return dimension
}

/** Wo der Wegpunkt gilt: Serveradresse oder „Einzelspielerwelt“ (nur eine Kennung, kein Name). */
export function waypointPlace(w: Pick<ChatWaypoint, 'world'>): string {
  return w.world.type === 'server' ? w.world.address : t('social.waypoint.singleplayer')
}

/** Farbe als CSS-Wert (`#rrggbb`), Standard = Redstone-Rot. */
export function waypointColor(w: Pick<ChatWaypoint, 'color'>): string {
  const c = w.color ?? 0xe0281e
  return `#${c.toString(16).padStart(6, '0')}`
}
