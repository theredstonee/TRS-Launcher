import { defineEventHandler } from 'h3'
import { handleRedeem } from '../../../lib/redeem-route'

/** Alter Pfad, gleiches Verhalten wie `POST /v1/redeem` (löst auch Kosmetik-Codes ein). */
export default defineEventHandler(handleRedeem)
