import { getHeader, type H3Event } from 'h3'
import type { AuthedUser } from './auth'
import { useCtx } from './context'
import { requireUser } from './http'
import { DEVICE_HEADER, authenticateDevice, type DeviceKind } from './remote'

/**
 * Fernbedienung (§33): Bearer-Token UND Geräte-Kopfzeile `X-TRS-Device: <id>.<geheimnis>` – das Gerät muss zum Konto
 * des Tokens gehören (und ggf. die passende Art haben).
 */
export function requireDevice(
  event: H3Event,
  kind: 'read' | 'write',
  deviceKind?: DeviceKind,
): { auth: AuthedUser, device: ReturnType<typeof authenticateDevice> } {
  const auth = requireUser(event, kind)
  const device = authenticateDevice(useCtx(), auth.uuid, getHeader(event, DEVICE_HEADER), deviceKind)
  return { auth, device }
}
