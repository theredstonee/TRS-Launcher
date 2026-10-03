#!/usr/bin/env node
/**
 * Erzeugt ein VAPID-Schlüsselpaar für Push (API.md §33) – nur node:crypto, nichts wird gespeichert.
 * Ausgabe in die `.env` des Servers übernehmen (NIE ins Repository):
 *
 *   node scripts/vapid-keys.mjs
 *
 * Gleiches Format wie `npx web-push generate-vapid-keys` (base64url, öffentlich 65 Byte unkomprimiert, privat 32 Byte).
 * Den Schlüssel NICHT tauschen, solange Geräte angemeldet sind: Verteiler mit VAPID-Bindung lehnen sonst ab (403),
 * die Apps müssen sich dann neu anmelden.
 */
import { createECDH } from 'node:crypto'

const ecdh = createECDH('prime256v1')
ecdh.generateKeys()
console.log(`VAPID_PUBLIC_KEY=${ecdh.getPublicKey().toString('base64url')}`)
const priv = ecdh.getPrivateKey()
// Führende Nullen auffüllen (immer 32 Byte).
console.log(`VAPID_PRIVATE_KEY=${Buffer.concat([Buffer.alloc(32 - priv.length), priv]).toString('base64url')}`)
console.log('VAPID_SUBJECT=mailto:you@example.com')
