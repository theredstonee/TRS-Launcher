import { bindMotionLibrary, vStagger } from '~/utils/motionFx'

// Motion for Vue: Komponenten (Layout, Präsenz) folgen der Einstellung „Animationen“,
// dazu die Direktive `v-stagger` für gestaffelte Auftritte (siehe utils/motionFx.ts).
export default defineNuxtPlugin((nuxtApp) => {
  bindMotionLibrary()
  nuxtApp.vueApp.directive('stagger', vStagger)
})

declare module 'vue' {
  interface GlobalDirectives {
    vStagger: typeof vStagger
  }
}
