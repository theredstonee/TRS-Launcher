import { describe, expect, it } from 'vitest'
import { defaultCapabilities, detectPlatform } from '../app/utils/platform'

describe('Plattform', () => {
  it('erkennt das System am User-Agent des Webviews', () => {
    // WebView2 (Windows)
    expect(detectPlatform('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0')).toBe('windows')
    // WebKitGTK (Linux, auch unter Wayland)
    expect(detectPlatform('Mozilla/5.0 (X11; Ubuntu; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko)')).toBe('linux')
    expect(detectPlatform('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15')).toBe('macos')
    expect(detectPlatform('')).toBe('windows')
  })

  it('liefert passende Standard-Fähigkeiten', () => {
    expect(defaultCapabilities('windows')).toMatchObject({ firewall: true, clips: true, updates: 'auto' })
    expect(defaultCapabilities('linux')).toMatchObject({ firewall: false, clips: false, updates: 'package' })
  })
})
