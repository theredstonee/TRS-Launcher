import { defineEventHandler } from 'h3'
import { handleRedeem } from '../../lib/redeem-route'

/** Code einlösen: `{ code }` → `{ kind, cape, cosmetic, alreadyOwned }`. */
export default defineEventHandler(handleRedeem)
