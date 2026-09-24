import type { ContentKind, ContentUpdate, Instance, StageProgress } from '~/types'

// Gemeinsame Aufgaben, die mehrere Seiten starten (Entdecken, Projektseite,
// Inhaltsliste). Sie laufen im Aufgaben-Store weiter, auch wenn die Seite
// verlassen wird; Toasts kommen von dort genau einmal.

export function modpackTaskKey(projectId: string): string {
  return taskKey('modpack', projectId)
}

export function contentTaskKey(instanceId: string, projectId: string): string {
  return taskKey('content', instanceId, projectId)
}

/** Modrinth-Modpack als neue Instanz installieren (abbrechbar, pausierbar). */
export function installModpackTask(pack: { projectId: string; title: string; iconUrl: string | null }) {
  const instances = useInstancesStore()
  return useTasksStore().run(
    {
      key: modpackTaskKey(pack.projectId),
      kind: 'modpack',
      title: pack.title,
      stage: packStageLabel('pack'),
      iconUrl: pack.iconUrl,
      cancellable: true,
      pausable: true,
    },
    async (ctx) => {
      const instance = await backend.installModpack(
        pack.projectId,
        (p) => {
          ctx.progress(packPercent(p), packStageLabel(p.phase))
          // Entpacken lässt sich nicht mehr sinnvoll anhalten.
          if (p.phase === 'overrides') ctx.update({ pausable: false })
        },
        ctx.taskId,
      )
      ctx.update({ instanceId: instance.id, doneText: t('tasks.toast.modpackReady', { name: instance.name }) })
      await instances.load()
      // Presets mit „immer automatisch“ laufen danach als eigene Aufgabe.
      void applyAutoPresetsTask(instance)
      return instance
    },
  )
}

export function repairTaskKey(instanceId: string): string {
  return taskKey('repair', instanceId)
}

/**
 * Spieldateien prüfen und reparieren bzw. komplett neu laden. Beides teilt
 * sich eine Aufgabe je Instanz – nie zwei gleichzeitig.
 */
export function repairInstanceTask(instance: Pick<Instance, 'id' | 'name'>, kind: 'repair' | 'reinstall') {
  return useTasksStore().run(
    {
      key: repairTaskKey(instance.id),
      kind,
      title: instance.name,
      stage: kind === 'repair' ? t('tasks.stage.checkingFiles') : t('tasks.stage.reinstalling'),
      instanceId: instance.id,
      cancellable: true,
      pausable: true,
      doneText: kind === 'repair' ? t('tasks.toast.repairDone') : t('tasks.toast.reinstallDone'),
    },
    (ctx) => {
      const onProgress = (p: StageProgress) => ctx.progress(overallPercent(p.stage, p.percent), stageLabel(p.stage))
      return kind === 'repair'
        ? backend.repairInstance(instance.id, onProgress, ctx.taskId)
        : backend.reinstallInstance(instance.id, onProgress, ctx.taskId)
    },
  )
}

/**
 * Inhalt (Mod, Shader …) in eine Instanz installieren – ohne `version` die
 * neueste passende; mit `replace` wird die installierte Datei ersetzt.
 */
export function installContentTask(options: {
  instance: Pick<Instance, 'id' | 'name'>
  projectId: string
  title: string
  iconUrl: string | null
  kind: ContentKind
  version: { id: string; versionNumber: string } | null
  replace?: string | null
}) {
  const { instance, projectId, title, kind, version, replace } = options
  return useTasksStore().run(
    {
      key: contentTaskKey(instance.id, projectId),
      kind: 'content',
      title,
      stage: t('tasks.stage.installingInto', { name: instance.name }),
      instanceId: instance.id,
      iconUrl: options.iconUrl,
      tag: version?.id ?? 'latest',
      // Einzelne Mods fluten sonst den Verlauf.
      record: false,
    },
    async (ctx) => {
      if (replace && version) {
        const update: ContentUpdate = { kind, fileName: replace, projectId, versionId: version.id, versionNumber: version.versionNumber }
        await backend.applyContentUpdate(instance.id, update, ctx.taskId)
        ctx.update({ doneText: t('tasks.toast.contentVersionInstalled', { title, version: version.versionNumber }) })
        return [replace]
      }
      const files = await backend.modrinthInstall(instance.id, projectId, kind, version?.id ?? null, ctx.taskId)
      ctx.update({
        doneText:
          files.length > 1
            ? t('tasks.toast.contentWithDependencies', { title }, files.length - 1)
            : t('tasks.toast.contentInstalled', { title }),
      })
      return files
    },
  )
}

export function presetsTaskKey(instanceId: string): string {
  return taskKey('presets', instanceId)
}

/**
 * Presets in eine Instanz installieren (abbrechbar). Am Ende genau ein Toast
 * mit Zusammenfassung („3 von 4 installiert – …“) und „Details“.
 */
export async function applyPresetsTask(instance: Pick<Instance, 'id' | 'name'>, presetIds: string[]) {
  if (!presetIds.length) return null
  const presets = usePresetsStore()
  const toasts = useToasts()
  const title = t('presets.task.title', { name: instance.name })
  const result = await useTasksStore().run(
    {
      key: presetsTaskKey(instance.id),
      kind: 'presets',
      title,
      stage: t('presets.task.preparing'),
      instanceId: instance.id,
      cancellable: true,
      pausable: true,
      // Den Toast mit „Details“ schicken wir selbst.
      notify: false,
    },
    async (ctx) => {
      const report = await backend.applyPresets(
        instance.id,
        presetIds,
        (p) => ctx.progress(presetPercent(p), presetStage(p)),
        ctx.taskId,
      )
      ctx.update({ doneText: presetSummaryText(report) })
      return report
    },
  )
  if (result.ok) {
    const report = result.value
    const action = { label: t('presets.report.details'), run: () => presets.openReport(report, instance) }
    const text = `${instance.name}: ${presetSummaryText(report)}`
    if (summarizePresetReport(report).problems.length) toasts.info(text, action)
    else toasts.ok(text, action)
  } else if (!result.cancelled && !result.discarded) {
    toasts.error(t('tasks.toast.failed', { title, error: errorMessage(result.error) }))
  }
  return result
}

/**
 * Nach einem neuen Modpack: Presets mit „immer automatisch“ ergänzen – nur
 * solche, die sich mit Modpacks vertragen (kein FPS-Boost).
 */
export async function applyAutoPresetsTask(instance: Pick<Instance, 'id' | 'name' | 'loader'>) {
  const presets = usePresetsStore()
  try {
    await presets.load()
  } catch {
    return null
  }
  const ids = defaultPresetSelection(presets.items, { loader: instance.loader.kind, context: 'modpack' })
  return applyPresetsTask(instance, ids)
}
