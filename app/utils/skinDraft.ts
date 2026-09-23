import type { SkinChanges, SkinProfile, SkinSyncStatus, SkinVariant } from '~/types'

// Lokaler Entwurf für Skin, Modell und Umhang – wie in der Modrinth App:
// Auswählen ändert nur den Entwurf (sofort in der 3D-Vorschau, ohne Netzwerk),
// erst „Anwenden“ schickt den Unterschied an den Kern. Reine Funktionen,
// getestet in tests/skin-draft.test.ts.

export type DraftSkin = { source: 'current' } | { source: 'library'; id: string } | { source: 'default' }

export interface SkinDraft {
  skin: DraftSkin
  variant: SkinVariant
  /** Umhang-ID oder `null` = keiner. */
  cape: string | null
}

/** Entwurf, der genau dem Konto entspricht. */
export function baseDraft(profile: SkinProfile | null): SkinDraft {
  return {
    skin: { source: 'current' },
    variant: profile?.variant ?? 'classic',
    cape: profile?.capes.find((c) => c.active)?.id ?? null,
  }
}

function sameSkin(a: DraftSkin, b: DraftSkin): boolean {
  if (a.source !== b.source) return false
  return a.source !== 'library' || (b.source === 'library' && a.id === b.id)
}

export function sameDraft(a: SkinDraft | null, b: SkinDraft | null): boolean {
  if (!a || !b) return a === b
  return sameSkin(a.skin, b.skin) && a.variant === b.variant && a.cape === b.cape
}

/**
 * Was sich gegenüber dem Konto ändern soll. `null` = nichts zu tun.
 * Zwischenstände spielen keine Rolle – verglichen wird nur Endzustand gegen Konto.
 */
export function draftChanges(draft: SkinDraft, profile: SkinProfile | null): SkinChanges | null {
  if (!profile) return null
  let skin: SkinChanges['skin'] = null
  if (draft.skin.source === 'library') skin = { kind: 'library', id: draft.skin.id, variant: draft.variant }
  else if (draft.skin.source === 'default') skin = { kind: 'default' }
  else if (draft.variant !== profile.variant) skin = { kind: 'current', variant: draft.variant }

  const activeCape = profile.capes.find((c) => c.active)?.id ?? null
  const cape = draft.cape !== activeCape ? { id: draft.cape } : null

  return skin || cape ? { skin, cape } : null
}

/**
 * Nach dem Anwenden kommt ein neues Profil. Was davon schon übernommen ist,
 * wird im Entwurf zu „getragen“; spätere, noch nicht angewendete Änderungen
 * bleiben erhalten.
 */
export function rebaseDraft(draft: SkinDraft, submitted: SkinDraft | null, profile: SkinProfile | null): SkinDraft {
  const base = baseDraft(profile)
  if (!submitted) return base
  const skinApplied = sameSkin(draft.skin, submitted.skin) && draft.variant === submitted.variant
  const capeApplied = draft.cape === submitted.cape
  return {
    skin: skinApplied ? base.skin : draft.skin,
    variant: skinApplied ? base.variant : draft.variant,
    cape: capeApplied ? base.cape : draft.cape,
  }
}

/** Countdown als „m:ss“. */
export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

/** Läuft im Kern noch etwas (Anfrage oder Warten)? */
export function syncBusy(status: SkinSyncStatus | null): boolean {
  return status?.state === 'applying' || status?.state === 'waiting'
}

/** Eine Zeile Status für die Vorschau – statt Toasts. */
export function syncLabel(status: SkinSyncStatus | null, now: number): string | null {
  if (!status) return null
  const left = status.retryAt != null ? formatCountdown(status.retryAt - now) : null
  switch (status.state) {
    case 'applying':
      return 'Wird angewendet …'
    case 'waiting':
      if (status.reason === 'rateLimited') return `Mojang bremst – wird in ${left ?? 'Kürze'} automatisch angewendet`
      if (status.reason === 'network') return `Mojang nicht erreichbar – neuer Versuch in ${left ?? 'Kürze'}`
      return `Viele Änderungen kurz hintereinander – wird in ${left ?? 'Kürze'} angewendet`
    case 'failed':
      return status.message ?? 'Die Änderung konnte nicht angewendet werden.'
    default:
      return null
  }
}
