import { randomInt } from 'node:crypto'
import type { AppContext } from './context'

/**
 * Halloween-Schreck-Erweiterung, Part C – der Owner (Theredstonee) kann einem Spieler einen Schreck schicken. Die
 * Arten müssen zu den allgemeinen Schreck-Typen im TRS Client passen (der Mod ordnet sie zu). Der Server prüft nur,
 * ob das Ziel gerade einen `/v1/events/me`-Stream offen hat; die endgültigen Sicherheits-/Opt-out-Prüfungen (Herzen,
 * Kampf, „Jumpscares aus“, Cooldown) macht der Client als letzte Instanz.
 */
export const SCARE_KINDS = ['face', 'whisper', 'eye', 'flash'] as const
export type ScareKind = (typeof SCARE_KINDS)[number]

/** Eine Schreck-Art würfeln (krypto-sicher, in Tests ersetzbar). */
export function pickScareKind(roll: (n: number) => number = randomInt): ScareKind {
  return SCARE_KINDS[roll(SCARE_KINDS.length)]!
}

/** Schickt dem Ziel einen Schreck auf `GET /v1/events/me`. `false` = das Ziel ist offline (kein offener Stream). */
export function sendScare(ctx: AppContext, targetUuid: string, kind: ScareKind): boolean {
  if (!ctx.events.isListening(targetUuid)) return false
  ctx.events.publish(targetUuid, { type: 'jumpscare', kind }, { meOnly: true })
  return true
}
