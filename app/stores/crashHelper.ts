import { defineStore } from 'pinia'
import type { CrashAction, CrashAnalysis } from '~/types'
import { actionKey, fileLabel, formatMb } from '~/utils/crash'

/**
 * Absturz-Helfer: nach einem Absturz kommt die Analyse des Kerns als
 * `crashAnalyzed` – der Dialog öffnet sich dann von selbst. Frühere Abstürze
 * lassen sich aus Verlauf und Logs-Tab wieder öffnen. Behebungen laufen über
 * die bestehenden Befehle (Inhalte, Modrinth, Reparatur, Java) und landen im
 * Verlauf der Instanz; vorher bestätigt der Nutzer im Dialog.
 */
export const useCrashHelperStore = defineStore('crashHelper', () => {
  /** Der offene Dialog. */
  const current = ref<CrashAnalysis | null>(null)
  /** Neueste Analyse je Instanz (für das Panel auf der Instanzseite). */
  const latest = ref<Record<string, CrashAnalysis>>({})
  /** Aktionen, die gerade laufen bzw. erledigt sind (je Absturz + Aktion). */
  const running = ref<Set<string>>(new Set())
  const done = ref<Set<string>>(new Set())
  const loading = ref(false)

  function received(crash: CrashAnalysis) {
    latest.value = { ...latest.value, [crash.instanceId]: crash }
    current.value = crash
  }

  function show(crash: CrashAnalysis) {
    current.value = crash
  }

  function close() {
    current.value = null
  }

  /** Gespeicherten Absturz (Verlauf) öffnen. */
  async function openSaved(instanceId: string, crashId: string) {
    loading.value = true
    try {
      current.value = await backend.getCrash(instanceId, crashId)
    } catch (e) {
      useToasts().error(e)
    } finally {
      loading.value = false
    }
  }

  /** Log-Datei bzw. Crash-Report aus dem Logs-Tab analysieren. */
  async function analyzeSource(instanceId: string, source: string) {
    loading.value = true
    try {
      current.value = await backend.analyzeLogSource(instanceId, source)
    } catch (e) {
      useToasts().error(e)
    } finally {
      loading.value = false
    }
  }

  const stateKey = (crash: CrashAnalysis, action: CrashAction) => `${crash.id}|${actionKey(action)}`
  const isRunning = (crash: CrashAnalysis, action: CrashAction) => running.value.has(stateKey(crash, action))
  const isDone = (crash: CrashAnalysis, action: CrashAction) => done.value.has(stateKey(crash, action))

  function instanceOf(id: string) {
    const instance = useInstancesStore().items.find((i) => i.id === id)
    return { id, name: instance?.name ?? id }
  }

  /** Dateien deaktivieren (umbenennen zu `.disabled`) – mit „Rückgängig“ im Toast. */
  async function disableFiles(instanceId: string, files: string[], crash: CrashAnalysis): Promise<boolean> {
    const changed: string[] = []
    try {
      for (const file of files) {
        await backend.setContentEnabled(instanceId, 'mod', file, false)
        changed.push(file)
      }
    } catch (e) {
      useToasts().error(e)
      return false
    }
    const names = changed.map((f) => fileLabel(f, crash)).join(', ')
    useToasts().ok(t('crashHelper.done.disabled', { name: names }), {
      label: t('crashHelper.undo'),
      run: () => void undoDisable(instanceId, changed, names),
    })
    return true
  }

  async function undoDisable(instanceId: string, files: string[], names: string) {
    try {
      for (const file of files) await backend.setContentEnabled(instanceId, 'mod', file, true)
      useToasts().ok(t('crashHelper.done.enabledAgain', { name: names }))
    } catch (e) {
      useToasts().error(e)
    }
  }

  async function perform(crash: CrashAnalysis, action: CrashAction): Promise<boolean> {
    const instance = instanceOf(crash.instanceId)
    const toasts = useToasts()
    switch (action.type) {
      case 'disableMods':
        return disableFiles(instance.id, action.files, crash)
      case 'removeDuplicates':
        return disableFiles(instance.id, action.files, crash)
      case 'installDependencies':
        return (await installMissingModsTask(instance, action.declarer, action.dependencies)).ok
      case 'fixConflict':
        return (await fixModConflictsTask(instance, action.modId)).ok
      case 'repair':
        return (await repairInstanceTask(instance, 'repair')).ok
      case 'setMemory':
        try {
          await backend.setInstanceMemory(instance.id, action.toMb)
          toasts.ok(t('crashHelper.done.memory', { to: formatMb(action.toMb) }))
          useInstancesStore().load()
          return true
        } catch (e) {
          toasts.error(e)
          return false
        }
      case 'switchJava': {
        const major = action.major
        const result = await useTasksStore().run(
          {
            key: taskKey('crash-java', instance.id),
            kind: 'java',
            title: instance.name,
            stage: major ? t('crashHelper.progress.java', { major }) : t('crashHelper.actions.autoJava'),
            instanceId: instance.id,
            doneText: major ? t('crashHelper.done.java', { major }) : t('crashHelper.done.autoJava'),
          },
          (ctx) => backend.switchInstanceJava(instance.id, major, (p) => ctx.progress(p), ctx.taskId),
        )
        if (result.ok) useInstancesStore().load()
        return result.ok
      }
      case 'updateTrsClient':
        try {
          const version = await backend.updateTrsClientNow(instance.id)
          const before = crash.mods.find((m) => m.id === 'trsclient')?.version
          if (version && version !== before) {
            toasts.ok(t('crashHelper.done.trsClient', { version }))
            return true
          }
          toasts.info(t('crashHelper.done.trsClientNone'))
          return false
        } catch (e) {
          toasts.error(e)
          return false
        }
    }
  }

  /** Aktion ausführen (Bestätigung macht der Dialog). */
  async function run(crash: CrashAnalysis, action: CrashAction) {
    const key = stateKey(crash, action)
    if (running.value.has(key)) return
    running.value = new Set(running.value).add(key)
    try {
      if (await perform(crash, action)) {
        done.value = new Set(done.value).add(key)
        // Erfolge: der Absturz-Helfer hat etwas behoben.
        useAchievementsStore().reportCrashFixed()
      }
    } finally {
      const next = new Set(running.value)
      next.delete(key)
      running.value = next
    }
  }

  return { current, latest, loading, received, show, close, openSaved, analyzeSource, run, isRunning, isDone }
})
