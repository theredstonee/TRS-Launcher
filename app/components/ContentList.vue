<script setup lang="ts">
import { isTauri } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import type { BulkAction, ContentItem, ContentKind, ContentUpdate, DropEvent, DuplicateModGroup, Instance, ModrinthVersion, UploadResult } from '~/types'
import { cancelledError } from '~/stores/tasks'
import {
  buildSections,
  contentKey,
  emptyOrganization,
  GROUP_COLOR_HEX,
  groupToggleState,
  indexOrganization,
  matchesFilters,
  sortedGroups,
  type ContentGroup,
  type ContentOrganization,
  type ContentSection,
  type GroupColor,
  type OriginFilter,
} from '~/utils/contentGroups'

// Inhalte einer Instanz als EINE Tabelle: Filter-Chips,
// Suche, Sortierung, Mehrfachauswahl mit Sammelaktionen, Dateien per Dialog
// oder Drag & Drop hinzufügen. Eigene Gruppen (klappbar, als Ganzes schaltbar,
// per Menü oder Ziehen zuordnen) und Herkunft „vom Modpack“ / „selbst hinzugefügt“.
const props = defineProps<{ instance: Instance }>()

const items = ref<ContentItem[]>([])
const duplicates = ref<DuplicateModGroup[]>([])
const fixingDuplicates = ref(false)
const loading = ref(false)
const error = ref<string | null>(null)
const filter = ref<'all' | ContentKind>('all')
const search = ref('')
const sort = ref<'name' | 'enabled' | 'updates' | 'kind'>('name')
const selected = ref<Set<string>>(new Set())
const toasts = useToasts()
const updates = ref<ContentUpdate[] | null>(null)
const checking = ref(false)
const localBulk = ref<string | null>(null)
// Updates und Presets laufen als Aufgaben weiter, auch wenn die Seite wechselt.
const tasks = useTasksStore()
const updatesKey = computed(() => taskKey('updates', props.instance.id))
const updatesTask = computed(() => tasks.get(updatesKey.value))
const bulkBusy = computed(() => (updatesTask.value?.status === 'running' ? 'update' : localBulk.value))
const presetsBusy = computed(() => tasks.isRunning(presetsTaskKey(props.instance.id)))
const applyingPreset = ref(false)
/** Wird die Datei gerade aktualisiert bzw. gewechselt? */
function isBusy(item: ContentItem): boolean {
  if (updatesTask.value?.status === 'running' && updatesTask.value.tag === item.fileName) return true
  return !!item.source && tasks.isRunning(contentTaskKey(props.instance.id, projectKey(sourcePlatform(item.source), item.source.projectId)))
}

// Dateien, die der Nutzer selbst von CurseForge laden muss (Autor erlaubt keine Downloads über andere Apps).
const curseforge = useCurseForgeStore()
const blockedCount = ref(0)
async function loadBlocked() {
  blockedCount.value = (await backend.curseforge.blocked(props.instance.id).catch(() => [])).length
}
watch(() => curseforge.revision, loadBlocked)
onMounted(loadBlocked)
// Fertig gewordene Aufgaben dieser Instanz (auch im Hintergrund) → Liste neu laden.
const finishedHere = computed(
  () =>
    Object.values(tasks.tasks).filter(
      (task) =>
        task.instanceId === props.instance.id &&
        (task.kind === 'content' || task.kind === 'content-update' || task.kind === 'performance-pack' || task.kind === 'presets') &&
        task.status !== 'running',
    ).length,
)
watch(finishedHere, (n, before) => {
  if (n > (before ?? 0)) load(true)
})
const menuFor = ref<string | null>(null)
const switching = ref<ContentItem | null>(null)
const changelogFor = ref<ContentItem | null>(null)
const toDelete = ref<ContentItem[] | null>(null)
const dragging = ref(false)

// --- Gruppen & Herkunft ----------------------------------------------------------
const org = ref<ContentOrganization>(emptyOrganization())
const orgIndex = computed(() => indexOrganization(org.value))
const origin = ref<OriginFilter>('all')
const groupFilter = ref<string>('all')
/** Gruppe anlegen (`group: null`) oder bearbeiten; `assign` kommt nach dem Anlegen hinein. */
const groupDialog = ref<{ group: ContentGroup | null; assign: ContentItem[] } | null>(null)
const groupToDelete = ref<ContentGroup | null>(null)
const groupMenuFor = ref<string | null>(null)
const assignMenu = ref(false)
const groupOf = (item: ContentItem) => {
  const id = orgIndex.value.groupOf.get(contentKey(item))
  return id ? (orgIndex.value.groups.get(id) ?? null) : null
}
const isFromPack = (item: ContentItem) => orgIndex.value.fromPack.has(contentKey(item))
watch(
  () => org.value.groups.map((g) => g.id),
  (ids) => {
    if (groupFilter.value !== 'all' && groupFilter.value !== 'none' && !ids.includes(groupFilter.value)) groupFilter.value = 'all'
  },
)

// --- Fehlersuche („Schuldige Mod finden“) ---------------------------------------------
const bisect = useBisectStore()
const bisecting = computed(() => bisect.isActive(props.instance.id))
// Die Suche schaltet Mods um – Liste neu laden.
watch(() => bisect.revision, () => load(true))

const keyOf = (i: { kind: ContentKind; fileName: string }) => `${i.kind}/${i.fileName}`
const updateFor = (item: ContentItem) => updates.value?.find((u) => u.kind === item.kind && u.fileName === item.fileName)
const isVanilla = computed(() => props.instance.loader.kind === 'vanilla')
const displayVersion = (item: ContentItem) => item.version ?? item.source?.versionNumber ?? null
const titleOf = (item: ContentItem) => item.title ?? item.fileName

