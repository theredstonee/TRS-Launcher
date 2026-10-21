import { defineEventHandler } from 'h3'
import { servePitchLink } from '../../../lib/pitch-http'

/** Dateien der Pitch-Präsentation (§38.1): `assets/…` u. a., nur mit gültigem Link. */
export default defineEventHandler((event) => servePitchLink(event))
