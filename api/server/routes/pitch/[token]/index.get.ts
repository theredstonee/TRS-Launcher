import { defineEventHandler } from 'h3'
import { servePitchLink } from '../../../lib/pitch-http'

/** Pitch-Präsentation über einen geheimen Link (§38.1): index.html, zählt den Aufruf. */
export default defineEventHandler((event) => servePitchLink(event))
