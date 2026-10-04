import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können (Spiele, Aufgaben,
// Instanzen, Toasts und Router kommen in der App über Nuxts Auto-Imports, im Test als Attrappen).
import { backend } from '../utils/backend'
import { t } from '../utils/i18n'
import { type ConflictParty, type ConflictReport, filesToDisable, fitMessage, partyKey } from '../utils/modConflicts'
import { projectRoute } from '../utils/platform'
import { taskKey } from '../utils/tasks'

/** Woher der Helfer geöffnet wurde (gestoppter Start, Hinweis auf der Instanzseite, Absturz-Helfer). */
export type ConflictOrigin = 'launch' | 'banner' | 'crash'

/**
 * Mod-Konflikt-Helfer: Der Kern stoppt einen Fabric/Quilt-Start, wenn Mod-Versionen
 * nicht zusammenpassen und sich keine passende findet. Der Dialog zeigt die Konflikte
 * und bietet je Mod „Passende Version suchen“, „Deaktivieren“, „Entfernen“ und die
 * Mod-Seite an – nach jeder Aktion wird neu geprüft. Ist alles gelöst, schließt er
 * sich, und „Starten“ startet das Spiel.
 */
export const useModConflictsStore = defineStore('modConflicts', () => {
  const instanceId = ref<string | null>(null)
  const origin = ref<ConflictOrigin>('banner')
  const report = ref<ConflictReport | null>(null)
  const loading = ref(false)
  /** Laufende Aktionen: `<aktion>|<datei>` bzw. `all`. */
  const busy = ref<Set<string>>(new Set())
  /** Hinweise je Mod (z. B. „Noch keine passende Version“). */
  const notes = ref<Record<string, string>>({})
  /** Mod, deren Entfernen gerade bestätigt werden soll. */
  const confirmRemove = ref<ConflictParty | null>(null)

  const isOpen = computed(() => instanceId.value !== null)
  const resolved = computed(() => !!report.value && report.value.conflicts.length === 0)

  function instanceName(id: string): string {
    return useInstancesStore().items.find((i) => i.id === id)?.name ?? id
  }

  async function open(id: string, from: ConflictOrigin = 'banner') {
    instanceId.value = id
    origin.value = from
    report.value = null
    notes.value = {}
    confirmRemove.value = null
    await refresh(false)
  }

  function close() {
    instanceId.value = null
    report.value = null
    confirmRemove.value = null
  }

  /**
   * Neu prüfen. `afterAction`: nach einer Behebung – ist dann alles gelöst, schließt
   * der Helfer und bietet „Starten“ im Hinweis an.
   */
  async function refresh(afterAction = true) {
    const id = instanceId.value
    if (!id) return
    loading.value = true
    try {
      const next = await backend.modConflicts(id)
      if (instanceId.value !== id) return
      report.value = next
      if (afterAction && next.conflicts.length === 0) {
        close()
        useToasts().ok(t('modConflicts.resolved'), { label: t('modConflicts.launch'), run: () => void useGamesStore().launch(id) })
      }
    } catch (e) {
      useToasts().error(e)
    } finally {
      loading.value = false
    }
  }

  const isBusy = (action: string, p?: ConflictParty) => busy.value.has(p ? `${action}|${partyKey(p)}` : action)

  async function withBusy(key: string, work: () => Promise<boolean>) {
    if (busy.value.has(key)) return
    busy.value = new Set(busy.value).add(key)
    try {
      if (await work()) await refresh()
    } finally {
      const next = new Set(busy.value)
      next.delete(key)
      busy.value = next
    }
  }

  /** „Passende Version suchen“ (Modrinth; als Aufgabe mit Abbrechen). */
  function findVersion(p: ConflictParty) {
    const id = instanceId.value
    const file = p.fileName
    if (!id || !file) return Promise.resolve()
    return withBusy(`fit|${partyKey(p)}`, async () => {
      const result = await useTasksStore().run(
        {
          key: taskKey('modfit', id, file),
          kind: 'content-update',
          title: instanceName(id),
          stage: t('modConflicts.fit.searching', { name: p.name }),
          instanceId: id,
          cancellable: true,
        },
        async (ctx) => {
          const r = await backend.modConflictFit(id, file, ctx.taskId)
          ctx.update({ doneText: fitMessage(r) })
          return r
        },
      )
      if (!result.ok) {
        if (!result.cancelled) useToasts().error(result.error)
        return false
      }
      notes.value = { ...notes.value, [partyKey(p)]: fitMessage(result.value) }
      return result.value.status === 'installed'
    })
  }

  function disable(p: ConflictParty) {
    const id = instanceId.value
    const file = p.fileName
    if (!id || !file) return Promise.resolve()
    return withBusy(`disable|${partyKey(p)}`, async () => {
      try {
        await backend.setContentEnabled(id, 'mod', file, false)
        useToasts().ok(t('modConflicts.disabled', { name: p.name }))
        return true
      } catch (e) {
        useToasts().error(e)
        return false
      }
    })
  }

  /** In den Papierkorb (nach Bestätigung im Dialog). */
  function remove(p: ConflictParty) {
    const id = instanceId.value
    const file = p.fileName
    confirmRemove.value = null
    if (!id || !file) return Promise.resolve()
    return withBusy(`remove|${partyKey(p)}`, async () => {
      try {
        await backend.trashContent(id, 'mod', file)
        useToasts().ok(t('modConflicts.removed', { name: p.name }))
        return true
      } catch (e) {
        useToasts().error(e)
        return false
      }
    })
  }

  function openPage(p: ConflictParty) {
    const id = instanceId.value
    if (!p.projectId) return
    close()
    void useRouter().push(projectRoute(p.platform ?? 'modrinth', p.projectId, id))
  }

  /** Alle Mods, die eine Bedingung stellen, deaktivieren und gleich starten. */
  function disableAllAndLaunch() {
    const id = instanceId.value
    const current = report.value
    if (!id || !current) return Promise.resolve()
    return withBusy('all', async () => {
      const files = filesToDisable(current)
      try {
        for (const file of files) await backend.setContentEnabled(id, 'mod', file, false)
      } catch (e) {
        useToasts().error(e)
        return true
      }
      const names = current.conflicts.filter((c) => c.declarer.fileName && files.includes(c.declarer.fileName)).map((c) => c.declarer.name)
      useToasts().ok(t('modConflicts.disabled', { name: [...new Set(names)].join(', ') }))
      close()
      void useGamesStore().launch(id)
      return false
    })
  }

  /** „Trotzdem starten“: einmal ohne Versions-Prüfung (gegen Fehlalarme). */
  function launchAnyway() {
    const id = instanceId.value
    if (!id) return
    close()
    void useGamesStore().launch(id, null, null, null, { skipModCheck: true })
  }

  /** Starten (wenn nichts mehr im Weg steht). */
  function launch() {
    const id = instanceId.value
    if (!id) return
    close()
    void useGamesStore().launch(id)
  }

  return {
    instanceId,
    origin,
    report,
    loading,
    notes,
    confirmRemove,
    isOpen,
    resolved,
    open,
    close,
    refresh,
    isBusy,
    findVersion,
    disable,
    remove,
    openPage,
    disableAllAndLaunch,
    launchAnyway,
    launch,
  }
})
