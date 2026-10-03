import { describe, expect, it } from 'vitest'
import { defaultCapabilities, detectOs } from '../app/utils/system'

describe('Betriebssystem', () => {
  it('erkennt das System am User-Agent des Webviews', () => {
    // WebView2 (Windows)
    expect(detectOs('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0')).toBe('windows')
    // WebKitGTK (Linux, auch unter Wayland)
    expect(detectOs('Mozilla/5.0 (X11; Ubuntu; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko)')).toBe('linux')
    expect(detectOs('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15')).toBe('macos')
    expect(detectOs('')).toBe('windows')
    // Android-WebView (Linux im User-Agent) und iOS (iPadOS gibt sich mit Touch als Mac aus)
    expect(detectOs('Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0 Mobile Safari/537.36')).toBe('android')
    expect(detectOs('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148')).toBe('ios')
    expect(detectOs('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15', 5)).toBe('ios')
    expect(detectOs('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15', 0)).toBe('macos')
  })

  it('liefert passende Standard-Fähigkeiten', () => {
    expect(defaultCapabilities('windows')).toMatchObject({ firewall: true, clips: true, updates: 'auto' })
    expect(defaultCapabilities('linux')).toMatchObject({ firewall: false, clips: false, updates: 'package' })
    expect(defaultCapabilities('windows')).toMatchObject({ gameLaunch: true, java: true, windowControls: true, gameEngine: false })
    for (const os of ['android', 'ios'] as const) {
      expect(defaultCapabilities(os)).toMatchObject({
        platform: os,
        firewall: false,
        clips: false,
        trash: false,
        updates: 'mobile',
        gameLaunch: false,
        java: false,
        windowControls: false,
        gameEngine: false,
      })
    }
  })
})