// --- Laden ---------------------------------------------------------------------
async function load(quiet = false) {
  if (!quiet) loading.value = true
  error.value = null
  const duplicateScan = backend.duplicateMods(props.instance.id).catch(() => [] as DuplicateModGroup[])
  const orgScan = backend.contentOrganization(props.instance.id).catch(() => null)
  try {
    const lists = await Promise.all(contentKinds.map((k) => backend.listContent(props.instance.id, k)))
    items.value = lists.flat()
    const keys = new Set(items.value.map(keyOf))
    selected.value = new Set([...selected.value].filter((k) => keys.has(k)))
    org.value = (await orgScan) ?? org.value
    duplicates.value = await duplicateScan
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function keepNewestDuplicates() {
  if (fixingDuplicates.value) return
  fixingDuplicates.value = true
  try {
    const files = await backend.resolveDuplicateMods(props.instance.id)
    if (files.length) toasts.ok(t('content.duplicates.done', { files: files.join(', ') }))
    await load(true)
  } catch (e) {
    toasts.error(e)
  } finally {
    fixingDuplicates.value = false
  }
}

async function checkUpdates(silent = false) {
  checking.value = true
  try {
    updates.value = await backend.checkContentUpdates(props.instance.id)
    if (!silent && !updates.value.length) toasts.info(t('content.toasts.upToDate'))
  } catch (e) {
    if (!silent) toasts.error(e)
  } finally {
    checking.value = false
  }
}

async function refresh() {
  await load(true)
  await checkUpdates()
}

// Icons, Titel und Autoren von Modrinth im Hintergrund ergänzen – einmal je Instanz.
onMounted(async () => {
  await load()
  try {
    if (await backend.refreshContentMeta(props.instance.id)) await load(true)
  } catch {
    // Offline oder Modrinth nicht erreichbar: Liste funktioniert auch ohne.
  }
  checkUpdates(true)
})

// --- Filtern, Suchen, Sortieren ------------------------------------------------------
const counts = computed(() => {
  const c: Partial<Record<ContentKind, number>> = {}
  for (const i of items.value) c[i.kind] = (c[i.kind] ?? 0) + 1
  return c
})
const chips = computed(() => contentKinds.filter((k) => counts.value[k]))
watch(chips, (list) => {
  if (filter.value !== 'all' && !list.includes(filter.value)) filter.value = 'all'
})

const visible = computed(() => {
  const needle = search.value.trim().toLowerCase()
  const filters = { needle, origin: origin.value, group: groupFilter.value }
  const list = items.value.filter((i) => (filter.value === 'all' || i.kind === filter.value) && matchesFilters(i, orgIndex.value, filters))
  const byName = (a: ContentItem, b: ContentItem) => compareText(titleOf(a), titleOf(b))
  const rank: Record<typeof sort.value, (i: ContentItem) => number> = {
    name: () => 0,
    enabled: (i) => (i.enabled ? 0 : 1),
    updates: (i) => (updateFor(i) ? 0 : 1),
    kind: (i) => contentKinds.indexOf(i.kind),
  }
  return list.sort((a, b) => rank[sort.value](a) - rank[sort.value](b) || byName(a, b))
})

/** Mit Gruppen: Abschnitte (Kopfzeile + Inhalte), sonst nur die Inhalte. */
type Row = { type: 'header'; key: string; section: ContentSection } | { type: 'item'; key: string; item: ContentItem }
const filtering = computed(() => !!search.value.trim() || filter.value !== 'all' || origin.value !== 'all' || groupFilter.value !== 'all')
const rows = computed<Row[]>(() => {
  if (!org.value.groups.length) return visible.value.map((item): Row => ({ type: 'item', key: keyOf(item), item }))
  const out: Row[] = []
  // Beim Suchen sind auch eingeklappte Gruppen offen.
  const searching = !!search.value.trim()
  for (const section of buildSections(visible.value, items.value, org.value, filtering.value)) {
    out.push({ type: 'header', key: `group:${section.group?.id ?? 'none'}`, section })
    if (!section.group?.collapsed || searching) for (const item of section.items) out.push({ type: 'item', key: keyOf(item), item })
  }
  return out
})
const originCounts = computed(() => {
  const pack = items.value.filter(isFromPack).length
  return { pack, manual: items.value.length - pack }
})

// --- Auswahl ---------------------------------------------------------------------
const selectedItems = computed(() => items.value.filter((i) => selected.value.has(keyOf(i))))
const allVisibleSelected = computed(() => visible.value.length > 0 && visible.value.every((i) => selected.value.has(keyOf(i))))
const someVisibleSelected = computed(() => visible.value.some((i) => selected.value.has(keyOf(i))))
function toggleAll() {
  const next = new Set(selected.value)
  if (allVisibleSelected.value) visible.value.forEach((i) => next.delete(keyOf(i)))
  else visible.value.forEach((i) => next.add(keyOf(i)))
  selected.value = next
}
function toggleOne(item: ContentItem) {
  const next = new Set(selected.value)
  if (!next.delete(keyOf(item))) next.add(keyOf(item))
  selected.value = next
}
const selectedUpdates = computed(() => selectedItems.value.map(updateFor).filter((u): u is ContentUpdate => !!u))

// --- Gruppen ----------------------------------------------------------------------
const targetsOf = (list: ContentItem[]) => list.map((item) => ({ kind: item.kind, fileName: item.fileName }))

async function reloadOrg() {
  try {
    org.value = await backend.contentOrganization(props.instance.id)
  } catch (e) {
    toasts.error(e)
  }
}

async function saveGroup(name: string, color: GroupColor) {
  const dialog = groupDialog.value
  groupDialog.value = null
  if (!dialog) return
  try {
    if (dialog.group) {
      await backend.updateContentGroup(props.instance.id, dialog.group.id, { name, color })
    } else {
      const created = await backend.createContentGroup(props.instance.id, name, color)
      if (dialog.assign.length) await backend.assignContentGroup(props.instance.id, targetsOf(dialog.assign), created.id)
      toasts.ok(t('contentGroups.toasts.created', { name: created.name }))
    }
  } catch (e) {
    toasts.error(e)
  }
  await reloadOrg()
}

async function assignTo(list: ContentItem[], groupId: string | null) {
  assignMenu.value = false
  menuFor.value = null
  if (!list.length || list.every((i) => (groupOf(i)?.id ?? null) === groupId)) return
  try {
    const n = await backend.assignContentGroup(props.instance.id, targetsOf(list), groupId)
    const name = groupId ? (orgIndex.value.groups.get(groupId)?.name ?? '') : ''
    toasts.ok(groupId ? t('contentGroups.toasts.moved', { name, n }, n) : t('contentGroups.toasts.removed', n))
  } catch (e) {
    toasts.error(e)
  }
  await reloadOrg()
}

async function toggleCollapse(group: ContentGroup) {
  group.collapsed = !group.collapsed
  try {
    await backend.updateContentGroup(props.instance.id, group.id, { collapsed: group.collapsed })
  } catch (e) {
    toasts.error(e)
    await reloadOrg()
  }
}

async function confirmDeleteGroup() {
  const group = groupToDelete.value
  groupToDelete.value = null
  if (!group) return
  try {
    await backend.deleteContentGroup(props.instance.id, group.id)
  } catch (e) {
    toasts.error(e)
  }
  await reloadOrg()
}

/** Ganze Gruppe schalten: alle an → alle aus, sonst alle an. */
function toggleGroup(section: ContentSection) {
  if (!section.all.length) return
  void bulk(groupToggleState(section.all) === 'on' ? 'disable' : 'enable', section.all)
}

// Ziehen in eine Gruppe – mit Zeiger-Ereignissen, weil das Fenster Datei-Drops
// selbst abfängt (HTML5-Drag & Drop geht unter Windows dann nicht).
const drag = ref<{ items: ContentItem[]; x: number; y: number; over: string | null } | null>(null)
function startDrag(e: PointerEvent, item: ContentItem) {
  if (e.button !== 0 || !org.value.groups.length) return
  e.preventDefault()
  const list = selected.value.has(keyOf(item)) ? selectedItems.value : [item]
  drag.value = { items: list, x: e.clientX, y: e.clientY, over: null }
  window.addEventListener('pointermove', onDragMove)
  window.addEventListener('pointerup', onDragEnd)
  window.addEventListener('keydown', onDragKey)
}
function onDragMove(e: PointerEvent) {
  if (!drag.value) return
  const target = (document.elementFromPoint(e.clientX, e.clientY) as HTMLElement | null)?.closest<HTMLElement>('[data-group-drop]')
  drag.value = { ...drag.value, x: e.clientX, y: e.clientY, over: target?.dataset.groupDrop ?? null }
}
function stopDrag() {
  window.removeEventListener('pointermove', onDragMove)
  window.removeEventListener('pointerup', onDragEnd)
  window.removeEventListener('keydown', onDragKey)
  drag.value = null
}
function onDragEnd() {
  const d = drag.value
  stopDrag()
  if (d?.over) void assignTo(d.items, d.over === 'none' ? null : d.over)
}
function onDragKey(e: KeyboardEvent) {
  if (e.key === 'Escape') stopDrag()
}
onBeforeUnmount(stopDrag)
const dragTargetName = computed(() => {
  const over = drag.value?.over
  if (!over) return null
  return over === 'none' ? t('contentGroups.header.ungrouped') : (orgIndex.value.groups.get(over)?.name ?? null)
})

// --- Aktionen --------------------------------------------------------------------
async function toggle(item: ContentItem) {
  try {
    await backend.setContentEnabled(props.instance.id, item.kind, item.fileName, !item.enabled)
    item.enabled = !item.enabled
  } catch (e) {
    toasts.error(e)
    load(true)
  }
}

async function bulk(action: BulkAction, targets: ContentItem[]) {
  localBulk.value = action
  try {
    const r = await backend.bulkContent(
      props.instance.id,
      action,
      targets.map((item) => ({ kind: item.kind, fileName: item.fileName })),
    )
    if (r.failed) toasts.error(t(`content.bulk.partial.${action}`, { changed: r.changed, failed: r.failed }))
    else toasts.ok(t(`content.bulk.done.${action}`, r.changed))
    if (action === 'delete') selected.value = new Set()
  } catch (e) {
    toasts.error(e)
  } finally {
    localBulk.value = null
    load(true)
  }
}

function applyUpdates(list: ContentUpdate[]) {
  const instance = props.instance
  // „Erneut versuchen“ nimmt nur, was noch fehlt.
  let remaining = [...list]
  const total = list.length
  tasks.run(
    {
      key: updatesKey.value,
      kind: 'content-update',
      title: instance.name,
      stage: t('content.updates.stage', total),
      instanceId: instance.id,
      cancellable: true,
      doneText: t('content.updates.done', total),
    },
    async (ctx) => {
      const failed: ContentUpdate[] = []
      const count = remaining.length
      for (const [i, u] of remaining.entries()) {
        if (ctx.cancelled()) throw cancelledError()
        ctx.update({ tag: u.fileName })
        ctx.progress((i / count) * 100, `${u.fileName} → ${u.versionNumber}`)
        try {
          await backend.applyContentUpdate(instance.id, u)
          updates.value = (updates.value ?? []).filter((x) => !(x.kind === u.kind && x.fileName === u.fileName))
        } catch {
          failed.push(u)
        }
      }
      remaining = failed
      ctx.update({ tag: null })
      if (failed.length) throw new BackendError('partial', t('content.updates.partial', { failed: failed.length, total: count }, failed.length))
    },
  )
}

async function switchVersion(item: ContentItem, version: ModrinthVersion) {
  switching.value = null
  changelogFor.value = null
  if (!item.source) return
  const result = await installContentTask({
    instance: props.instance,
    projectId: item.source.projectId,
    title: titleOf(item),
    iconUrl: item.iconUrl ?? null,
    kind: item.kind,
    version,
    replace: item.fileName,
    platform: sourcePlatform(item.source),
  })
  if (result.ok) updates.value = (updates.value ?? []).filter((u) => !(u.kind === item.kind && u.fileName === item.fileName))
}

async function confirmDelete() {
  const list = toDelete.value
  toDelete.value = null
  if (!list?.length) return
  if (list.length === 1) {
    try {
      await backend.deleteContent(props.instance.id, list[0]!.kind, list[0]!.fileName)
      items.value = items.value.filter((i) => keyOf(i) !== keyOf(list[0]!))
    } catch (e) {
      toasts.error(e)
    }
  } else {
    await bulk('delete', list)
  }
}

// --- Dateien hinzufügen ------------------------------------------------------------
function reportUploads(results: UploadResult[]) {
  const ok = results.filter((r) => !r.error)
  if (ok.length) toasts.ok(ok.length === 1 ? t('content.toasts.fileAdded', { name: ok[0]!.fileName }) : t('content.toasts.filesAdded', ok.length))
  for (const r of results.filter((r) => r.error)) toasts.error(`${r.fileName}: ${r.errorInfo ? userErrorText(r.errorInfo) : r.error}`)
  if (ok.length) load(true)
}

async function pickFiles() {
  try {
    const results = await backend.pickContentFiles(props.instance.id)
    if (results) reportUploads(results)
  } catch (e) {
    toasts.error(e)
  }
}

let unlisten: UnlistenFn | null = null
onMounted(async () => {
  if (!isTauri()) return
  unlisten = await listen<DropEvent>('file-drop', async ({ payload }) => {
    if (payload.type === 'enter') dragging.value = true
    else if (payload.type === 'leave') dragging.value = false
    else {
      dragging.value = false
      try {
        reportUploads(await backend.addDroppedFiles(props.instance.id, payload.token))
      } catch (e) {
        toasts.error(e)
      }
    }
  })
})
onBeforeUnmount(() => unlisten?.())

// --- Menü ------------------------------------------------------------------------
function projectLink(item: ContentItem) {
  return item.source?.projectId ? projectRoute(sourcePlatform(item.source), item.source.projectId, props.instance.id) : null
}
function closeMenu(e: MouseEvent) {
  const target = e.target as HTMLElement | null
  if (!target?.closest('[data-row-menu]')) menuFor.value = null
  if (!target?.closest('[data-group-menu]')) groupMenuFor.value = null
  if (!target?.closest('[data-assign-menu]')) assignMenu.value = false
}
onMounted(() => document.addEventListener('mousedown', closeMenu))
onBeforeUnmount(() => document.removeEventListener('mousedown', closeMenu))

const browseKind = computed(() => (filter.value === 'all' ? (isVanilla.value && props.instance.overrides.boost === false ? 'resourcepack' : 'mod') : filter.value))
const pendingUpdates = computed(() => updates.value ?? [])
</script>

<template>
  <div class="relative flex min-h-0 flex-1 flex-col">
    <!-- Werkzeugleiste -->
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <div class="relative min-w-52 flex-1">
        <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-base-600" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="11" cy="11" r="6" /><path d="m20 20-4.5-4.5" /></svg>
        <input v-model="search" class="field h-9 rounded-full py-0 pl-9 text-sm" maxlength="100" :placeholder="t('content.toolbar.searchPlaceholder', items.length)" spellcheck="false" :aria-label="t('content.toolbar.searchLabel')" />
      </div>
      <select v-model="sort" class="field h-9 w-auto py-0 text-xs" :aria-label="t('content.toolbar.sortLabel')">
        <option value="name">{{ t('content.sort.name') }}</option>
        <option value="enabled">{{ t('content.sort.enabled') }}</option>
        <option value="updates">{{ t('content.sort.updates') }}</option>
        <option value="kind">{{ t('content.sort.kind') }}</option>
      </select>
      <button class="btn-icon" :disabled="checking || loading" :title="t('content.toolbar.refreshTitle')" :aria-label="t('common.actions.refresh')" @click="refresh">
        <svg viewBox="0 0 24 24" class="size-4" :class="{ 'animate-spin': checking }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path :d="icons.sync" /></svg>
      </button>
      <button v-if="pendingUpdates.length" class="btn h-9 bg-lamp-900 py-0 text-xs text-lamp-300 ring-1 ring-lamp-400/40 hover:bg-base-800" :disabled="!!bulkBusy" @click="applyUpdates([...pendingUpdates])">
        {{ bulkBusy === 'update' ? t('content.toolbar.updating') : t('content.toolbar.updateAll', { n: pendingUpdates.length }) }}
      </button>
      <button class="btn btn-ghost h-9 py-0 text-xs" @click="pickFiles">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M14 3H6v18h12V7zM14 3v4h4M12 11v6m-3-3h6" /></svg>
        {{ t('content.toolbar.addFiles') }}
      </button>
      <NuxtLink :to="{ path: '/browse', query: { instance: instance.id, kind: browseKind } }" class="btn btn-primary h-9 py-0 text-xs">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        {{ t('content.toolbar.browse') }}
      </NuxtLink>
    </div>

    <!-- Filter-Chips -->
    <div class="mb-3 flex flex-wrap items-center gap-1.5">
      <button class="filter-chip" :class="{ 'filter-chip-on': filter === 'all' }" @click="filter = 'all'">{{ t('common.labels.all') }} <span class="opacity-60">{{ items.length }}</span></button>
      <button v-for="k in chips" :key="k" class="filter-chip" :class="{ 'filter-chip-on': filter === k }" @click="filter = k">
        {{ contentKindLabel(k) }} <span class="opacity-60">{{ counts[k] }}</span>
      </button>
      <select v-if="org.packKnown" v-model="origin" class="field h-7 w-auto rounded-full py-0 text-xs" :aria-label="t('contentGroups.origin.label')" data-testid="content-origin-filter">
        <option value="all">{{ t('contentGroups.origin.all') }}</option>
        <option value="pack">{{ t('contentGroups.origin.pack') }} ({{ originCounts.pack }})</option>
        <option value="manual">{{ t('contentGroups.origin.manual') }} ({{ originCounts.manual }})</option>
      </select>
      <select v-if="org.groups.length" v-model="groupFilter" class="field h-7 w-auto rounded-full py-0 text-xs" :aria-label="t('contentGroups.filter.label')" data-testid="content-group-filter">
        <option value="all">{{ t('contentGroups.filter.all') }}</option>
        <option value="none">{{ t('contentGroups.header.ungrouped') }}</option>
        <option v-for="g in sortedGroups(org.groups)" :key="g.id" :value="g.id">{{ g.name }}</option>
      </select>
      <button class="ml-auto text-xs text-base-400 hover:text-base-50" data-testid="content-new-group" @click="groupDialog = { group: null, assign: [] }">
        + {{ t('contentGroups.newGroup') }}
      </button>
      <button
        v-if="!bisecting && (counts.mod ?? 0) >= 2"
        class="text-xs text-base-400 hover:text-base-50"
        :title="t('bisect.buttonTitle')"
        data-testid="content-bisect"
        @click="bisect.askStart(instance.id)"
      >
        {{ t('bisect.button') }}
      </button>
      <button class="text-xs text-base-400 hover:text-base-50 disabled:opacity-50" :disabled="presetsBusy" :title="t('presets.apply.buttonTitle')" @click="applyingPreset = true">
        {{ presetsBusy ? t('presets.apply.installing') : t('presets.apply.button') }}
      </button>
    </div>

    <p v-if="error" role="alert" class="card mb-3 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ error }}</p>
    <div v-if="bisecting" class="card mb-3 flex flex-wrap items-center gap-3 border-redstone-600/40 px-4 py-2.5 text-sm" data-testid="bisect-banner">
      <svg viewBox="0 0 24 24" class="size-4 shrink-0 text-redstone-300" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="11" cy="11" r="6" /><path d="m20 20-4.5-4.5" /></svg>
      <span class="min-w-0 flex-1 text-base-200">{{ t('bisect.banner') }}</span>
      <button class="btn btn-ghost px-3 py-1 text-xs" @click="bisect.minimized = false">{{ t('bisect.showWindow') }}</button>
    </div>
    <div v-if="duplicates.length" class="card mb-3 flex flex-wrap items-center gap-3 border-warn/40 px-4 py-2.5 text-sm" data-testid="duplicate-mods-banner">
      <span class="min-w-0 flex-1 text-base-200">{{ t('content.duplicates.banner', { n: duplicates.length }, duplicates.length) }}</span>
      <button class="btn btn-primary px-3 py-1 text-xs" :disabled="fixingDuplicates" data-testid="duplicate-mods-keep" @click="keepNewestDuplicates">
        {{ fixingDuplicates ? t('content.duplicates.fixing') : t('content.duplicates.fix') }}
      </button>
    </div>
    <div v-if="blockedCount" class="card mb-3 flex flex-wrap items-center gap-3 border-lamp-400/40 px-4 py-2.5 text-sm">
      <svg viewBox="0 0 24 24" class="size-4 shrink-0 text-lamp-300" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M12 4v11m0 0-4-4m4 4 4-4M5 20h14" /></svg>
      <span class="min-w-0 flex-1 text-base-200">{{ t('curseforge.blocked.banner', { count: blockedCount }, blockedCount) }}</span>
      <button class="btn btn-ghost px-3 py-1 text-xs" @click="curseforge.openBlocked(instance.id)">{{ t('curseforge.blocked.show') }}</button>
    </div>
    <p v-if="isVanilla && (filter === 'mod' || filter === 'all') && counts.mod" class="mb-3 text-xs text-base-400">
      {{ instance.overrides.boost === false ? t('content.boostOff') : t('content.boostOn') }}
    </p>

    <div v-if="loading && !items.length" class="card divide-y divide-base-800">
      <div v-for="i in 6" :key="i" class="flex items-center gap-3 px-3 py-2.5">
        <div class="skeleton size-4 rounded" />
        <div class="skeleton size-10 rounded-lg" />
        <div class="flex-1 space-y-1.5"><div class="skeleton h-3.5 w-48" /><div class="skeleton h-3 w-72" /></div>
      </div>
    </div>

    <div v-else-if="rows.length" class="card min-h-0 flex-1 overflow-y-auto">
      <!-- Kopfzeile bzw. Sammelaktionen -->
      <div class="sticky top-0 z-10 grid min-h-11 grid-cols-[1.5rem_minmax(0,1fr)_11rem_10.5rem] items-center gap-3 border-b border-base-800 bg-base-900/95 px-3 py-1.5 text-[11px] font-medium text-base-600 backdrop-blur">
        <input
          type="checkbox"
          class="size-4 accent-redstone-500"
          :checked="allVisibleSelected"
          :indeterminate.prop="someVisibleSelected && !allVisibleSelected"
          :aria-label="t('content.selection.selectAll')"
          @change="toggleAll"
        />
        <template v-if="selectedItems.length">
          <div class="col-span-3 flex flex-wrap items-center gap-1.5 text-xs">
            <span class="mr-1 font-semibold text-base-50">{{ t('content.selection.selected', selectedItems.length) }}</span>
            <button class="bulk-btn" :disabled="!!bulkBusy || bisecting" @click="bulk('enable', selectedItems)">{{ t('common.actions.enable') }}</button>
            <button class="bulk-btn" :disabled="!!bulkBusy || bisecting" @click="bulk('disable', selectedItems)">{{ t('common.actions.disable') }}</button>
            <div class="relative" data-assign-menu>
              <button class="bulk-btn" :aria-expanded="assignMenu" data-testid="content-assign-group" @click="assignMenu = !assignMenu">{{ t('contentGroups.assign') }} ▾</button>
              <div v-if="assignMenu" class="menu top-8 left-0 min-w-48" role="menu">
                <button v-for="g in sortedGroups(org.groups)" :key="g.id" class="menu-item flex items-center gap-2" role="menuitem" @click="assignTo(selectedItems, g.id)">
                  <span class="size-2.5 shrink-0 rounded-full" :style="{ background: GROUP_COLOR_HEX[g.color] }" />
                  <span class="truncate">{{ g.name }}</span>
                </button>
                <button class="menu-item" role="menuitem" @click="assignMenu = false; groupDialog = { group: null, assign: [...selectedItems] }">+ {{ t('contentGroups.newGroup') }}</button>
                <button v-if="selectedItems.some((i) => groupOf(i))" class="menu-item" role="menuitem" @click="assignTo(selectedItems, null)">{{ t('contentGroups.menu.removeFromGroup') }}</button>
              </div>
            </div>
            <button v-if="selectedUpdates.length" class="bulk-btn text-lamp-300" :disabled="!!bulkBusy" @click="applyUpdates(selectedUpdates)">{{ t('content.selection.update', { n: selectedUpdates.length }) }}</button>
            <button class="bulk-btn text-redstone-300" :disabled="!!bulkBusy" @click="toDelete = [...selectedItems]">{{ t('common.actions.delete') }}</button>
            <button class="ml-auto text-base-400 hover:text-base-50" @click="selected = new Set()">{{ t('content.selection.clear') }}</button>
          </div>
        </template>
        <template v-else>
          <span>{{ t('content.columns.project') }}</span>
          <span>{{ t('common.labels.version') }}</span>
          <span class="text-right">{{ t('content.columns.actions') }}</span>
        </template>
      </div>

      <ul class="divide-y divide-base-800/70">
        <template v-for="row in rows" :key="row.key">
          <!-- Gruppen-Kopf: klappen, ganze Gruppe schalten, Menü; Ziel beim Ziehen -->
          <li
            v-if="row.type === 'header'"
            class="flex min-h-10 items-center gap-2.5 bg-base-900/60 px-3 py-1.5 transition-colors"
            :class="{ 'bg-redstone-900/30 ring-2 ring-redstone-500/60 ring-inset': drag && drag.over === (row.section.group?.id ?? 'none') }"
            :data-group-drop="row.section.group?.id ?? 'none'"
            data-testid="content-group-header"
          >
            <template v-if="row.section.group">
              <button
                class="btn-icon size-6 bg-transparent"
                :aria-expanded="!row.section.group.collapsed"
                :aria-label="row.section.group.collapsed ? t('contentGroups.header.expand', { name: row.section.group.name }) : t('contentGroups.header.collapse', { name: row.section.group.name })"
                @click="toggleCollapse(row.section.group)"
              >
                <svg viewBox="0 0 24 24" class="size-3.5 transition-transform" :class="{ '-rotate-90': row.section.group.collapsed && !search.trim() }" fill="none" stroke="currentColor" stroke-width="2.6"><path d="m6 9 6 6 6-6" /></svg>
              </button>
              <span class="size-2.5 shrink-0 rounded-full" :style="{ background: GROUP_COLOR_HEX[row.section.group.color] }" />
              <span class="min-w-0 truncate text-xs font-semibold text-base-50">{{ row.section.group.name }}</span>
              <span class="shrink-0 text-[11px] text-base-400">{{ t('contentGroups.header.count', { on: row.section.all.filter((i) => i.enabled).length, total: row.section.all.length }) }}</span>
              <div class="ml-auto flex items-center gap-1">
                <button
                  role="switch"
                  :aria-checked="groupToggleState(row.section.all) === 'on' ? 'true' : groupToggleState(row.section.all) === 'mixed' ? 'mixed' : 'false'"
                  :aria-label="t('contentGroups.header.toggle', { name: row.section.group.name })"
                  :title="bisecting ? t('bisect.lockedTitle') : t('contentGroups.header.toggle', { name: row.section.group.name })"
                  class="relative mx-1 h-5 w-9 shrink-0 rounded-full transition-colors disabled:opacity-40"
                  :class="{ 'bg-redstone-500': groupToggleState(row.section.all) === 'on', 'bg-redstone-500/45': groupToggleState(row.section.all) === 'mixed', 'bg-base-700': groupToggleState(row.section.all) === 'off' }"
                  :disabled="!row.section.all.length || !!bulkBusy || bisecting"
                  data-testid="content-group-toggle"
                  @click="toggleGroup(row.section)"
                >
                  <span
                    class="absolute top-0.5 left-0.5 size-4 rounded-full bg-white shadow transition-transform"
                    :class="{ 'translate-x-4': groupToggleState(row.section.all) === 'on', 'translate-x-2': groupToggleState(row.section.all) === 'mixed' }"
                  />
                </button>
                <div class="relative" data-group-menu>
                  <button
                    class="btn-icon size-7 bg-transparent"
                    :aria-label="t('contentGroups.header.menu', { name: row.section.group.name })"
                    :aria-expanded="groupMenuFor === row.section.group.id"
                    @click="groupMenuFor = groupMenuFor === row.section.group.id ? null : row.section.group.id"
                  >
                    <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><circle cx="12" cy="5.5" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="12" cy="18.5" r="1.7" /></svg>
                  </button>
                  <div v-if="groupMenuFor === row.section.group.id" class="menu top-8 right-0" role="menu">
                    <button class="menu-item" role="menuitem" @click="groupMenuFor = null; groupDialog = { group: row.section.group, assign: [] }">{{ t('contentGroups.menu.edit') }}</button>
                    <button class="menu-item text-redstone-300" role="menuitem" @click="groupMenuFor = null; groupToDelete = row.section.group">{{ t('contentGroups.menu.delete') }}</button>
                  </div>
                </div>
              </div>
            </template>
            <template v-else>
              <span class="text-xs font-semibold text-base-400">{{ t('contentGroups.header.ungrouped') }}</span>
              <span class="text-[11px] text-base-600">{{ row.section.all.length }}</span>
            </template>
          </li>
          <li
            v-else
          class="group grid grid-cols-[1.5rem_minmax(0,1fr)_11rem_10.5rem] items-center gap-3 px-3 py-2 transition-colors hover:bg-base-850"
          :class="{ 'bg-redstone-900/15': selected.has(keyOf(row.item)), 'opacity-40': drag?.items.includes(row.item) }"
          >
          <input type="checkbox" class="size-4 accent-redstone-500" :checked="selected.has(keyOf(row.item))" :aria-label="t('content.row.select', { name: titleOf(row.item) })" @change="toggleOne(row.item)" />

          <div class="flex min-w-0 items-center gap-3" :class="{ 'opacity-55': !row.item.enabled }">
            <button
              v-if="org.groups.length"
              class="-mr-1.5 -ml-1 shrink-0 cursor-grab touch-none text-base-600 opacity-0 group-hover:opacity-100 hover:text-base-50 focus-visible:opacity-100"
              :aria-label="t('contentGroups.dragHandle', { name: titleOf(row.item) })"
              :title="t('contentGroups.dragHandle', { name: titleOf(row.item) })"
              @pointerdown="startDrag($event, row.item)"
            >
              <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><circle cx="9" cy="6" r="1.5" /><circle cx="15" cy="6" r="1.5" /><circle cx="9" cy="12" r="1.5" /><circle cx="15" cy="12" r="1.5" /><circle cx="9" cy="18" r="1.5" /><circle cx="15" cy="18" r="1.5" /></svg>
            </button>
            <ModIcon :src="row.item.iconUrl" :name="titleOf(row.item)" :size="40" :class="{ grayscale: !row.item.enabled }" />
            <div class="min-w-0">
              <div class="flex min-w-0 items-center gap-1.5">
                <NuxtLink v-if="projectLink(row.item)" :to="projectLink(row.item)!" class="block truncate text-sm font-semibold hover:text-redstone-300">{{ titleOf(row.item) }}</NuxtLink>
                <p v-else class="truncate text-sm font-semibold">{{ titleOf(row.item) }}</p>
                <span
                  v-if="org.packKnown"
                  class="badge shrink-0 text-[10px]"
                  :class="isFromPack(row.item) ? 'bg-base-800 text-base-400' : 'bg-lamp-900 text-lamp-300 ring-1 ring-lamp-400/30'"
                  :title="isFromPack(row.item) ? t('contentGroups.origin.packTitle') : t('contentGroups.origin.manualTitle')"
                >
                  {{ isFromPack(row.item) ? t('contentGroups.origin.packBadge') : t('contentGroups.origin.manualBadge') }}
                </span>
              </div>
              <p class="truncate text-xs text-base-400">
                <template v-if="row.item.author">{{ t('content.row.by', { author: row.item.author }) }}</template>
                <template v-else>{{ t('content.row.unknownAuthor') }}</template>
                <template v-if="filter === 'all'"><span class="text-base-600"> · </span>{{ contentKindLabel(row.item.kind) }}</template>
                <template v-if="sourcePlatform(row.item.source) === 'curseforge' && row.item.source"><span class="text-base-600"> · </span>{{ t('content.row.viaCurseForge') }}</template>
              </p>
            </div>
          </div>

          <div class="min-w-0">
            <div class="flex items-center gap-1.5">
              <span class="truncate font-mono text-xs text-base-200" :title="displayVersion(row.item) ?? ''">{{ displayVersion(row.item) ?? '–' }}</span>
              <button
                v-if="updateFor(row.item) && !isBusy(row.item)"
                class="badge shrink-0 bg-lamp-900 text-lamp-300 ring-1 ring-lamp-400/30 hover:bg-lamp-400 hover:text-base-950"
                :title="
                  updateFor(row.item)!.compatWith
                    ? t('content.row.compatTitle', { version: updateFor(row.item)!.versionNumber, other: updateFor(row.item)!.compatWith! })
                    : t('content.row.updateTitle', { version: updateFor(row.item)!.versionNumber })
                "
                @click="row.item.source && !updateFor(row.item)!.compatWith ? (changelogFor = row.item) : applyUpdates([updateFor(row.item)!])"
              >
                <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 19V5m0 0-6 6m6-6 6 6" /></svg>
                {{ updateFor(row.item)!.compatWith ? t('content.row.compatBadge') : t('content.row.updateBadge') }}
              </button>
            </div>
            <span v-if="isBusy(row.item)" class="mt-1 block w-24"><RedstoneWire :percent="60" :segments="8" /></span>
            <p v-else class="truncate text-[11px] text-base-600" :title="row.item.fileName">{{ row.item.fileName }}</p>
          </div>

          <div class="flex items-center justify-end gap-1">
            <button
              class="btn-icon size-8 bg-transparent opacity-70 group-hover:opacity-100 disabled:opacity-25"
              :disabled="!row.item.source"
              :title="row.item.source ? t('content.row.switchVersion') : t('content.row.onlyModrinth')"
              :aria-label="t('content.row.switchVersionOf', { name: titleOf(row.item) })"
              @click="switching = row.item"
            >
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 8h13m0 0-4-4m4 4-4 4M20 16H7m0 0 4-4m-4 4 4 4" /></svg>
            </button>
            <button
              role="switch"
              :aria-checked="row.item.enabled"
              :aria-label="t(row.item.enabled ? 'content.row.disable' : 'content.row.enable', { name: titleOf(row.item) })"
              class="relative mx-1 h-5 w-9 shrink-0 rounded-full transition-colors disabled:opacity-40"
              :class="row.item.enabled ? 'bg-redstone-500' : 'bg-base-700'"
              :disabled="bisecting"
              :title="bisecting ? t('bisect.lockedTitle') : undefined"
              @click="toggle(row.item)"
            >
              <span class="absolute top-0.5 left-0.5 size-4 rounded-full bg-white shadow transition-transform" :class="{ 'translate-x-4': row.item.enabled }" />
            </button>
            <button class="btn-icon size-8 bg-transparent opacity-70 group-hover:opacity-100 hover:text-redstone-300" :aria-label="t('content.row.delete', { name: titleOf(row.item) })" :title="t('common.actions.delete')" @click="toDelete = [row.item]">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
            </button>
            <div class="relative" data-row-menu>
              <button class="btn-icon size-8 bg-transparent opacity-70 group-hover:opacity-100" :aria-label="t('content.row.moreActions', { name: titleOf(row.item) })" :aria-expanded="menuFor === keyOf(row.item)" @click="menuFor = menuFor === keyOf(row.item) ? null : keyOf(row.item)">
                <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><circle cx="12" cy="5.5" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="12" cy="18.5" r="1.7" /></svg>
              </button>
              <div v-if="menuFor === keyOf(row.item)" class="menu top-9 right-0" role="menu">
                <button v-if="updateFor(row.item)" class="menu-item text-lamp-300" role="menuitem" @click="menuFor = null; applyUpdates([updateFor(row.item)!])">
                  {{ t('content.menu.updateTo', { version: updateFor(row.item)!.versionNumber }) }}
                </button>
                <button v-if="row.item.source" class="menu-item" role="menuitem" @click="menuFor = null; changelogFor = row.item">{{ t('content.menu.changelog') }}</button>
                <NuxtLink v-if="projectLink(row.item)" :to="projectLink(row.item)!" class="menu-item" role="menuitem">{{ t('content.menu.projectPage') }}</NuxtLink>
                <p v-if="!row.item.source" class="px-2.5 py-1.5 text-xs text-base-400">{{ t('content.menu.notModrinth') }}</p>
                <p class="border-t border-base-800 px-2.5 pt-1.5 pb-0.5 text-[11px] text-base-600">{{ t('contentGroups.menu.moveTo') }}</p>
                <button
                  v-for="g in sortedGroups(org.groups)"
                  :key="g.id"
                  class="menu-item flex items-center gap-2 disabled:opacity-50"
                  role="menuitem"
                  :disabled="groupOf(row.item)?.id === g.id"
                  @click="assignTo([row.item], g.id)"
                >
                  <span class="size-2.5 shrink-0 rounded-full" :style="{ background: GROUP_COLOR_HEX[g.color] }" />
                  <span class="truncate">{{ g.name }}</span>
                </button>
                <button class="menu-item" role="menuitem" @click="menuFor = null; groupDialog = { group: null, assign: [row.item] }">+ {{ t('contentGroups.newGroup') }}</button>
                <button v-if="groupOf(row.item)" class="menu-item" role="menuitem" @click="assignTo([row.item], null)">{{ t('contentGroups.menu.removeFromGroup') }}</button>
              </div>
            </div>
          </div>
          </li>
        </template>
      </ul>
    </div>

    <RedstoneEmpty
      v-else-if="!loading"
      :compact="items.length > 0"
      :seed="0x55"
      :title="items.length ? t('content.empty.noMatchTitle') : t('content.empty.title')"
      :text="items.length ? t('content.empty.noMatchText') : t('content.empty.text')"
    >
      <template v-if="!items.length">
        <NuxtLink :to="{ path: '/browse', query: { instance: instance.id, kind: browseKind } }" class="btn btn-primary">{{ t('content.toolbar.browse') }}</NuxtLink>
        <button class="btn btn-ghost" @click="pickFiles">{{ t('content.toolbar.addFiles') }}</button>
      </template>
    </RedstoneEmpty>

    <!-- Drag & Drop -->
    <Transition name="toast">
      <div v-if="dragging" class="pointer-events-none fixed inset-0 z-40 grid place-items-center bg-black/55 backdrop-blur-sm">
        <div class="rounded-2xl border-2 border-dashed border-redstone-400 bg-base-900/90 px-12 py-10 text-center">
          <svg viewBox="0 0 24 24" class="mx-auto size-10 text-redstone-400" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 3v12m0 0-4-4m4 4 4-4M4 17v3h16v-3" /></svg>
          <p class="display mt-3 text-2xl">{{ t('content.drop.title') }}</p>
          <p class="mt-1 text-sm text-base-400">{{ t('content.drop.hint') }}</p>
        </div>
      </div>
    </Transition>

    <VersionPickerDialog
      v-if="switching?.source"
      :instance="instance"
      :project-id="switching.source.projectId"
      :title="titleOf(switching)"
      :kind="switching.kind"
      :current-version-id="switching.source.versionId"
      :platform="sourcePlatform(switching.source)"
      @close="switching = null"
      @pick="switchVersion(switching!, $event)"
    />

    <ChangelogDialog v-if="changelogFor?.source" :instance="instance" :item="changelogFor" @close="changelogFor = null" @install="switchVersion(changelogFor!, $event)" />

    <ApplyPresetDialog v-if="applyingPreset" :instance="instance" @close="applyingPreset = false" />
    <ContentGroupDialog v-if="groupDialog" :group="groupDialog.group" :count="groupDialog.assign.length" @close="groupDialog = null" @save="saveGroup" />
    <BaseDialog v-if="groupToDelete" :title="t('contentGroups.deleteDialog.title')" @close="groupToDelete = null">
      <p class="text-sm text-base-200">{{ t('contentGroups.deleteDialog.text', { name: groupToDelete.name }) }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="groupToDelete = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmDeleteGroup">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>
    <!-- Beim Ziehen: was wohin -->
    <div
      v-if="drag"
      class="pointer-events-none fixed z-50 rounded-full bg-base-800 px-3 py-1 text-xs font-medium text-base-50 shadow-lg ring-1 ring-base-700"
      :style="{ left: `${drag.x + 14}px`, top: `${drag.y + 10}px` }"
    >
      {{ dragTargetName ? t('contentGroups.dragInto', { n: drag.items.length, name: dragTargetName }, drag.items.length) : t('contentGroups.dragging', drag.items.length) }}
    </div>
    <BaseDialog
      v-if="toDelete"
      :title="toDelete.length === 1 ? t('content.deleteDialog.titleOne') : t('content.deleteDialog.titleMany', toDelete.length)"
      @close="toDelete = null"
    >
      <i18n-t v-if="toDelete.length === 1" keypath="content.deleteDialog.textOne" tag="p" scope="global" class="text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ titleOf(toDelete[0]!) }}</strong></template>
      </i18n-t>
      <p v-else class="text-sm text-base-200">{{ t('content.deleteDialog.textMany', toDelete.length) }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmDelete">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.filter-chip {
  @apply rounded-full bg-base-900 px-3 py-1 text-xs font-medium text-base-400 ring-1 ring-base-800 transition-colors hover:text-base-50;
}
.filter-chip-on {
  @apply bg-redstone-500 text-white ring-redstone-500 hover:text-white;
}
.bulk-btn {
  @apply rounded-md bg-base-800 px-2.5 py-1 font-medium text-base-200 transition-colors hover:bg-base-700 hover:text-base-50 disabled:opacity-50;
}
</style>
