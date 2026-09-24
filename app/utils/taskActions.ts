import type { ContentKind, ContentUpdate, Instance, Platform, StageProgress } from '~/types'

// Gemeinsame Aufgaben, die mehrere Seiten starten (Entdecken, Projektseite,
// Inhaltsliste). Sie laufen im Aufgaben-Store weiter, auch wenn die Seite
// verlassen wird; Toasts kommen von dort genau einmal.

/** `projectKey` wie in utils/platform (CurseForge mit Präfix `cf:`). */
export function modpackTaskKey(projectKey: string): string {
  return taskKey('modpack', projectKey)
}

export function contentTaskKey(instanceId: string, projectKey: string): string {
  return taskKey('content', instanceId, projectKey)
}

/** Nach einer Installation: Dateien, die der Nutzer selbst laden muss, gleich zeigen. */
function showBlocked(instanceId: string, open: boolean) {
  const curseforge = useCurseForgeStore()
  curseforge.touch()
  if (open) curseforge.openBlocked(instanceId)
}

/** Der Autor sperrt das ganze Modpack: Link zur Seite auf CurseForge anbieten. */
function offerPackPage(e: unknown) {
  if (!(e instanceof BackendError) || e.code !== 'curseforge.packBlocked') return
  const url = e.params?.url
  if (!url || !isSafeLink(url)) return
  useToasts().info(t('curseforge.packBlocked'), {
    label: t('curseforge.openOnCurseForge'),
    run: () => void backend.openExternalUrl(url).catch(() => {}),
  })
}

/** Modpack als neue Instanz installieren (abbrechbar, pausierbar). */
export function installModpackTask(
  pack: { projectId: string; title: string; iconUrl: string | null },
  platform: Platform = 'modrinth',
) {
  const instances = useInstancesStore()
  return useTasksStore().run(
    {
      key: modpackTaskKey(projectKey(platform, pack.projectId)),
      kind: 'modpack',
      title: pack.title,
      stage: packStageLabel('pack'),
      iconUrl: pack.iconUrl,
      cancellable: true,
      pausable: true,
    },
    async (ctx) => {
      const onProgress = (p: { phase: 'pack' | 'files' | 'overrides'; percent: number }) => {
        ctx.progress(packPercent(p), packStageLabel(p.phase))
        // Entpacken lässt sich nicht mehr sinnvoll anhalten.
        if (p.phase === 'overrides') ctx.update({ pausable: false })
      }
      if (platform === 'curseforge') {
        let result
        try {
          result = await backend.curseforge.installModpack(pack.projectId, onProgress, ctx.taskId)
        } catch (e) {
          offerPackPage(e)
          throw e
        }
        const { instance, blocked } = result
        ctx.update({
          instanceId: instance.id,
          doneText: blocked.length
            ? t('tasks.toast.modpackReadyBlocked', { name: instance.name, count: blocked.length }, blocked.length)
            : t('tasks.toast.modpackReady', { name: instance.name }),
        })
        await instances.load()
        showBlocked(instance.id, blocked.length > 0)
        return instance
      }
      const instance = await backend.installModpack(pack.projectId, onProgress, ctx.taskId)
      ctx.update({ instanceId: instance.id, doneText: t('tasks.toast.modpackReady', { name: instance.name }) })
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
  platform?: Platform
}) {
  const { instance, projectId, title, kind, version, replace } = options
  const platform = options.platform ?? 'modrinth'
  return useTasksStore().run(
    {
      key: contentTaskKey(instance.id, projectKey(platform, projectId)),
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
        const update: ContentUpdate = { platform, kind, fileName: replace, projectId, versionId: version.id, versionNumber: version.versionNumber }
        try {
          await backend.applyContentUpdate(instance.id, update, ctx.taskId)
        } catch (e) {
          // Gesperrte Datei: steht jetzt in der Liste zum Selbst-Laden.
          if (e instanceof BackendError && e.code === 'curseforge.downloadBlocked') showBlocked(instance.id, true)
          throw e
        }
        ctx.update({ doneText: t('tasks.toast.contentVersionInstalled', { title, version: version.versionNumber }) })
        return [replace]
      }
      if (platform === 'curseforge') {
        const { files, blocked } = await backend.curseforge.install(instance.id, projectId, kind, version?.id ?? null, ctx.taskId)
        const dependencies = Math.max(files.length - 1, 0)
        ctx.update({
          doneText: blocked.length
            ? t('tasks.toast.contentBlocked', { title, count: blocked.length }, blocked.length)
            : dependencies
              ? t('tasks.toast.contentWithDependencies', { title }, dependencies)
              : t('tasks.toast.contentInstalled', { title }),
        })
        showBlocked(instance.id, blocked.length > 0)
        return files
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
