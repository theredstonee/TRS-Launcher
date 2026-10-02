import { defineStore } from 'pinia'
import type { LiveEvent } from '~/utils/chat'
import type { InboxPack, PackLink, PackUpdateInfo } from '~/utils/packs'

type PackEvent = Extract<LiveEvent, { type: 'pack_shared' | 'pack_updated' | 'pack_removed' }>

/** Update-Prüfung höchstens so oft (Start, Bibliothek öffnen, `pack_updated`). */
const UPDATE_CHECK_MS = 10 * 60_000

// Geteilte Modpacks (API §27): welche Instanz zu welchem Pack gehört, verfügbare
// Updates, Packs von Freunden – und die Dialoge „Per Code installieren“,
// „Modpack teilen“ und „Meine Modpacks“, die von überall aufgehen können.
export const usePacksStore = defineStore('packs', () => {
  const links = ref<PackLink[]>([])
  const updates = ref<PackUpdateInfo[]>([])
  const inbox = ref<InboxPack[]>([])
  const inboxLoaded = ref(false)
  let lastUpdateCheck = 0

  /** „Per Code installieren“ (`code` vorausgefüllt, z. B. aus der Liste oder einem Toast). */
  const codeDialog = ref<{ code: string } | null>(null)
  const mineOpen = ref(false)

  const linkOf = (instanceId: string) => links.value.find((l) => l.instanceId === instanceId) ?? null
  const updateOf = (instanceId: string) => updates.value.find((u) => u.instanceId === instanceId) ?? null

  async function loadLinks() {
    try {
      links.value = await backend.packs.links()
    } catch {
      // Ohne Liste gibt es eben keine Hinweise.
    }
  }

  /** Neue Versionen installierter Packs (braucht die TRS-Dienste). */
  async function checkUpdates(force = false) {
    const trs = useTrsStore()
    if (!trs.enabled) return
    if (!force && Date.now() - lastUpdateCheck < UPDATE_CHECK_MS) return
    lastUpdateCheck = Date.now()
    await loadLinks()
    if (!links.value.some((l) => l.role === 'installed')) {
      updates.value = []
      return
    }
    try {
      updates.value = await backend.packs.updates()
    } catch {
      // Offline o. Ä. – beim nächsten Mal.
    }
  }

  async function loadInbox() {
    if (!useTrsStore().enabled) return
    try {
      inbox.value = await backend.packs.inbox()
      inboxLoaded.value = true
    } catch {
      // Später erneut.
    }
  }

  async function dismiss(packId: string) {
    const before = inbox.value
    inbox.value = inbox.value.filter((e) => e.pack.id !== packId)
    try {
      await backend.packs.dismiss(packId)
    } catch (e) {
      inbox.value = before
      useToasts().error(e)
    }
  }

  function openCode(code = '') {
    codeDialog.value = { code }
  }

  /** Neue Version übernehmen (als Aufgabe; Hinweis auf behaltene eigene Änderungen). */
  function update(instanceId: string) {
    const info = updateOf(instanceId)
    const instance = useInstancesStore().items.find((i) => i.id === instanceId)
    const title = instance?.name ?? info?.latest.name ?? 'Modpack'
    return useTasksStore().run(
      { key: taskKey('packupdate', instanceId), kind: 'modpack', title, stage: packStageLabel('pack'), instanceId, cancellable: true, pausable: true },
      async (ctx) => {
        const result = await backend.packs.update(instanceId, (p) => ctx.progress(packPercent(p), packStageLabel(p.phase, p)), ctx.taskId)
        ctx.update({
          doneText: result.kept.length
            ? t('packs.update.doneKept', { name: title, count: result.kept.length }, result.kept.length)
            : t('packs.update.done', { name: title }),
        })
        updates.value = updates.value.filter((u) => u.instanceId !== instanceId)
        await Promise.allSettled([loadLinks(), useInstancesStore().load()])
        return result
      },
    )
  }

  function onLiveEvent(e: PackEvent) {
    switch (e.type) {
      case 'pack_shared': {
        inbox.value = [{ pack: e.pack, from: e.from, sentAt: e.sentAt }, ...inbox.value.filter((x) => x.pack.id !== e.pack.id)]
        const code = e.pack.code
        void useSocialToasts().notify('pack', {
          key: `pack:${e.pack.id}`,
          title: t('packs.toast.sharedTitle', { name: e.from.name }),
          body: `${e.pack.name} · ${packVersionLine(e.pack)}`,
          face: e.from,
          open: () => openCode(code),
          actions: [{ label: t('packs.toast.view'), primary: true, run: () => openCode(code) }],
        })
        return
      }
      case 'pack_updated': {
        inbox.value = inbox.value.map((x) => (x.pack.id === e.pack.id ? { ...x, pack: e.pack } : x))
        const mine = links.value.filter((l) => l.role === 'installed' && l.packId === e.pack.id && l.revision < e.pack.revision)
        if (!mine.length) return
        updates.value = [
          ...updates.value.filter((u) => !mine.some((l) => l.instanceId === u.instanceId)),
          ...mine.map((l) => ({ instanceId: l.instanceId, revision: l.revision, latest: e.pack })),
        ]
        const first = mine[0]!
        void useSocialToasts().notify('pack', {
          key: `packupd:${e.pack.id}`,
          title: t('packs.toast.updateTitle', { name: e.pack.name }),
          body: t('packs.toast.updateBody', { version: e.pack.packVersion }),
          face: e.pack.owner,
          open: () => void navigateTo(`/instances/${first.instanceId}`),
          actions: [{ label: t('packs.update.action'), primary: true, run: () => void update(first.instanceId) }],
        })
        return
      }
      case 'pack_removed':
        inbox.value = inbox.value.filter((x) => x.pack.id !== e.packId)
        return
    }
  }

  return {
    links,
    updates,
    inbox,
    inboxLoaded,
    codeDialog,
    mineOpen,
    linkOf,
    updateOf,
    loadLinks,
    checkUpdates,
    loadInbox,
    dismiss,
    openCode,
    update,
    onLiveEvent,
  }
})
