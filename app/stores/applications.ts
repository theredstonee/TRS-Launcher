import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können (`useTrsStore` bleibt Auto-Import).
import { applicationToast, isOpenApplication, type MyApplication } from '../utils/applications'
import { backend, errorMessage } from '../utils/backend'
import type { LiveEvent } from '../utils/chat'
import { t } from '../utils/i18n'
import { useSocialToasts } from './socialToasts'

type ApplicationEvent = Extract<LiveEvent, { type: 'application_updated' }>

// Eigene Team-Bewerbungen (API §24.3): Liste für „Meine Bewerbungen“, Zurückziehen
// und das Ereignis `application_updated` (Hinweis über die Sozial-Benachrichtigungen –
// im Spiel mit TRS Client schweigt der Launcher dann automatisch).
export const useApplicationsStore = defineStore('applications', () => {
  const list = ref<MyApplication[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  /** Dialog „Meine Bewerbungen“ (aus Einstellungen, Strg+K oder dem Hinweis). */
  const dialogOpen = ref(false)
  const focusId = ref<string | null>(null)

  const openCount = computed(() => list.value.filter(isOpenApplication).length)

  function sort(items: MyApplication[]): MyApplication[] {
    return [...items].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
  }

  async function load() {
    const trs = useTrsStore()
    if (!trs.enabled || !trs.status?.account) {
      list.value = []
      loaded.value = false
      return
    }
    loading.value = true
    error.value = null
    try {
      list.value = sort(await backend.applications.mine())
      loaded.value = true
    } catch (e) {
      error.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }

  /** `true`, wenn sich Status oder Antwort geändert haben (neu = auch geändert). */
  function upsert(a: MyApplication): boolean {
    const before = list.value.find((x) => x.id === a.id)
    list.value = sort([a, ...list.value.filter((x) => x.id !== a.id)])
    return !before || before.status !== a.status || before.response !== a.response
  }

  async function withdraw(id: string): Promise<MyApplication> {
    const updated = await backend.applications.withdraw(id)
    upsert(updated)
    return updated
  }

  function show(id: string | null = null) {
    focusId.value = id
    dialogOpen.value = true
  }

  /** `application_updated` (§24.3): eingereicht, Status/Antwort geändert, zurückgezogen. */
  function onLiveEvent(e: ApplicationEvent) {
    const a = e.application
    // Eigene Änderung aus diesem Launcher (zurückgezogen) kommt schon an – kein doppelter Hinweis.
    if (!upsert(a)) return
    const { title, body } = applicationToast(a)
    void useSocialToasts().notify('application', {
      key: `application:${a.id}`,
      title,
      body,
      face: null,
      open: () => show(a.id),
      actions: [{ label: t('applications.toast.view'), primary: true, run: () => show(a.id) }],
    })
  }

  return { list, loaded, loading, error, dialogOpen, focusId, openCount, load, withdraw, show, onLiveEvent }
})
