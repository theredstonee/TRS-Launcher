/**
 * Dialog „Auf der Website anmelden“ (API §29): geöffnet über einen `trs-launcher://web-login/…`-Link (mit Token) oder
 * von Hand (Einstellungen → Datenschutz, Strg+K) zum Eintippen des Codes. `seq` erzwingt einen frischen Dialog, wenn
 * ein neuer Link kommt, während einer offen ist.
 */
export const useWebLoginStore = defineStore('webLogin', () => {
  const request = ref<{ token: string | null; seq: number } | null>(null)
  let seq = 0

  function open(token: string | null = null) {
    request.value = { token: isWebLoginToken(token) ? token : null, seq: ++seq }
  }

  function close() {
    request.value = null
  }

  return { request, open, close }
})
