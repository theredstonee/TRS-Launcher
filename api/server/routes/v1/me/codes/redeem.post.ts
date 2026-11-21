import { defineEventHandler } from 'h3'
import { handleUnifiedRedeem } from '../../../../lib/redeem-route'

/** Ein Feld für alle Codes (§44.2): Creator → Partner → Umhang/Kosmetik; eine Brute-Force-Sperre. */
export default defineEventHandler((event) => handleUnifiedRedeem(event))
