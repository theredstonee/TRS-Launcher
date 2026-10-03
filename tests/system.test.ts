import { describe, expect, it } from 'vitest'
import { defaultCapabilities, detectOs, isMobileOs, normalizeCapabilities, wantsMobileUi } from '../app/utils/system'

describe('Betriebssystem', () => {
  it('erkennt das System am User-Agent des Webviews', () => {
    // WebView2 (Windows)
    expect(detectOs('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0')).toBe('windows')
    // WebKitGTK (Linux, auch unter Wayland)
    expect(detectOs('Mozilla/5.0 (X11; Ubuntu; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko)')).toBe('linux')
    expect(detectOs('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15', 0)).toBe('macos')
    expect(detectOs('', 0)).toBe('windows')
  })

  it('erkennt Android vor Linux und iPhone/iPad', () => {
    // Android-WebView meldet auch „Linux“.
    expect(detectOs('Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36')).toBe('android')
    expect(detectOs('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148')).toBe('ios')
    expect(detectOs('Mozilla/5.0 (iPad; CPU OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148')).toBe('ios')
    // iPadOS 13+ gibt sich als Mac aus – nur die Touch-Punkte verraten es.
    expect(detectOs('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)', 5)).toBe('ios')
    expect(isMobileOs('android')).toBe(true)
    expect(isMobileOs('ios')).toBe(true)
    expect(isMobileOs('linux')).toBe(false)
  })

  it('liefert passende Standard-Fähigkeiten', () => {
    expect(defaultCapabilities('windows')).toMatchObject({ firewall: true, clips: true, updates: 'auto', gameLaunch: true, java: true, windowControls: true })
    expect(defaultCapabilities('linux')).toMatchObject({ firewall: false, clips: false, updates: 'package', gameLaunch: true, windowControls: true })
    for (const os of ['android', 'ios'] as const) {
      expect(defaultCapabilities(os)).toEqual({
        platform: os,
        firewall: false,
        trash: false,
        clips: false,
        updates: 'mobile',
        gameLaunch: false,
        java: false,
        windowControls: false,
        pushSupported: false,
      })
    }
  })

  it('ergänzt fehlende Schalter eines älteren Kerns und verwirft falsche Typen', () => {
    // Desktop-Kern ohne die neuen Felder: alles wie bisher, Spielstart an.
    expect(normalizeCapabilities({ platform: 'windows', firewall: true, trash: true, clips: true, updates: 'auto' })).toMatchObject({ gameLaunch: true, java: true, windowControls: true, pushSupported: false })
    // Handy-Kern meldet selbst – seine Werte gelten.
    expect(normalizeCapabilities({ platform: 'android', pushSupported: true }).pushSupported).toBe(true)
    expect(normalizeCapabilities({ platform: 'android', pushSupported: true }).gameLaunch).toBe(false)
    expect(normalizeCapabilities({ platform: 'linux', clips: 'yes' as unknown as boolean }).clips).toBe(false)
    expect(normalizeCapabilities(null).platform).toBeTypeOf('string')
  })

  it('Handy-Oberfläche nur auf dem Handy oder bei schmalem Touch-Bildschirm', () => {
    expect(wantsMobileUi({ os: 'android', coarse: false, width: 1200 })).toBe(true)
    expect(wantsMobileUi({ os: 'ios', coarse: true, width: 1024 })).toBe(true)
    expect(wantsMobileUi({ os: 'windows', coarse: true, width: 390 })).toBe(true)
    // Desktop-Fenster: nie, auch nicht mit Touchscreen in voller Breite oder schmal mit Maus.
    expect(wantsMobileUi({ os: 'windows', coarse: true, width: 1280 })).toBe(false)
    expect(wantsMobileUi({ os: 'linux', coarse: false, width: 600 })).toBe(false)
  })
})
