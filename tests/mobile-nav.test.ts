import { describe, expect, it, vi } from 'vitest'
import { longPressCancelled, pullDistance, pullTriggers, sheetSwipeCloses } from '../app/utils/gestures'
import { activeMobileTab, mobileMoreItems, mobileTabs } from '../app/utils/mobileNav'
import { backAction, closeTopOverlay, overlayCount, pushOverlay, removeOverlay } from '../app/utils/overlays'

describe('Handy-Navigation', () => {
  it('hat vier Bereiche unten plus „Mehr“', () => {
    expect(mobileTabs.map((t) => t.to)).toEqual(['/', '/instances', '/browse', '/social'])
  })

  it('markiert den passenden Tab, auch auf Unterseiten', () => {
    expect(activeMobileTab('/')).toBe('/')
    expect(activeMobileTab('/instances')).toBe('/instances')
    expect(activeMobileTab('/instances/redstone-labor')).toBe('/instances')
    expect(activeMobileTab('/project/abc')).toBe('/browse')
    expect(activeMobileTab('/social')).toBe('/social')
    expect(activeMobileTab('/friends')).toBe('/social')
    expect(activeMobileTab('/skins')).toBe('more')
    expect(activeMobileTab('/settings')).toBe('more')
    // Kein Präfix-Treffer über Pfadgrenzen hinweg.
    expect(activeMobileTab('/browsex')).toBe('more')
  })

  it('zeigt in „Mehr“ nur, was das Gerät kann – und nie den Team-Bereich', () => {
    const phone = mobileMoreItems({ clips: false, gameLaunch: false })
    expect(phone.some((i) => i.to === '/clips')).toBe(false)
    expect(phone.some((i) => i.to?.startsWith('/admin'))).toBe(false)
    expect(phone.some((i) => i.to === '/skins')).toBe(true)
    expect(phone.some((i) => i.action === 'settings')).toBe(true)
    expect(mobileMoreItems({ clips: true, gameLaunch: true }).some((i) => i.to === '/clips')).toBe(true)
  })
})

describe('Zurück-Taste (Android)', () => {
  it('schließt zuerst Dialoge, dann zurück, im Bereich zur Startseite, dort beenden', () => {
    expect(backAction({ overlays: 1, path: '/', canGoBack: false })).toBe('close')
    expect(backAction({ overlays: 0, path: '/instances/x', canGoBack: true })).toBe('back')
    expect(backAction({ overlays: 0, path: '/presets', canGoBack: true })).toBe('back')
    expect(backAction({ overlays: 0, path: '/presets', canGoBack: false })).toBe('home')
    expect(backAction({ overlays: 0, path: '/browse', canGoBack: true })).toBe('home')
    expect(backAction({ overlays: 0, path: '/', canGoBack: true })).toBe('exit')
  })

  it('schließt immer die oberste Ebene', () => {
    const first = vi.fn()
    const second = vi.fn()
    const a = pushOverlay(first)
    pushOverlay(second)
    expect(overlayCount()).toBe(2)
    expect(closeTopOverlay()).toBe(true)
    expect(second).toHaveBeenCalledOnce()
    expect(first).not.toHaveBeenCalled()
    removeOverlay(a)
    expect(overlayCount()).toBe(0)
    expect(closeTopOverlay()).toBe(false)
  })
})

describe('Gesten', () => {
  it('Sheet schließt nach genug Weg oder schnellem Wisch', () => {
    expect(sheetSwipeCloses(120, 0.1)).toBe(true)
    expect(sheetSwipeCloses(40, 1)).toBe(true)
    expect(sheetSwipeCloses(40, 0.2)).toBe(false)
    expect(sheetSwipeCloses(20, 2)).toBe(false)
  })

  it('Ziehen zum Aktualisieren hat Widerstand und eine Schwelle', () => {
    expect(pullDistance(-30)).toBe(0)
    expect(pullDistance(Number.NaN)).toBe(0)
    expect(pullDistance(100)).toBe(50)
    expect(pullDistance(1000)).toBe(96)
    expect(pullTriggers(pullDistance(100))).toBe(false)
    expect(pullTriggers(pullDistance(140))).toBe(true)
  })

  it('langer Druck bricht ab, wenn der Finger wandert', () => {
    expect(longPressCancelled(3, 4)).toBe(false)
    expect(longPressCancelled(8, 8)).toBe(true)
  })
})
