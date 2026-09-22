<script setup lang="ts">
import { isTauri } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import type { BulkAction, ContentItem, ContentKind, ContentUpdate, DropEvent, Instance, ModrinthVersion, UploadResult } from '~/types'

// Inhalte einer Instanz als EINE Tabelle wie in der Modrinth App: Filter-Chips,
// Suche, Sortierung, Mehrfachauswahl mit Sammelaktionen, Dateien per Dialog
// oder Drag & Drop hinzufügen.
const props = defineProps<{ instance: Instance }>()

const items = ref<ContentItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const filter = ref<'all' | ContentKind>('all')
const search = ref('')
const sort = ref<'name' | 'enabled' | 'updates' | 'kind'>('name')
const selected = ref<Set<string>>(new Set())
const toasts = useToasts()
const updates = ref<ContentUpdate[] | null>(null)
const checking = ref(false)
const bulkBusy = ref<string | null>(null)
const packBusy = ref(false)
const busyFile = ref<string | null>(null)
const menuFor = ref<string | null>(null)
const switching = ref<ContentItem | null>(null)
const changelogFor = ref<ContentItem | null>(null)
const toDelete = ref<ContentItem[] | null>(null)
const dragging = ref(false)

const keyOf = (i: { kind: ContentKind; fileName: string }) => `${i.kind}/${i.fileName}`
const updateFor = (item: ContentItem) => updates.value?.find((u) => u.kind === item.kind && u.fileName === item.fileName)
const isVanilla = computed(() => props.instance.loader.kind === 'vanilla')
const displayVersion = (item: ContentItem) => item.version ?? item.source?.versionNumber ?? null
const titleOf = (item: ContentItem) => item.title ?? item.fileName

