import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { createMotion, isReducedMotion, motionModes, normalizeMotion, REDUCED_MOTION_QUERY } from '../app/utils/motion'
import { uiSettingsSchema } from '../app/utils/schemas'

/** Abfrage „Bewegung reduzieren“ zum Umschalten. */
function fakeMedia(matches: boolean) {
  const listeners = new Set<(e: { matches: boolean }) => void>()
  return {
    matches,
    addEventListener: (_: string, cb: (e: { matches: boolean }) => void) => listeners.add(cb),
    removeEventListener: (_: string, cb: (e: { matches: boolean }) => void) => listeners.delete(cb),
    change(next: boolean) {
      this.matches = next
      for (const cb of listeners) cb({ matches: next })
    },
    listeners,
  }
}

function fakeRoot() {
  return { dataset: {} as DOMStringMap } as HTMLElement
}

describe('Einstellung „Animationen“', () => {
  it('reduziert nur bei „Reduziert“ oder „Wie System“ mit reduzierter Systembewegung', () => {
    expect(isReducedMotion('full', true)).toBe(false)
    expect(isReducedMotion('full', false)).toBe(false)
    expect(isReducedMotion('system', true)).toBe(true)
    expect(isReducedMotion('system', false)).toBe(false)
    expect(isReducedMotion('reduced', false)).toBe(true)
  })

  it('fällt bei fehlenden oder unbekannten Werten auf „Immer an“ zurück', () => {
    expect(motionModes).toEqual(['full', 'system', 'reduced'])
    expect(normalizeMotion(undefined)).toBe('full')
    expect(normalizeMotion('wobbly')).toBe('full')
    expect(normalizeMotion('system')).toBe('system')
  })

  it('animiert ab Werk auch dann, wenn Windows „Bewegung reduzieren“ meldet', () => {
    // Der eigentliche Fehler: Windows mit ausgeschalteten Animationseffekten
    // meldet `prefers-reduced-motion: reduce` – der Hintergrund stand still.
    const media = fakeMedia(true)
    const root = fakeRoot()
    const motion = createMotion({ media, root })
    expect(motion.systemReduced.value).toBe(true)
    expect(motion.reduced.value).toBe(false)
    expect(root.dataset.motion).toBe('full')
    expect('reducedMotion' in root.dataset).toBe(false)
    // Settings ohne Feld (älterer Kern) → ebenfalls „Immer an“.
    motion.apply(undefined)
    expect(motion.reduced.value).toBe(false)
  })

  it('folgt bei „Wie System“ der Systemeinstellung, auch während der Laufzeit', () => {
    const media = fakeMedia(false)
    const root = fakeRoot()
    const motion = createMotion({ media, root })
    motion.apply('system')
    expect(motion.reduced.value).toBe(false)
    media.change(true)
    expect(motion.reduced.value).toBe(true)
    expect(root.dataset.motion).toBe('system')
    expect(root.dataset.reducedMotion).toBe('')
    media.change(false)
    expect('reducedMotion' in root.dataset).toBe(false)

    motion.apply('reduced')
    expect(motion.reduced.value).toBe(true)
    expect(root.dataset.reducedMotion).toBe('')
    motion.apply('full')
    media.change(true)
    expect(motion.reduced.value).toBe(false)
    expect('reducedMotion' in root.dataset).toBe(false)

    motion.dispose()
    expect(media.listeners.size).toBe(0)
  })

  it('kommt ohne matchMedia und ohne Dokument aus', () => {
    const motion = createMotion()
    motion.apply('system')
    expect(motion.reduced.value).toBe(false)
    motion.apply('reduced')
    expect(motion.reduced.value).toBe(true)
  })

  it('ist Teil der UI-Einstellungen', () => {
    const base = {
      theme: 'dark',
      accent: 'redstone',
      advancedRendering: true,
      animatedBackground: true,
      worldsTab: true,
      screenshotsTab: true,
      historyTab: true,
      sidebarRecent: true,
      sidebarAccount: true,
      hideRightSidebar: false,
      compactLibrary: false,
      showPlayTime: true,
      language: 'de',
    }
    for (const motion of motionModes) expect(uiSettingsSchema.safeParse({ ...base, motion }).success).toBe(true)
    expect(uiSettingsSchema.safeParse({ ...base, motion: 'wobbly' }).success).toBe(false)
  })

  it('niemand fragt „Bewegung reduzieren“ an der Einstellung vorbei ab', () => {
    // Direkte Media-Queries würden die Einstellung ignorieren (Standard „Immer an“).
    const appDir = path.resolve(__dirname, '../app')
    const offenders: string[] = []
    const walk = (dir: string) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name)
        if (entry.isDirectory()) walk(full)
        else if (/\.(vue|ts|css)$/.test(entry.name) && !full.endsWith(path.join('utils', 'motion.ts'))) {
          const text = fs.readFileSync(full, 'utf8')
          if (text.includes('prefers-reduced-motion')) offenders.push(path.relative(appDir, full))
        }
      }
    }
    walk(appDir)
    expect(offenders).toEqual([])
    expect(REDUCED_MOTION_QUERY).toBe('(prefers-reduced-motion: reduce)')
  })
})
