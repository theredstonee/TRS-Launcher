import { afterAll, describe, expect, it } from 'vitest'
import {
  overlayDuration,
  overlayInstance,
  overlayLine,
  overlayLineText,
  overlayNoteFor,
  overlayNoteText,
  overlayNoteTone,
  type OverlayState,
} from '../app/utils/clipOverlay'
import { setLocale } from '../app/utils/i18n'
import type { ClipEvent } from '../app/types'

const base: OverlayState = {
  instanceId: 'survival',
  buffer: true,
  recording: false,
  clipSeconds: 30,
  overlay: true,
  bufferHud: true,
}

function stateEvent(patch: Partial<OverlayState>): ClipEvent {
  return { type: 'state', recordingMs: 0, reason: null, encoder: null, ...base, ...patch }
}

describe('Clip-Overlay', () => {
  afterAll(() => setLocale('en'))

  it('zeigt REC immer, den Puffer nur mit Merkmal und Wunsch', () => {
    expect(overlayLine(undefined)).toBeNull()
    expect(overlayLine({ ...base, recording: true })).toEqual({ kind: 'rec' })
    // Ältere Mod (zeichnet selbst): REC trotzdem im Overlay, Puffer nicht.
    expect(overlayLine({ ...base, overlay: false, recording: true })).toEqual({ kind: 'rec' })
    expect(overlayLine({ ...base, overlay: false })).toBeNull()
    expect(overlayLine(base)).toEqual({ kind: 'buffer', seconds: 30 })
    expect(overlayLine({ ...base, bufferHud: false })).toBeNull()
    expect(overlayLine({ ...base, buffer: false })).toBeNull()
  })

  it('wählt ein Spiel: Aufnahme vor Puffer vor Meldung', () => {
    const now = 1_000_000
    const a = { ...base, instanceId: 'a' }
    const b = { ...base, instanceId: 'b', recording: true }
    const c = { ...base, instanceId: 'c', buffer: false }
    expect(overlayInstance([a, b], {}, now)).toBe('b')
    expect(overlayInstance([c, a], {}, now)).toBe('a')
    expect(overlayInstance([c], {}, now)).toBeNull()
    expect(overlayInstance([c], { c: now + 1000 }, now)).toBe('c')
    expect(overlayInstance([c], { c: now - 1 }, now), 'abgelaufen').toBeNull()
    expect(overlayInstance([c], { weg: now + 1000 }, now), 'Spiel schon beendet').toBeNull()
    // Ältere Mod ohne Merkmal und ohne Aufnahme: Overlay bleibt weg.
    expect(overlayInstance([{ ...a, overlay: false }], {}, now)).toBeNull()
  })

  it('macht Meldungen nur für Spiele mit Merkmal', () => {
    const saved: ClipEvent = { type: 'saved', instanceId: 'survival', fileName: 'x.mp4', kind: 'clip', seconds: 29.6, removed: 0 }
    expect(overlayNoteFor(saved, base)).toEqual({ instanceId: 'survival', kind: 'clipSaved', seconds: 30 })
    expect(overlayNoteFor({ ...saved, kind: 'recording' }, base)?.kind).toBe('recordingSaved')
    expect(overlayNoteFor(saved, { ...base, overlay: false }), 'ältere Mod zeigt es selbst').toBeNull()
    expect(overlayNoteFor(saved, undefined)).toBeNull()

    const failed: ClipEvent = { type: 'failed', instanceId: 'survival', code: 'busy' }
    expect(overlayNoteFor(failed, base)).toEqual({ instanceId: 'survival', kind: 'failed', code: 'busy' })
    expect(overlayNoteFor({ ...failed, code: 'disabled' }, base), 'Angebot bleibt im Spiel').toBeNull()

    // „Aufnahme läuft“ nur beim Übergang, nicht beim ersten Stand.
    expect(overlayNoteFor(stateEvent({ recording: true }), base)).toEqual({ instanceId: 'survival', kind: 'recordingStarted' })
    expect(overlayNoteFor(stateEvent({ recording: true }), { ...base, recording: true })).toBeNull()
    expect(overlayNoteFor(stateEvent({ recording: true }), undefined)).toBeNull()
    expect(overlayNoteFor(stateEvent({ recording: true, overlay: false }), base)).toBeNull()
    expect(overlayNoteFor({ type: 'ffmpeg', state: 'ready' }, undefined)).toBeNull()
  })

  it('formuliert Texte und Farben', () => {
    expect(overlayDuration(30)).toBe('30 s')
    expect(overlayDuration(65)).toBe('1:05')
    expect(overlayLineText({ kind: 'buffer', seconds: 30 }, 0)).toBe('Buffer 30 s')
    expect(overlayLineText({ kind: 'rec' }, 7_900)).toBe('REC 0:07')
    expect(overlayLineText({ kind: 'rec' }, 3_723_400)).toBe('REC 1:02:03')
    expect(overlayNoteText({ instanceId: 'a', kind: 'clipSaved', seconds: 30 })).toBe('Clip saved (30 s)')
    expect(overlayNoteText({ instanceId: 'a', kind: 'recordingSaved', seconds: 125 })).toBe('Recording saved (2:05)')
    expect(overlayNoteText({ instanceId: 'a', kind: 'recordingStarted' })).toBe('Recording started')
    expect(overlayNoteText({ instanceId: 'a', kind: 'failed', code: 'busy' })).toBe('The last clip is still being saved.')
    expect(overlayNoteText({ instanceId: 'a', kind: 'failed', code: '<b>' })).toBe('The clip could not be saved.')
    expect(overlayNoteTone({ instanceId: 'a', kind: 'clipSaved', seconds: 1 })).toBe('ok')
    expect(overlayNoteTone({ instanceId: 'a', kind: 'failed', code: 'busy' })).toBe('warn')
    expect(overlayNoteTone({ instanceId: 'a', kind: 'failed', code: 'encoder' })).toBe('error')
  })

  it('übersetzt die Overlay-Texte', async () => {
    await setLocale('de')
    expect(overlayLineText({ kind: 'buffer', seconds: 30 }, 0)).toBe('Puffer 30 s')
    expect(overlayNoteText({ instanceId: 'a', kind: 'clipSaved', seconds: 30 })).toBe('Clip gespeichert (30 s)')
    expect(overlayNoteText({ instanceId: 'a', kind: 'recordingStarted' })).toBe('Aufnahme läuft')
  })
})
