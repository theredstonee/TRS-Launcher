// Betriebssystem als Klasse am <html> (`os-windows`, `os-linux`) – für die
// wenigen Stellen, an denen WebKitGTK (Linux) anders zeichnet als WebView2.
export default defineNuxtPlugin(() => {
  document.documentElement.classList.add(`os-${hostOs}`)
})
