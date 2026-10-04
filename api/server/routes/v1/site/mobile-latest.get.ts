import { defineEventHandler, setResponseHeader } from 'h3'
import { latestMobile } from '../../../lib/mobile'

/** Website: neueste Handy-Apps (Android-APK, iOS-IPA + AltStore-Quelle) aus dem signierten Kanal `mobile`. */
export default defineEventHandler(async (event) => {
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  return { mobile: await latestMobile().catch(() => null) }
})
