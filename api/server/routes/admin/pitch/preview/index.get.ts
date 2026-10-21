import { defineEventHandler } from 'h3'
import { servePitchPreview } from '../../../../lib/pitch-http'

/** Team-Vorschau der Pitch-Präsentation (§38.3): Website-Sitzung mit `pitch.manage`, sonst 404. */
export default defineEventHandler((event) => servePitchPreview(event))
