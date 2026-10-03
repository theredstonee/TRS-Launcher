// Betriebssystem als Klasse am <html> (`os-windows`, `os-linux`, `os-android` …) – für die
// wenigen Stellen, an denen WebKitGTK (Linux) anders zeichnet als WebView2.
// Dazu `is-mobile` für die Handy-Oberfläche (Variante `mobile:` in main.css).
export default defineNuxtPlugin(() => {
  const root = document.documentElement
  root.classList.add(`os-${hostOs}`)

  const coarse = window.matchMedia?.('(pointer: coarse)')
  const update = () => {
    mobileUi.value = wantsMobileUi({ os: hostOs, coarse: coarse?.matches ?? false, width: window.innerWidth })
  }
  update()
  window.addEventListener('resize', update, { passive: true })
  coarse?.addEventListener?.('change', update)
  watch(mobileUi, (on) => root.classList.toggle('is-mobile', on), { immediate: true })

  // Fähigkeiten früh laden: Navigation und Seiten blenden danach aus, was es hier nicht gibt.
  loadAppInfo().catch(() => {})
})
