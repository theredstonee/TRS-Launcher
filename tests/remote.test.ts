import { describe, expect, it } from 'vitest'
import {
  REMOTE_MAX_INSTANCES,
  buildStatus,
  detectRemoteRole,
  iconHash,
  newIdempotencyKey,
  normalizePairCode,
  remoteEventSchemas,
  remotePairingsSchema,
  resultErrorKey,
} from '../app/utils/remote'
import { liveEventSchema } from '../app/utils/chat'

const PC = 'AAAAAAAAAAAAAAAAAAAAAA'
const PHONE = 'CCCCCCCCCCCCCCCCCCCCCC'

describe('PC-Fernbedienung (API §33)', () => {
  it('Rolle aus dem User-Agent: Android/iPhone/iPad steuern, PCs werden gesteuert', () => {
    expect(detectRemoteRole('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Edg/140.0', 0)).toBe('desktop')
    expect(detectRemoteRole('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/605.1.15', 0)).toBe('desktop')
    expect(detectRemoteRole('Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36', 5)).toBe('phone')
    expect(detectRemoteRole('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)', 5)).toBe('phone')
    // iPadOS meldet sich wie ein Mac – aber mit Touch.
    expect(detectRemoteRole('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', 5)).toBe('phone')
    expect(detectRemoteRole('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', 0)).toBe('desktop')
  })

  it('Kopplungs-Codes wie Kern und Server, auch als QR-Link', () => {
    expect(normalizePairCode('k7q 2mx')).toBe('K7Q-2MX')
    expect(normalizePairCode('trs-launcher://remote-pair/K7Q2MX')).toBe('K7Q-2MX')
    expect(normalizePairCode('TRS-LAUNCHER://remote-pair/k7q2mx/')).toBe('K7Q-2MX')
    for (const bad of ['K0Q2MX', 'K7Q2M', 'https://evil/remote-pair/K7Q2MX', 'trs-launcher://pack/K7Q2MX', '', 'x'.repeat(100)]) {
      expect(normalizePairCode(bad), bad).toBeNull()
    }
  })

  it('Idempotenz-Schlüssel sind zufällig und passen zum Server-Muster', () => {
    const a = newIdempotencyKey()
    expect(a).toMatch(/^[A-Za-z0-9_-]{8,64}$/)
    expect(newIdempotencyKey()).not.toBe(a)
  })

  it('Status: gültige Instanzen, „läuft“, Aufgaben mit Fortschritt 0–1', () => {
    const instances = [
      { id: 'fabric-1-21', name: 'Fabric 1.21', gameVersion: '1.21.11', loader: { kind: 'fabric', version: '0.17.2' }, icon: 'icon.png' },
      { id: '../evil', name: 'x', gameVersion: '1', loader: 'vanilla' },
      { id: 'fabric-1-21', name: 'Doppelt', gameVersion: '1', loader: 'vanilla' },
      { id: 'vanilla', name: 'N'.repeat(100), gameVersion: '1.8.9', loader: 'vanilla', icon: null },
    ]
    const tasks = [
      { title: 'Pack', percent: 42, instanceId: 'fabric-1-21', status: 'running' },
      { title: 'Fertig', percent: 100, instanceId: null, status: 'done' },
      { title: 'Java', percent: null, instanceId: 'NOPE', status: 'running' },
      { title: 'Zu viel', percent: 250, instanceId: null, status: 'running' },
    ]
    const s = buildStatus(instances, (id) => id === 'vanilla', tasks)
    expect(s.instances.map((i) => i.id)).toEqual(['fabric-1-21', 'vanilla'])
    expect(s.instances[0]).toMatchObject({ version: '1.21.11', loader: 'fabric', running: false })
    expect(s.instances[0]!.iconHash).toMatch(/^[0-9a-f]{16}$/)
    expect(s.instances[1]).toMatchObject({ running: true, iconHash: null })
    expect([...s.instances[1]!.name]).toHaveLength(64)
    expect(s.tasks).toEqual([
      { title: 'Pack', progress: 0.42, instanceId: 'fabric-1-21' },
      { title: 'Java', progress: null, instanceId: null },
      { title: 'Zu viel', progress: 1, instanceId: null },
    ])
    const many = Array.from({ length: 300 }, (_, i) => ({ id: `i${i}`, name: `I${i}`, gameVersion: '1', loader: 'vanilla' }))
    expect(buildStatus(many, () => false, []).instances).toHaveLength(REMOTE_MAX_INSTANCES)
  })

  it('Symbol-Hash ist stabil und verrät den Pfad nicht', () => {
    expect(iconHash('C:/Users/x/icon.png')).toBe(iconHash('C:/Users/x/icon.png'))
    expect(iconHash('a')).not.toBe(iconHash('b'))
    expect(iconHash(null)).toBeNull()
    expect(iconHash('C:/Users/x/icon.png')).not.toContain('Users')
  })

  it('Ergebnis-Codes vom PC → bekannte Texte, Unbekanntes → „failed“', () => {
    expect(resultErrorKey('needs_pc')).toBe('needs_pc')
    expect(resultErrorKey('rm_rf')).toBe('failed')
    expect(resultErrorKey(null)).toBe('failed')
  })

  it('Echtzeit-Ereignisse und Listen werden streng geprüft', () => {
    expect(remoteEventSchemas).toHaveLength(4)
    const status = {
      online: true,
      allow: { launch: true, install: false },
      instances: [{ id: 'fabric-1-21', name: 'Fabric', version: '1.21.11', loader: 'fabric', iconHash: null, running: true }],
      tasks: [{ title: 'Pack', progress: 0.5, instanceId: null }],
    }
    const ok = liveEventSchema.safeParse({ type: 'remote_status', desktopId: PC, online: true, status, at: null })
    expect(ok.success).toBe(true)
    const badId = { ...status, instances: [{ ...status.instances[0], id: '../x' }] }
    expect(liveEventSchema.safeParse({ type: 'remote_status', desktopId: PC, online: true, status: badId, at: null }).success).toBe(false)
    const command = { id: 'BBBBBBBBBBBBBBBBBBBBBB', phoneId: PHONE, phoneName: 'Pixel', commandType: 'launch_instance', instanceId: 'fabric-1-21', code: null }
    expect(liveEventSchema.safeParse({ type: 'remote_command', command }).success).toBe(true)
    expect(liveEventSchema.safeParse({ type: 'remote_command', command: { ...command, commandType: 'format_disk' } }).success).toBe(false)
    expect(
      liveEventSchema.safeParse({
        type: 'remote_command_update',
        commandId: command.id,
        desktopId: PC,
        phoneId: PHONE,
        commandType: 'ping',
        state: 'done',
        error: null,
      }).success,
    ).toBe(true)
    const peer = { id: PC, kind: 'desktop', name: 'Gaming-PC', pairedAt: null, lastSeenAt: null, online: true, status, statusAt: null }
    expect(remotePairingsSchema.safeParse({ deviceId: PHONE, peers: [peer] }).success).toBe(true)
    expect(liveEventSchema.safeParse({ type: 'remote_pairing', action: 'added', desktopId: PC, phoneId: PHONE, desktop: peer, phone: null }).success).toBe(true)
  })
})
