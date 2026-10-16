import { defineEventHandler } from 'h3'
import { handleCreatorRedeem } from '../../../../lib/redeem-route'

/** Creator-Code einlösen (§35.2) – Brute-Force-Sperre wie bei `POST /v1/redeem`. */
export default defineEventHandler((event) => handleCreatorRedeem(event))
