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
      stage: packStageLabels.pack,
      iconUrl: pack.iconUrl,
      cancellable: true,
      pausable: true,
    },
    async (ctx) => {
      const instance = await backend.installModpack(
        pack.projectId,
        (p) => {
          ctx.progress(packPercent(p), packStageLabels[p.phase])
          // Entpacken lässt sich nicht mehr sinnvoll anhalten.
          if (p.phase === 'overrides') ctx.update({ pausable: false })
        },
        ctx.taskId,
      )
      ctx.update({ instanceId: instance.id, doneText: `Modpack „${instance.name}“ ist bereit` })
      await instances.load()
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
      stage: kind === 'repair' ? 'Dateien werden geprüft' : 'Wird neu installiert',
      instanceId: instance.id,
      cancellable: true,
      pausable: true,
      doneText: kind === 'repair' ? 'Alle Dateien geprüft – beschädigte wurden neu geladen' : 'Instanz neu installiert',
    },
    (ctx) => {
      const onProgress = (p: StageProgress) => ctx.progress(overallPercent(p.stage, p.percent), stageLabels[p.stage])
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
      stage: `Wird in ${instance.name} installiert`,
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
        ctx.update({ doneText: `${title}: Version ${version.versionNumber} installiert` })
        return [replace]
      }
      const files = await backend.modrinthInstall(instance.id, projectId, kind, version?.id ?? null, ctx.taskId)
      ctx.update({
        doneText: files.length > 1 ? `${title} und ${files.length - 1} Abhängigkeit(en) installiert` : `${title} installiert`,
      })
      return files
    },
  )
}
