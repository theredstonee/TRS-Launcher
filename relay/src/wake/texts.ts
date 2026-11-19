import type { HostInfo, Resolved } from './api.ts'

// max. ~270 px in der Serverliste, sonst verschwindet der Rest in Zeile 3
export const FOOTER = '§7Free by §bcraftinghosting.free §8× §ctrs-launcher.de'

export const MSG = {
  starting: '§aServer startet …§r\n§7In etwa einer Minute erneut verbinden.',
  running: '§aServer läuft§r\n§7Bitte erneut verbinden.',
  giveUp: 'Server konnte nicht starten – versuch es gleich noch mal',
  unknown: '§cUnbekannter Server.',
  notAllowed: '§cNur Freunde des Besitzers können diesen Server starten.',
  eula: '§cDer Besitzer muss den Server einmal im TRS Launcher starten.',
  rateLimited: '§cBitte kurz warten.',
  unavailable: '§cServer gerade nicht verfügbar.',
  shutdown: '§eTRS Wake startet neu – bitte gleich erneut verbinden.',
} as const

const clock = new Intl.DateTimeFormat('de-DE', { timeZone: 'Europe/Berlin', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })

/** ISO-Zeit → „HH:MM“ in Berlin, null bei Unsinn. */
export function berlinTime(iso: string | null | undefined): string | null {
  if (!iso) return null
  const t = Date.parse(iso)
  return Number.isFinite(t) ? clock.format(t) : null
}

export function cooldownMessage(until: string | null | undefined): string {
  const hhmm = berlinTime(until)
  return hhmm ? `§cPflichtpause bis ${hhmm}.` : '§cPflichtpause.'
}

/** Fehlercode von `wake` → Disconnect-Text. */
export function wakeErrorMessage(code: string, cooldownUntil?: string): string {
  switch (code) {
    case 'not_allowed': return MSG.notAllowed
    case 'cooldown': return cooldownMessage(cooldownUntil)
    case 'eula_required': return MSG.eula
    case 'rate_limited': return MSG.rateLimited
    case 'unknown_host': return MSG.unknown
    default: return MSG.unavailable
  }
}

/** Zeile 1: eigene MOTD bzw. Servername, dahinter der Zustand. */
function statusLine(info: HostInfo): string {
  const name = `§f${info.motd || info.name} §8– `
  switch (info.state) {
    case 'offline':
    case 'stopping':
      return `${name}§a▶ Click to join, it starts now`
    case 'starting':
      return `${name}§e● Starting …`
    case 'running':
      return `${name}§a● Online`
    case 'cooldown': {
      const hhmm = berlinTime(info.cooldownUntil)
      return hhmm ? `${name}§c● Break until ${hhmm}` : `${name}§c● Break`
    }
    default:
      return `${name}§c● Unavailable`
  }
}

/** Server-Listen-Antwort. `r = null` = Adresse gehört nicht zu uns. */
export function statusJson(protocol: number, r: Resolved | null): unknown {
  let line: string
  let max = 0
  if (r === null || (!r.ok && r.code === 'unknown_host')) line = '§c● §fUnknown server'
  else if (!r.ok) line = '§c● §fUnavailable'
  else {
    line = statusLine(r.info)
    max = r.info.maxPlayers
  }
  return {
    version: { name: 'TRS Wake', protocol },
    players: { max, online: 0 },
    description: { text: `${line}\n${FOOTER}` },
  }
}
