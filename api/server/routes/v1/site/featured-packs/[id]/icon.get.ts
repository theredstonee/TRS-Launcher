import { defineEventHandler } from 'h3'
import { serveFeaturedImage } from '../../../../../lib/featured-http'

/** Symbol eines empfohlenen Modpacks (§37.1) – gespeicherte Kopie, nie eine Weiterleitung zur Plattform. */
export default defineEventHandler((event) => serveFeaturedImage(event, 'icon'))
