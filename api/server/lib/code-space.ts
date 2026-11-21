import type { AppContext } from './context'
import { one } from './db'

/**
 * Gemeinsamer Namensraum der öffentlichen Codes (§35.2 Creator, §44 Partner): ein fester Partner-Code darf kein
 * Creator-Code sein und umgekehrt; Partner-Einmal-Codes haben eine eigene Form, die Creator-Codes nicht nutzen dürfen.
 */

/** Alphabet der Einmal-Codes (ohne 0, 1, I, O). */
export const ONETIME_ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'
/** `<PREFIX>-XXXX-XXXX` */
export const PARTNER_ONETIME = /^[A-Z]{2,4}-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$/
/** Fester Partner-Code; 20 Zeichen ohne Bindestrich bleiben den Umhang-Codes (§5.9). */
export const PARTNER_FIXED = /^[A-Z0-9]{4,19}$/

export function isCreatorCodeTaken(ctx: AppContext, code: string): boolean {
  return one(ctx.db, 'SELECT 1 AS x FROM creator_codes WHERE code = ?', code) !== undefined
}

/** Fester Partner-Code (auch eines pausierten Partners) mit genau diesem Text? */
export function isPartnerFixedTaken(ctx: AppContext, code: string): boolean {
  return one(ctx.db, "SELECT 1 AS x FROM partner_codes WHERE code = ? AND kind = 'fixed'", code) !== undefined
}
