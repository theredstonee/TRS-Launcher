import { defineEventHandler } from 'h3'
import { servePitchPreview } from '../../../../lib/pitch-http'

/** Dateien der Team-Vorschau (§38.3). */
export default defineEventHandler((event) => servePitchPreview(event))
