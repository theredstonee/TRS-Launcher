import { describe, expect, it } from 'vitest'
import type { SkinProfile, SkinSyncStatus } from '../app/types'
import {
  baseDraft,
  draftChanges,
  formatCountdown,
  rebaseDraft,
  sameDraft,
  syncBusy,
  syncLabel,
  type SkinDraft,
} from '../app/utils/skinDraft'

function profile(extra: Partial<SkinProfile> = {}): SkinProfile {
  return {
    name: 'Theredstonee',
    uuid: 'abcdef',
    variant: 'classic',
    skin: 'data:image/png;base64,AAAA',
    capes: [
      { id: 'migrator', name: 'Migrator', active: true, texture: null },
      { id: 'vanilla', name: 'Vanilla', active: false, texture: null },
    ],
    ...extra,
  }
}

function status(extra: Partial<SkinSyncStatus>): SkinSyncStatus {
  return {
    version: 1,
    state: 'idle',
    account: 'abcdef',
    reason: null,
    retryAt: null,
    message: null,
    pendingSkin: false,
    pendingCape: false,
    profile: null,
    ...extra,
  }
}

describe('Entwurf', () => {
  it('startet genau beim Konto – nichts anzuwenden', () => {
    const p = profile()
    const draft = baseDraft(p)
    expect(draft).toEqual({ skin: { source: 'current' }, variant: 'classic', cape: 'migrator' })
    expect(draftChanges(draft, p)).toBeNull()
    expect(draftChanges(draft, null)).toBeNull()
  })

  it('schickt nur den Endzustand, nicht die Zwischenschritte', () => {
    const p = profile()
    let draft = baseDraft(p)
    // Schnell hin und her klicken …
    draft = { ...draft, skin: { source: 'library', id: 'aaaaaaaaaaaa' }, variant: 'slim' }
    draft = { ...draft, cape: null }
    draft = { ...draft, skin: { source: 'library', id: 'bbbbbbbbbbbb' }, variant: 'classic' }
    draft = { ...draft, cape: 'migrator' }
    // … am Ende zählt nur: anderer Skin, gleicher Umhang.
    expect(draftChanges(draft, p)).toEqual({
      skin: { kind: 'library', id: 'bbbbbbbbbbbb', variant: 'classic' },
      cape: null,
    })
  })

  it('zurückgeklickt = nichts zu tun', () => {
    const p = profile()
    const draft: SkinDraft = { skin: { source: 'current' }, variant: 'classic', cape: 'migrator' }
    expect(draftChanges({ ...draft, cape: 'vanilla' }, p)).not.toBeNull()
    expect(draftChanges({ ...draft, cape: 'migrator' }, p)).toBeNull()
  })

  it('erkennt Modellwechsel, Standard-Skin und Umhang abnehmen', () => {
    const p = profile()
    expect(draftChanges({ skin: { source: 'current' }, variant: 'slim', cape: 'migrator' }, p)).toEqual({
      skin: { kind: 'current', variant: 'slim' },
      cape: null,
    })
    expect(draftChanges({ skin: { source: 'default' }, variant: 'classic', cape: null }, p)).toEqual({
      skin: { kind: 'default' },
      cape: { id: null },
    })
  })

  it('vergleicht Entwürfe inhaltlich', () => {
    const a: SkinDraft = { skin: { source: 'library', id: 'x' }, variant: 'slim', cape: null }
    expect(sameDraft(a, { ...a, skin: { source: 'library', id: 'x' } })).toBe(true)
    expect(sameDraft(a, { ...a, skin: { source: 'library', id: 'y' } })).toBe(false)
    expect(sameDraft(a, { ...a, variant: 'classic' })).toBe(false)
    expect(sameDraft(a, null)).toBe(false)
    expect(sameDraft(null, null)).toBe(true)
  })
})

describe('rebaseDraft', () => {
  it('macht Übernommenes zu „getragen“', () => {
    const submitted: SkinDraft = { skin: { source: 'library', id: 'x' }, variant: 'slim', cape: 'vanilla' }
    const after = profile({
      variant: 'slim',
      capes: [
        { id: 'migrator', name: 'Migrator', active: false, texture: null },
        { id: 'vanilla', name: 'Vanilla', active: true, texture: null },
      ],
    })
    const draft = rebaseDraft({ ...submitted }, submitted, after)
    expect(draft).toEqual({ skin: { source: 'current' }, variant: 'slim', cape: 'vanilla' })
    expect(draftChanges(draft, after)).toBeNull()
  })

  it('behält Bearbeitungen, die nach dem Anwenden dazukamen', () => {
    const submitted: SkinDraft = { skin: { source: 'library', id: 'x' }, variant: 'slim', cape: 'migrator' }
    const later: SkinDraft = { ...submitted, cape: null }
    const after = profile({ variant: 'slim' })
    const draft = rebaseDraft(later, submitted, after)
    expect(draft).toEqual({ skin: { source: 'current' }, variant: 'slim', cape: null })
    expect(draftChanges(draft, after)).toEqual({ skin: null, cape: { id: null } })
  })
})

describe('Status', () => {
  it('formatiert den Countdown', () => {
    expect(formatCountdown(42_000)).toBe('0:42')
    expect(formatCountdown(41_001)).toBe('0:42')
    expect(formatCountdown(125_000)).toBe('2:05')
    expect(formatCountdown(-5)).toBe('0:00')
  })

  it('zeigt bei 429 eine Zeile mit Countdown statt Fehlern', () => {
    const now = 1_000_000
    const s = status({ state: 'waiting', reason: 'rateLimited', retryAt: now + 42_000, pendingSkin: true })
    expect(syncBusy(s)).toBe(true)
    expect(syncLabel(s, now)).toBe('Mojang bremst – wird in 0:42 automatisch angewendet')
    expect(syncLabel(s, now + 40_500)).toBe('Mojang bremst – wird in 0:02 automatisch angewendet')
  })

  it('kennt Anwenden, Netzwerk, eigenen Takt und Fehler', () => {
    expect(syncLabel(status({ state: 'applying' }), 0)).toBe('Wird angewendet …')
    expect(syncLabel(status({ state: 'waiting', reason: 'network', retryAt: 30_000 }), 0)).toContain('neuer Versuch in 0:30')
    expect(syncLabel(status({ state: 'waiting', reason: 'pacing', retryAt: 5_000 }), 0)).toContain('0:05')
    expect(syncLabel(status({ state: 'failed', message: 'Abgelehnt' }), 0)).toBe('Abgelehnt')
    expect(syncLabel(status({ state: 'done' }), 0)).toBeNull()
    expect(syncBusy(status({ state: 'done' }))).toBe(false)
    expect(syncBusy(null)).toBe(false)
  })
})
