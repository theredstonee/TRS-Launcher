import { defineStore } from 'pinia'
import { ref } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können (`useTrsStore` bleibt Auto-Import).
import { BackendError, backend, errorMessage } from '../utils/backend'
import { t } from '../utils/i18n'
import type { MyPartner, PartnerEvent } from '../utils/partner'

// Partner-Programm (API §44): unterstützter Partner, eigene Partner-Kosmetik (für
// die Herkunft in der Garderobe) und Partner, deren Website-Dashboard man öffnen darf.
export const usePartnerStore = defineStore('partner', () => {
  const me = ref<MyPartner | null>(null)
  const loaded = ref(false)
  /** Konto, für das `me` geladen wurde. */
  const account = ref<string | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  let seq = 0
  async function load() {
    const trs = useTrsStore()
    const n = ++seq
    if (!trs.enabled || !trs.status?.account) {
      me.value = null
      loaded.value = false
      account.value = null
      return
    }
    const forAccount = trs.status.account
    loading.value = true
    error.value = null
    try {
      const data = await backend.partner.me()
      if (n === seq) me.value = data
    } catch (e) {
      if (n !== seq) return
      // Ältere API ohne §44: keine Partner, kein Fehler.
      if (e instanceof BackendError && e.apiCode === 'not_found') me.value = null
      else error.value = errorMessage(e)
    } finally {
      if (n === seq) {
        loading.value = false
        loaded.value = true
        account.value = forAccount
      }
    }
  }

  /** Laden, wenn noch nicht für das aktuelle Konto geschehen (Garderobe). */
  function ensure() {
    const trs = useTrsStore()
    if (!loading.value && trs.enabled && trs.status?.account && account.value !== trs.status.account) void load()
  }

  async function endSupport() {
    await backend.partner.endSupport()
    await load()
  }

  /** Herkunft eines eigenen Teils für die Garderobe: „Partner: <Name>“ (`null` = kein Partner-Teil). */
  function sourceLabel(kind: 'cape' | 'cosmetic', id: string): string | null {
    const item = me.value?.owned.find((o) => o.kind === kind && o.id === id)
    if (!item) return null
    return item.partner ? t('partner.source', { name: item.partner.name }) : t('partner.title')
  }

  function onLiveEvent(e: PartnerEvent) {
    // Einlösen/Beenden auf einem anderen Gerät oder der Website; Mitgliedschaften (Anträge, Kontingent).
    if (e.type === 'partner_support_changed' || e.type === 'partner_updated') void load()
  }

  return { me, loaded, loading, error, load, ensure, endSupport, sourceLabel, onLiveEvent }
})