// --- Laden ---------------------------------------------------------------------
async function load(quiet = false) {
  if (!quiet) loading.value = true
  error.value = null
  try {
    const lists = await Promise.all(contentKinds.map((k) => backend.listContent(props.instance.id, k)))
    items.value = lists.flat()
    const keys = new Set(items.value.map(keyOf))
    selected.value = new Set([...selected.value].filter((k) => keys.has(k)))
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function checkUpdates(silent = false) {
  checking.value = true
  try {
    updates.value = await backend.checkContentUpdates(props.instance.id)
    if (!silent && !updates.value.length) toasts.info('Alles ist aktuell')
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
  const list = items.value.filter(
    (i) =>
      (filter.value === 'all' || i.kind === filter.value) &&
      (!needle || `${i.title ?? ''} ${i.fileName} ${i.author ?? ''}`.toLowerCase().includes(needle)),
  )
  const byName = (a: ContentItem, b: ContentItem) => titleOf(a).localeCompare(titleOf(b), 'de', { sensitivity: 'base' })
  const rank: Record<typeof sort.value, (i: ContentItem) => number> = {
    name: () => 0,
    enabled: (i) => (i.enabled ? 0 : 1),
    updates: (i) => (updateFor(i) ? 0 : 1),
    kind: (i) => contentKinds.indexOf(i.kind),
  }
  return list.sort((a, b) => rank[sort.value](a) - rank[sort.value](b) || byName(a, b))
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
  bulkBusy.value = action
  try {
    const r = await backend.bulkContent(
      props.instance.id,
      action,
      targets.map((t) => ({ kind: t.kind, fileName: t.fileName })),
    )
    const verb = { enable: 'aktiviert', disable: 'deaktiviert', delete: 'gelöscht' }[action]
    if (r.failed) toasts.error(`${r.changed} ${verb}, ${r.failed} fehlgeschlagen`)
    else toasts.ok(`${r.changed} ${r.changed === 1 ? 'Inhalt' : 'Inhalte'} ${verb}`)
    if (action === 'delete') selected.value = new Set()
  } catch (e) {
    toasts.error(e)
  } finally {
    bulkBusy.value = null
    load(true)
  }
}

async function applyUpdates(list: ContentUpdate[]) {
  bulkBusy.value = 'update'
  let failed = 0
  for (const u of list) {
    busyFile.value = u.fileName
    try {
      await backend.applyContentUpdate(props.instance.id, u)
      updates.value = (updates.value ?? []).filter((x) => x !== u)
    } catch {
      failed++
    }
  }
  busyFile.value = null
  bulkBusy.value = null
  if (failed) toasts.error(`${failed} von ${list.length} Updates sind fehlgeschlagen.`)
  else toasts.ok(`${list.length} ${list.length === 1 ? 'Update' : 'Updates'} installiert`)
  load(true)
}

async function switchVersion(item: ContentItem, version: ModrinthVersion) {
  switching.value = null
  changelogFor.value = null
  if (!item.source) return
  busyFile.value = item.fileName
  try {
    await backend.applyContentUpdate(props.instance.id, {
      kind: item.kind,
      fileName: item.fileName,
      projectId: item.source.projectId,
      versionId: version.id,
      versionNumber: version.versionNumber,
    })
    updates.value = (updates.value ?? []).filter((u) => !(u.kind === item.kind && u.fileName === item.fileName))
    toasts.ok(`${titleOf(item)}: Version ${version.versionNumber} installiert`)
  } catch (e) {
    toasts.error(e)
  } finally {
    busyFile.value = null
    load(true)
  }
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

async function installPerformancePack() {
  packBusy.value = true
  try {
    const files = await backend.installPerformancePack(props.instance.id)
    toasts.ok(`Performance-Paket installiert (${files.length} Dateien)`)
    load(true)
  } catch (e) {
    toasts.error(e)
  } finally {
    packBusy.value = false
  }
}

// --- Dateien hinzufügen ------------------------------------------------------------
function reportUploads(results: UploadResult[]) {
  const ok = results.filter((r) => !r.error)
  if (ok.length) toasts.ok(ok.length === 1 ? `${ok[0]!.fileName} hinzugefügt` : `${ok.length} Dateien hinzugefügt`)
  for (const r of results.filter((r) => r.error)) toasts.error(`${r.fileName}: ${r.error}`)
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
  return item.source ? { path: `/project/${item.source.projectId}`, query: { instance: props.instance.id } } : null
}
function closeMenu(e: MouseEvent) {
  if (!(e.target as HTMLElement | null)?.closest('[data-row-menu]')) menuFor.value = null
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
        <input v-model="search" class="field h-9 rounded-full py-0 pl-9 text-sm" maxlength="100" :placeholder="`${items.length} Projekte durchsuchen …`" spellcheck="false" aria-label="Projekte durchsuchen" />
      </div>
      <select v-model="sort" class="field h-9 w-auto py-0 text-xs" aria-label="Sortieren">
        <option value="name">Nach Name</option>
        <option value="enabled">Aktive zuerst</option>
        <option value="updates">Updates zuerst</option>
        <option value="kind">Nach Art</option>
      </select>
      <button class="btn-icon" :disabled="checking || loading" title="Aktualisieren (Liste neu laden und nach Updates suchen)" aria-label="Aktualisieren" @click="refresh">
        <svg viewBox="0 0 24 24" class="size-4" :class="{ 'animate-spin': checking }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path :d="icons.sync" /></svg>
      </button>
      <button v-if="pendingUpdates.length" class="btn h-9 bg-lamp-900 py-0 text-xs text-lamp-300 ring-1 ring-lamp-400/40 hover:bg-base-800" :disabled="!!bulkBusy" @click="applyUpdates([...pendingUpdates])">
        {{ bulkBusy === 'update' ? 'Aktualisiere …' : `Alle aktualisieren (${pendingUpdates.length})` }}
      </button>
      <button class="btn btn-ghost h-9 py-0 text-xs" @click="pickFiles">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M14 3H6v18h12V7zM14 3v4h4M12 11v6m-3-3h6" /></svg>
        Dateien hinzufügen
      </button>
      <NuxtLink :to="{ path: '/browse', query: { instance: instance.id, kind: browseKind } }" class="btn btn-primary h-9 py-0 text-xs">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        Inhalte durchsuchen
      </NuxtLink>
    </div>

    <!-- Filter-Chips -->
    <div class="mb-3 flex flex-wrap items-center gap-1.5">
      <button class="filter-chip" :class="{ 'filter-chip-on': filter === 'all' }" @click="filter = 'all'">Alle <span class="opacity-60">{{ items.length }}</span></button>
      <button v-for="k in chips" :key="k" class="filter-chip" :class="{ 'filter-chip-on': filter === k }" @click="filter = k">
        {{ contentKindLabels[k] }} <span class="opacity-60">{{ counts[k] }}</span>
      </button>
      <button v-if="!isVanilla" class="ml-auto text-xs text-base-400 hover:text-base-50 disabled:opacity-50" :disabled="packBusy" title="Sodium, Lithium & Co. – nur was es für diese Version gibt" @click="installPerformancePack">
        {{ packBusy ? 'Installiere Performance-Paket …' : '+ Performance-Paket' }}
      </button>
    </div>

    <p v-if="error" role="alert" class="card mb-3 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ error }}</p>
    <p v-if="isVanilla && (filter === 'mod' || filter === 'all') && counts.mod" class="mb-3 text-xs text-base-400">
      {{ instance.overrides.boost === false
        ? 'TRS-Optimierung ist aus – diese Instanz startet als echtes Vanilla und lädt keine Mods.'
        : 'TRS-Optimierung: Die Instanz startet mit Fabric, dem TRS Client und Performance-Mods.' }}
    </p>

    <div v-if="loading && !items.length" class="card divide-y divide-base-800">
      <div v-for="i in 6" :key="i" class="flex items-center gap-3 px-3 py-2.5">
        <div class="skeleton size-4 rounded" />
        <div class="skeleton size-10 rounded-lg" />
        <div class="flex-1 space-y-1.5"><div class="skeleton h-3.5 w-48" /><div class="skeleton h-3 w-72" /></div>
      </div>
    </div>

    <div v-else-if="visible.length" class="card min-h-0 flex-1 overflow-y-auto">
      <!-- Kopfzeile bzw. Sammelaktionen -->
      <div class="sticky top-0 z-10 grid min-h-11 grid-cols-[1.5rem_minmax(0,1fr)_11rem_10.5rem] items-center gap-3 border-b border-base-800 bg-base-900/95 px-3 py-1.5 text-[11px] font-medium text-base-600 backdrop-blur">
        <input
          type="checkbox"
          class="size-4 accent-redstone-500"
          :checked="allVisibleSelected"
          :indeterminate.prop="someVisibleSelected && !allVisibleSelected"
          aria-label="Alle auswählen"
          @change="toggleAll"
        />
        <template v-if="selectedItems.length">
          <div class="col-span-3 flex flex-wrap items-center gap-1.5 text-xs">
            <span class="mr-1 font-semibold text-base-50">{{ selectedItems.length }} ausgewählt</span>
            <button class="bulk-btn" :disabled="!!bulkBusy" @click="bulk('enable', selectedItems)">Aktivieren</button>
            <button class="bulk-btn" :disabled="!!bulkBusy" @click="bulk('disable', selectedItems)">Deaktivieren</button>
            <button v-if="selectedUpdates.length" class="bulk-btn text-lamp-300" :disabled="!!bulkBusy" @click="applyUpdates(selectedUpdates)">Aktualisieren ({{ selectedUpdates.length }})</button>
            <button class="bulk-btn text-redstone-300" :disabled="!!bulkBusy" @click="toDelete = [...selectedItems]">Löschen</button>
            <button class="ml-auto text-base-400 hover:text-base-50" @click="selected = new Set()">Auswahl aufheben</button>
          </div>
        </template>
        <template v-else>
          <span>Projekt</span>
          <span>Version</span>
          <span class="text-right">Aktionen</span>
        </template>
      </div>

      <ul class="divide-y divide-base-800/70">
        <li
          v-for="item in visible"
          :key="keyOf(item)"
          class="group grid grid-cols-[1.5rem_minmax(0,1fr)_11rem_10.5rem] items-center gap-3 px-3 py-2 transition-colors hover:bg-base-850"
          :class="{ 'bg-redstone-900/15': selected.has(keyOf(item)) }"
        >
          <input type="checkbox" class="size-4 accent-redstone-500" :checked="selected.has(keyOf(item))" :aria-label="`${titleOf(item)} auswählen`" @change="toggleOne(item)" />

          <div class="flex min-w-0 items-center gap-3" :class="{ 'opacity-55': !item.enabled }">
            <ModIcon :src="item.iconUrl" :name="titleOf(item)" :size="40" :class="{ grayscale: !item.enabled }" />
            <div class="min-w-0">
              <NuxtLink v-if="projectLink(item)" :to="projectLink(item)!" class="block truncate text-sm font-semibold hover:text-redstone-300">{{ titleOf(item) }}</NuxtLink>
              <p v-else class="truncate text-sm font-semibold">{{ titleOf(item) }}</p>
              <p class="truncate text-xs text-base-400">
                <template v-if="item.author">von {{ item.author }}</template>
                <template v-else>Unbekannter Autor</template>
                <template v-if="filter === 'all'"><span class="text-base-600"> · </span>{{ contentKindLabels[item.kind] }}</template>
              </p>
            </div>
          </div>

          <div class="min-w-0">
            <div class="flex items-center gap-1.5">
              <span class="truncate font-mono text-xs text-base-200" :title="displayVersion(item) ?? ''">{{ displayVersion(item) ?? '–' }}</span>
              <button
                v-if="updateFor(item) && busyFile !== item.fileName"
                class="badge shrink-0 bg-lamp-900 text-lamp-300 ring-1 ring-lamp-400/30 hover:bg-lamp-400 hover:text-base-950"
                :title="`Update auf ${updateFor(item)!.versionNumber}`"
                @click="item.source ? (changelogFor = item) : applyUpdates([updateFor(item)!])"
              >
                <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 19V5m0 0-6 6m6-6 6 6" /></svg>
                Update
              </button>
            </div>
            <span v-if="busyFile === item.fileName" class="mt-1 block w-24"><RedstoneWire :percent="60" :segments="8" /></span>
            <p v-else class="truncate text-[11px] text-base-600" :title="item.fileName">{{ item.fileName }}</p>
          </div>

          <div class="flex items-center justify-end gap-1">
            <button
              class="btn-icon size-8 bg-transparent opacity-70 group-hover:opacity-100 disabled:opacity-25"
              :disabled="!item.source"
              :title="item.source ? 'Version wechseln' : 'Nur für Modrinth-Inhalte'"
              :aria-label="`Version von ${titleOf(item)} wechseln`"
              @click="switching = item"
            >
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 8h13m0 0-4-4m4 4-4 4M20 16H7m0 0 4-4m-4 4 4 4" /></svg>
            </button>
            <button
              role="switch"
              :aria-checked="item.enabled"
              :aria-label="`${titleOf(item)} ${item.enabled ? 'deaktivieren' : 'aktivieren'}`"
              class="relative mx-1 h-5 w-9 shrink-0 rounded-full transition-colors"
              :class="item.enabled ? 'bg-redstone-500' : 'bg-base-700'"
              @click="toggle(item)"
            >
              <span class="absolute top-0.5 left-0.5 size-4 rounded-full bg-white shadow transition-transform" :class="{ 'translate-x-4': item.enabled }" />
            </button>
            <button class="btn-icon size-8 bg-transparent opacity-70 group-hover:opacity-100 hover:text-redstone-300" :aria-label="`${titleOf(item)} löschen`" title="Löschen" @click="toDelete = [item]">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
            </button>
            <div class="relative" data-row-menu>
              <button class="btn-icon size-8 bg-transparent opacity-70 group-hover:opacity-100" :aria-label="`Weitere Aktionen für ${titleOf(item)}`" :aria-expanded="menuFor === keyOf(item)" @click="menuFor = menuFor === keyOf(item) ? null : keyOf(item)">
                <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><circle cx="12" cy="5.5" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="12" cy="18.5" r="1.7" /></svg>
              </button>
              <div v-if="menuFor === keyOf(item)" class="menu top-9 right-0" role="menu">
                <button v-if="updateFor(item)" class="menu-item text-lamp-300" role="menuitem" @click="menuFor = null; applyUpdates([updateFor(item)!])">
                  Auf {{ updateFor(item)!.versionNumber }} aktualisieren
                </button>
                <button v-if="item.source" class="menu-item" role="menuitem" @click="menuFor = null; changelogFor = item">Changelog ansehen</button>
                <NuxtLink v-if="projectLink(item)" :to="projectLink(item)!" class="menu-item" role="menuitem">Projektseite</NuxtLink>
                <p v-if="!item.source" class="px-2.5 py-1.5 text-xs text-base-400">Nicht über Modrinth installiert – Versionen und Changelog gibt es nur für Modrinth-Inhalte.</p>
              </div>
            </div>
          </div>
        </li>
      </ul>
    </div>

    <div v-else-if="!loading" class="card flex flex-col items-center px-6 py-14 text-center">
      <div class="mb-4 grid size-14 place-items-center rounded-xl bg-base-800 text-base-400">
        <svg viewBox="0 0 24 24" class="size-7" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 7.5 12 3l8 4.5v9L12 21l-8-4.5z" /><path d="m4 7.5 8 4.5 8-4.5M12 12v9" /></svg>
      </div>
      <h2 class="font-semibold">{{ items.length ? 'Nichts gefunden' : 'Noch keine Inhalte' }}</h2>
      <p class="mx-auto mt-1 max-w-md text-sm text-base-400">
        {{ items.length ? 'Kein Eintrag passt zu Filter oder Suche.' : 'Durchsuche Modrinth, füge Dateien hinzu oder ziehe .jar- und .zip-Dateien einfach ins Fenster.' }}
      </p>
      <div v-if="!items.length" class="mt-5 flex gap-2">
        <NuxtLink :to="{ path: '/browse', query: { instance: instance.id, kind: browseKind } }" class="btn btn-primary">Inhalte durchsuchen</NuxtLink>
        <button class="btn btn-ghost" @click="pickFiles">Dateien hinzufügen</button>
      </div>
    </div>

    <!-- Drag & Drop -->
    <Transition name="toast">
      <div v-if="dragging" class="pointer-events-none fixed inset-0 z-40 grid place-items-center bg-black/55 backdrop-blur-sm">
        <div class="rounded-2xl border-2 border-dashed border-redstone-400 bg-base-900/90 px-12 py-10 text-center">
          <svg viewBox="0 0 24 24" class="mx-auto size-10 text-redstone-400" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 3v12m0 0-4-4m4 4 4-4M4 17v3h16v-3" /></svg>
          <p class="display mt-3 text-2xl">Loslassen zum Hinzufügen</p>
          <p class="mt-1 text-sm text-base-400">.jar = Mod · .zip = Ressourcenpaket, Shader oder Datenpaket</p>
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
      @close="switching = null"
      @pick="switchVersion(switching!, $event)"
    />

    <ChangelogDialog v-if="changelogFor?.source" :instance="instance" :item="changelogFor" @close="changelogFor = null" @install="switchVersion(changelogFor!, $event)" />

    <BaseDialog v-if="toDelete" :title="toDelete.length === 1 ? 'Datei löschen?' : `${toDelete.length} Dateien löschen?`" @close="toDelete = null">
      <p class="text-sm text-base-200">
        <template v-if="toDelete.length === 1"><strong class="text-base-50">{{ titleOf(toDelete[0]!) }}</strong> wird aus dieser Instanz gelöscht.</template>
        <template v-else>{{ toDelete.length }} Inhalte werden aus dieser Instanz gelöscht.</template>
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">Abbrechen</button>
        <button class="btn btn-danger" @click="confirmDelete">Löschen</button>
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
