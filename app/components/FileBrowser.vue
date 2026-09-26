<script setup lang="ts">
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import type { DirListing, DropEvent, FileEntry, ImportReport, Instance } from '~/types'
import type { FileKind, FileSort, FileSortKey } from '~/utils/files'

// Tab „Dateien“: Spielordner der Instanz durchsuchen und verwalten. Alle
// Pfade sind relativ; geprüft wird im Kern (kein Ausbruch aus der Instanz).
const props = defineProps<{ instance: Instance }>()

const toasts = useToasts()
const cwd = ref('')
const listing = ref<DirListing | null>(null)
const loading = ref(false)
const query = ref('')
const selected = ref(new Set<string>())
const anchor = ref<string | null>(null)
const focused = ref<string | null>(null)
const dragging = ref(false)
const busy = ref(false)

// --- Sortierung (pro Gerät gemerkt) --------------------------------------------
const SORT_KEY = 'trs.files.sort'
function loadSort(): FileSort {
  try {
    const raw = JSON.parse(localStorage.getItem(SORT_KEY) ?? 'null') as FileSort | null
    if (raw && ['name', 'size', 'created', 'modified'].includes(raw.key)) return { key: raw.key, desc: !!raw.desc }
  } catch {
    // Kein Speicher – Standard.
  }
  return { key: 'name', desc: false }
}
const sort = ref<FileSort>(loadSort())
function setSort(key: FileSortKey) {
  sort.value = sort.value.key === key ? { key, desc: !sort.value.desc } : { key, desc: key !== 'name' }
  try {
    localStorage.setItem(SORT_KEY, JSON.stringify(sort.value))
  } catch {
    // egal
  }
}
const collator = computed(() => new Intl.Collator(intlLocale(), { numeric: true, sensitivity: 'base' }))

const atRoot = computed(() => cwd.value === '')
const rows = computed(() => sortFiles(filterFiles(listing.value?.entries ?? [], query.value), sort.value, collator.value))
const names = computed(() => rows.value.map((r) => r.name))
const crumbs = computed(() => breadcrumbs(cwd.value))
const selection = computed(() => rows.value.filter((r) => selected.value.has(r.name)))

// --- Laden ---------------------------------------------------------------------
let loadToken = 0
async function load(path = cwd.value, keepSelection = false) {
  const token = ++loadToken
  loading.value = true
  try {
    const result = await backend.listInstanceFiles(props.instance.id, path)
    if (token !== loadToken) return
    listing.value = result
    cwd.value = result.path
    if (!keepSelection) {
      selected.value = new Set()
      anchor.value = null
      focused.value = null
    } else {
      const present = new Set(result.entries.map((e) => e.name))
      selected.value = new Set([...selected.value].filter((n) => present.has(n)))
    }
  } catch (e) {
    if (token !== loadToken) return
    toasts.error(e)
    // Ordner verschwunden → eine Ebene höher.
    if (path) load(parentPath(path))
  } finally {
    if (token === loadToken) loading.value = false
  }
}

function go(path: string) {
  query.value = ''
  load(path)
}

// --- Auswahl & Tastatur ---------------------------------------------------------
function click(entry: FileEntry, e: MouseEvent) {
  selected.value = nextSelection(selected.value, names.value, entry.name, anchor.value, { shift: e.shiftKey, toggle: e.ctrlKey || e.metaKey })
  if (!e.shiftKey) anchor.value = entry.name
  focused.value = entry.name
}

function toggleCheck(entry: FileEntry) {
  selected.value = nextSelection(selected.value, names.value, entry.name, anchor.value, { shift: false, toggle: true })
  anchor.value = entry.name
}

const allChecked = computed(() => rows.value.length > 0 && rows.value.every((r) => selected.value.has(r.name)))
function toggleAll() {
  selected.value = allChecked.value ? new Set() : new Set(names.value)
}

function activate(entry: FileEntry) {
  if (entry.dir) go(joinPath(cwd.value, entry.name))
  else backend.openInstanceFile(props.instance.id, joinPath(cwd.value, entry.name)).catch((e) => toasts.error(e))
}

const list = ref<HTMLElement | null>(null)
function focusRow(name: string) {
  focused.value = name
  nextTick(() => list.value?.querySelector<HTMLElement>(`[data-name="${CSS.escape(name)}"]`)?.focus())
}

function onListKey(e: KeyboardEvent) {
  const current = focused.value ? names.value.indexOf(focused.value) : -1
  const entry = current >= 0 ? rows.value[current] : undefined
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    const next = Math.min(Math.max(current + (e.key === 'ArrowDown' ? 1 : -1), 0), names.value.length - 1)
    const name = names.value[next]
    if (name === undefined) return
    selected.value = nextSelection(selected.value, names.value, name, anchor.value, { shift: e.shiftKey, toggle: false })
    if (!e.shiftKey) anchor.value = name
    focusRow(name)
  } else if (e.key === 'Enter' && entry) {
    e.preventDefault()
    activate(entry)
  } else if (e.key === 'Backspace' && !atRoot.value) {
    e.preventDefault()
    go(parentPath(cwd.value))
  } else if (e.key === 'Delete' && selection.value.length) {
    e.preventDefault()
    confirmTrash.value = true
  } else if (e.key === 'F2' && selection.value.length === 1) {
    e.preventDefault()
    startRename(selection.value[0]!)
  } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
    e.preventDefault()
    selected.value = new Set(names.value)
  } else if (e.key === ' ' && entry) {
    e.preventDefault()
    toggleCheck(entry)
  }
}

// --- Kontextmenü ---------------------------------------------------------------
const menu = ref<{ x: number; y: number; entry: FileEntry | null } | null>(null)
function openMenu(e: MouseEvent, entry: FileEntry | null) {
  if (entry && !selected.value.has(entry.name)) {
    selected.value = new Set([entry.name])
    anchor.value = entry.name
  }
  const box = (e.currentTarget as HTMLElement).closest('.file-browser')?.getBoundingClientRect()
  menu.value = { x: e.clientX - (box?.left ?? 0), y: e.clientY - (box?.top ?? 0), entry }
}
function closeMenu(e: MouseEvent) {
  if (!(e.target as HTMLElement | null)?.closest('[data-file-menu]')) menu.value = null
}

// --- Aktionen ------------------------------------------------------------------
type NameDialog = { mode: 'folder' | 'file' | 'rename'; value: string; entry?: FileEntry }
const nameDialog = ref<NameDialog | null>(null)
const nameError = ref<string | null>(null)
const confirmTrash = ref(false)

function startRename(entry: FileEntry) {
  menu.value = null
  nameError.value = null
  nameDialog.value = { mode: 'rename', value: entry.name, entry }
}
function startCreate(mode: 'folder' | 'file') {
  menu.value = null
  nameError.value = null
  nameDialog.value = { mode, value: '' }
}

async function submitName() {
  const dialog = nameDialog.value
  if (!dialog) return
  const problem = nameProblem(dialog.value)
  if (problem) {
    nameError.value = problem === 'empty' ? t('files.errors.empty') : t('files.errors.invalid')
    return
  }
  busy.value = true
  try {
    const name = dialog.value.trim()
    let created: string
    if (dialog.mode === 'rename' && dialog.entry) created = await backend.renameInstanceFile(props.instance.id, joinPath(cwd.value, dialog.entry.name), name)
    else if (dialog.mode === 'folder') created = await backend.createInstanceFolder(props.instance.id, cwd.value, name)
    else created = await backend.createInstanceFile(props.instance.id, cwd.value, name)
    nameDialog.value = null
    await load(cwd.value)
    const newName = created.split('/').pop() ?? name
    selected.value = new Set([newName])
    anchor.value = newName
    focusRow(newName)
  } catch (e) {
    nameError.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function trashSelected() {
  confirmTrash.value = false
  const paths = selection.value.map((e) => joinPath(cwd.value, e.name))
  if (!paths.length) return
  busy.value = true
  try {
    const count = await backend.trashInstanceFiles(props.instance.id, paths)
    toasts.ok(t('files.trashed', count))
    await load(cwd.value)
  } catch (e) {
    toasts.error(e)
    load(cwd.value, true)
  } finally {
    busy.value = false
  }
}

function openSelected() {
  menu.value = null
  const entry = selection.value[0]
  if (entry) activate(entry)
}
function reveal(entry?: FileEntry) {
  menu.value = null
  const target = entry ?? selection.value[0]
  if (!target) return openCurrentDir()
  backend.revealInstanceFile(props.instance.id, joinPath(cwd.value, target.name)).catch((e) => toasts.error(e))
}
/** Aktuellen Ordner im Explorer/Dateimanager öffnen. */
function openCurrentDir() {
  menu.value = null
  backend.openInstanceFile(props.instance.id, cwd.value).catch((e) => toasts.error(e))
}
async function copyPath() {
  menu.value = null
  const entry = selection.value[0]
  const path = entry ? joinPath(cwd.value, entry.name) : cwd.value
  try {
    await navigator.clipboard.writeText(path)
    toasts.ok(t('files.pathCopied'))
  } catch {
    // Zwischenablage nicht verfügbar.
  }
}

function reportImport(report: ImportReport) {
  if (report.files) toasts.ok(t('files.uploaded', { size: formatBytes(report.bytes) }, report.files))
  for (const skip of report.skipped.slice(0, 3)) toasts.error(t(`files.skipped.${skip.reason}`, { name: skip.name }))
  load(cwd.value)
}

async function upload() {
  menu.value = null
  busy.value = true
  try {
    const report = await backend.pickInstanceUpload(props.instance.id, cwd.value)
    if (report) reportImport(report)
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}

// --- Drag & Drop (Pfade bleiben im Kern, hier nur Marke + Namen) -----------------
let unlisten: UnlistenFn | null = null
onMounted(async () => {
  load('')
  document.addEventListener('mousedown', closeMenu)
  unlisten = await listen<DropEvent>('file-drop', async ({ payload }) => {
    if (payload.type === 'enter') dragging.value = true
    else if (payload.type === 'leave') dragging.value = false
    else {
      dragging.value = false
      busy.value = true
      try {
        reportImport(await backend.importDroppedInstanceFiles(props.instance.id, cwd.value, payload.token))
      } catch (e) {
        toasts.error(e)
      } finally {
        busy.value = false
      }
    }
  })
})
onBeforeUnmount(() => {
  unlisten?.()
  document.removeEventListener('mousedown', closeMenu)
})

// --- Darstellung ---------------------------------------------------------------
const KIND_ICON: Record<FileKind, string> = {
  folder: 'M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z',
  mods: 'M4 8l8-4 8 4v8l-8 4-8-4zM4 8l8 4 8-4M12 12v8',
  config: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1',
  saves: 'M3 17l5-6 4 4 3-3 6 5M3 5h18v14H3z',
  resourcepacks: 'M12 3 3 8l9 5 9-5zM3 13l9 5 9-5M3 17.5 12 22l9-4.5',
  shaderpacks: 'M12 3v2M12 19v2M5 12H3M21 12h-2M6.3 6.3 4.9 4.9M19.1 19.1l-1.4-1.4M6.3 17.7l-1.4 1.4M19.1 4.9l-1.4 1.4M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
  screenshots: 'M4 8h3l1.5-2h7L17 8h3v11H4zM12 16.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z',
  logs: 'M6 3h9l4 4v14H6zM14 3v5h5M9 12h7M9 16h7',
  crash: 'M12 3 2 20h20zM12 9v5M12 17h.01',
  datapacks: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6',
  backups: 'M4 7h16v13H4zM2 4h20v3H2zM10 11h4',
  archive: 'M5 3h14v18H5zM11 3v2h2v2h-2v2h2v2h-2M10 14h4v4h-4z',
  jar: 'M8 3h8v3H8zM6 6h12v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1zM9 11h6',
  image: 'M4 5h16v14H4zM4 15l4.5-4.5 3.5 3.5 3-3L20 16M9 9.5h.01',
  text: 'M6 3h9l4 4v14H6zM14 3v5h5M9 13h6M9 17h4',
  data: 'M8 4c-2 0-2 2-2 4s-2 2-2 4 2 2 2 4 0 4 2 4M16 4c2 0 2 2 2 4s2 2 2 4-2 2-2 4 0 4-2 4',
  audio: 'M9 18V5l11-2v13M9 18a3 3 0 1 1-3-3 3 3 0 0 1 3 3zM20 16a3 3 0 1 1-3-3 3 3 0 0 1 3 3z',
  video: 'M3 6h13v12H3zM16 10l5-3v10l-5-3',
  nbt: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
  file: 'M6 3h9l4 4v14H6zM14 3v5h5',
}
const KIND_COLOR: Partial<Record<FileKind, string>> = {
  folder: 'text-base-400',
  mods: 'text-redstone-400',
  config: 'text-sky-400',
  saves: 'text-ok',
  resourcepacks: 'text-violet-400',
  shaderpacks: 'text-lamp-400',
  screenshots: 'text-sky-300',
  logs: 'text-base-200',
  crash: 'text-redstone-300',
  datapacks: 'text-emerald-400',
  backups: 'text-lamp-300',
  jar: 'text-redstone-300',
  archive: 'text-lamp-300',
  image: 'text-sky-300',
}
function kindOf(entry: FileEntry): FileKind {
  return fileKind(entry, atRoot.value)
}
function knownLabel(kind: FileKind): string | null {
  return atRoot.value && isKnownFolder(kind) ? tKey(`files.known.${kind}`) : null
}

const quickFolders = computed(() =>
  atRoot.value
    ? []
    : (['mods', 'config', 'saves', 'resourcepacks', 'shaderpacks', 'screenshots', 'logs'] as const),
)
const shortDate = computed(() => new Intl.DateTimeFormat(intlLocale(), { dateStyle: 'short', timeStyle: 'short' }))
function when(iso: string | null): string {
  return iso ? shortDate.value.format(new Date(iso)) : '–'
}
const sortIcon = (key: FileSortKey) => (sort.value.key === key ? (sort.value.desc ? '↓' : '↑') : '')
const ariaSort = (key: FileSortKey) => (sort.value.key === key ? (sort.value.desc ? 'descending' : 'ascending') : 'none')
const nameDialogTitle = computed(() => {
  const mode = nameDialog.value?.mode
  return mode === 'rename' ? t('files.rename') : mode === 'folder' ? t('files.newFolder') : t('files.newFile')
})
</script>

<template>
  <section class="file-browser card relative flex min-h-0 flex-1 flex-col overflow-hidden" :aria-label="t('files.title')" @contextmenu.self.prevent="openMenu($event, null)">
    <!-- Werkzeugleiste -->
    <div class="flex flex-wrap items-center gap-2 border-b border-base-800 px-3 py-2">
      <nav class="flex min-w-0 flex-1 items-center gap-0.5 text-sm" :aria-label="t('files.breadcrumbs')">
        <button class="crumb" :class="{ 'crumb-on': atRoot }" :title="t('files.root')" @click="go('')">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path :d="icons.home" /></svg>
          <span class="max-w-40 truncate">{{ instance.name }}</span>
        </button>
        <template v-for="(c, i) in crumbs" :key="c.path">
          <span class="text-base-600" aria-hidden="true">/</span>
          <button class="crumb" :class="{ 'crumb-on': i === crumbs.length - 1 }" :aria-current="i === crumbs.length - 1 ? 'location' : undefined" @click="go(c.path)">
            <span class="max-w-48 truncate">{{ c.name }}</span>
          </button>
        </template>
      </nav>

      <div class="relative w-44">
        <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-base-400" fill="none" stroke="currentColor" stroke-width="2"><path :d="icons.search" /></svg>
        <input v-model="query" class="field h-8 py-0 pl-8 text-xs" maxlength="100" :placeholder="t('files.filter')" :aria-label="t('files.filter')" spellcheck="false" @keydown.esc="query = ''" />
      </div>
      <div class="flex items-center gap-1">
        <button class="btn-icon size-8" :title="t('common.actions.refresh')" :aria-label="t('common.actions.refresh')" :disabled="loading" @click="load(cwd, true)">
          <svg viewBox="0 0 24 24" class="size-4" :class="{ 'animate-spin': loading }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path :d="icons.sync" /></svg>
        </button>
        <button class="btn-icon size-8" :title="t('files.newFolder')" :aria-label="t('files.newFolder')" @click="startCreate('folder')">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1zM12 10v6M9 13h6" /></svg>
        </button>
        <button class="btn-icon size-8" :title="t('files.newFile')" :aria-label="t('files.newFile')" @click="startCreate('file')">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M6 3h9l4 4v14H6zM14 3v5h5M12 11v6M9 14h6" /></svg>
        </button>
        <button class="btn-icon size-8" :title="t('common.actions.openFolder')" :aria-label="t('common.actions.openFolder')" @click="openCurrentDir">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M14 4h6v6M20 4l-8 8M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" /></svg>
        </button>
        <button class="btn btn-primary h-8 px-3 py-0 text-xs" :disabled="busy" @click="upload">
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M12 16V4m0 0L7 9m5-5 5 5M4 17v3h16v-3" /></svg>
          {{ t('files.upload') }}
        </button>
      </div>
    </div>

    <!-- Schnellzugriff auf bekannte Ordner (außerhalb der Wurzel) -->
    <div v-if="quickFolders.length" class="flex flex-wrap gap-1 border-b border-base-800 px-3 py-1.5">
      <button v-for="f in quickFolders" :key="f" class="quick" @click="go(f)">
        <svg viewBox="0 0 24 24" class="size-3" :class="KIND_COLOR[f]" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path :d="KIND_ICON[f]" /></svg>
        {{ f }}
      </button>
    </div>

    <!-- Auswahlleiste -->
    <div v-if="selection.length" class="flex flex-wrap items-center gap-2 border-b border-redstone-600/30 bg-redstone-900/25 px-3 py-1.5 text-xs">
      <span class="font-medium text-base-50">{{ t('files.selected', selection.length) }}</span>
      <button class="sel-btn" @click="openSelected">{{ t('common.actions.open') }}</button>
      <button class="sel-btn" @click="reveal()">{{ t('files.reveal') }}</button>
      <button class="sel-btn" :disabled="selection.length !== 1" @click="startRename(selection[0]!)">{{ t('files.rename') }}</button>
      <button class="sel-btn text-redstone-300 hover:text-redstone-300" @click="confirmTrash = true">{{ t('files.trash') }}</button>
      <button class="ml-auto text-base-400 hover:text-base-50" @click="selected = new Set()">{{ t('files.clearSelection') }}</button>
    </div>

    <!-- Liste -->
    <div class="min-h-0 flex-1 overflow-auto" @contextmenu.self.prevent="openMenu($event, null)">
      <table class="w-full table-fixed border-separate border-spacing-0 text-sm">
        <thead class="sticky top-0 z-10 bg-base-900 text-left text-[11px] tracking-wide text-base-400 uppercase">
          <tr>
            <th class="w-9 border-b border-base-800 py-2 pl-3">
              <input type="checkbox" class="fcheck" :checked="allChecked" :aria-label="t('files.selectAll')" @change="toggleAll" />
            </th>
            <th class="border-b border-base-800 py-2" :aria-sort="ariaSort('name')">
              <button class="th-btn" @click="setSort('name')">{{ t('common.labels.name') }} {{ sortIcon('name') }}</button>
            </th>
            <th class="w-28 border-b border-base-800 py-2 pr-6 text-right" :aria-sort="ariaSort('size')">
              <button class="th-btn ml-auto" @click="setSort('size')">{{ t('files.size') }} {{ sortIcon('size') }}</button>
            </th>
            <th class="hidden w-36 border-b border-base-800 py-2 lg:table-cell" :aria-sort="ariaSort('created')">
              <button class="th-btn" @click="setSort('created')">{{ t('files.created') }} {{ sortIcon('created') }}</button>
            </th>
            <th class="w-36 border-b border-base-800 py-2 pr-3" :aria-sort="ariaSort('modified')">
              <button class="th-btn" @click="setSort('modified')">{{ t('files.modified') }} {{ sortIcon('modified') }}</button>
            </th>
          </tr>
        </thead>
        <tbody v-if="loading && !listing">
          <tr v-for="i in 8" :key="i"><td colspan="5" class="px-3 py-1.5"><div class="skeleton h-6" /></td></tr>
        </tbody>
        <tbody v-else ref="list" role="listbox" :aria-label="t('files.title')" aria-multiselectable="true" @keydown="onListKey">
          <tr
            v-for="entry in rows"
            :key="entry.name"
            :data-name="entry.name"
            role="option"
            :aria-selected="selected.has(entry.name)"
            :tabindex="(focused ?? rows[0]?.name) === entry.name ? 0 : -1"
            class="file-row"
            :class="{ 'file-row-on': selected.has(entry.name) }"
            @click="click(entry, $event)"
            @dblclick="activate(entry)"
            @focus="focused = entry.name"
            @contextmenu.prevent.stop="openMenu($event, entry)"
          >
            <td class="py-1 pl-3" @click.stop>
              <input type="checkbox" class="fcheck" tabindex="-1" :checked="selected.has(entry.name)" :aria-label="t('files.selectOne', { name: entry.name })" @change="toggleCheck(entry)" />
            </td>
            <td class="py-1">
              <div class="flex min-w-0 items-center gap-2.5">
                <span class="file-icon" :class="[KIND_COLOR[kindOf(entry)] ?? 'text-base-400', { 'file-icon-known': !!knownLabel(kindOf(entry)) }]">
                  <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="KIND_ICON[kindOf(entry)]" /></svg>
                </span>
                <span class="min-w-0 truncate" :class="entry.dir ? 'font-medium text-base-50' : 'text-base-200'" :title="entry.name">{{ entry.name }}</span>
                <span v-if="knownLabel(kindOf(entry))" class="hidden shrink-0 truncate text-[11px] text-base-600 md:inline">{{ knownLabel(kindOf(entry)) }}</span>
              </div>
            </td>
            <td class="py-1 pr-6 text-right font-mono text-xs whitespace-nowrap text-base-400 tabular-nums">{{ entry.dir ? '–' : formatBytes(entry.size) }}</td>
            <td class="hidden py-1 text-xs whitespace-nowrap text-base-400 tabular-nums lg:table-cell">{{ when(entry.created) }}</td>
            <td class="py-1 pr-3 text-xs whitespace-nowrap text-base-400 tabular-nums">{{ when(entry.modified) }}</td>
          </tr>
        </tbody>
      </table>

      <div v-if="listing && !rows.length && !loading" class="px-6 py-14 text-center">
        <p class="heading text-base">{{ query ? t('files.noMatches') : t('files.emptyFolder') }}</p>
        <p class="mt-1.5 text-sm text-base-400">{{ query ? t('files.noMatchesText') : t('files.emptyFolderText') }}</p>
      </div>
      <p v-if="listing?.truncated" class="px-3 py-2 text-xs text-lamp-300">{{ t('files.truncated') }}</p>
    </div>

    <div class="flex items-center gap-3 border-t border-base-800 px-3 py-1 text-[11px] text-base-600">
      <span>{{ t('files.count', rows.length) }}</span>
      <span class="ml-auto hidden sm:inline">{{ t('files.dropHint') }}</span>
    </div>

    <!-- Ablegen -->
    <div v-if="dragging" class="pointer-events-none absolute inset-2 z-20 flex flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed border-redstone-500 bg-base-950/85 text-center">
      <svg viewBox="0 0 24 24" class="size-8 text-redstone-400" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 4v12m0 0-5-5m5 5 5-5M4 20h16" /></svg>
      <p class="heading text-base">{{ t('files.dropTitle') }}</p>
      <p class="font-mono text-xs text-base-400">/{{ cwd }}</p>
    </div>

    <!-- Kontextmenü -->
    <div v-if="menu" data-file-menu class="menu" :style="{ left: `${menu.x}px`, top: `${menu.y}px` }" role="menu">
      <template v-if="menu.entry">
        <button class="menu-item" role="menuitem" @click="openSelected">{{ menu.entry.dir ? t('files.openFolder') : t('common.actions.open') }}</button>
        <button class="menu-item" role="menuitem" @click="reveal(menu.entry)">{{ t('files.reveal') }}</button>
        <button class="menu-item" role="menuitem" :disabled="selection.length !== 1" @click="startRename(menu.entry)">{{ t('files.rename') }}</button>
        <button class="menu-item" role="menuitem" @click="copyPath">{{ t('files.copyPath') }}</button>
        <div class="my-1 border-t border-base-700" />
        <button class="menu-item text-redstone-300" role="menuitem" @click="menu = null; confirmTrash = true">{{ t('files.trash') }}</button>
      </template>
      <template v-else>
        <button class="menu-item" role="menuitem" @click="startCreate('folder')">{{ t('files.newFolder') }}</button>
        <button class="menu-item" role="menuitem" @click="startCreate('file')">{{ t('files.newFile') }}</button>
        <button class="menu-item" role="menuitem" @click="upload">{{ t('files.upload') }}</button>
        <button class="menu-item" role="menuitem" @click="openCurrentDir">{{ t('common.actions.openFolder') }}</button>
      </template>
    </div>

    <!-- Dialoge -->
    <BaseDialog v-if="nameDialog" :title="nameDialogTitle" @close="nameDialog = null">
      <form id="file-name-form" @submit.prevent="submitName">
        <label class="label" for="file-name">{{ t('common.labels.name') }}</label>
        <input id="file-name" v-model="nameDialog.value" class="field font-mono" maxlength="255" spellcheck="false" autofocus @input="nameError = null" />
        <p class="mt-1.5 text-xs text-base-400">{{ t('files.inFolder', { path: `/${cwd}` }) }}</p>
        <p v-if="nameError" role="alert" class="mt-2 text-sm text-redstone-300">{{ nameError }}</p>
      </form>
      <template #actions>
        <button class="btn btn-ghost" @click="nameDialog = null">{{ t('common.actions.cancel') }}</button>
        <button type="submit" form="file-name-form" class="btn btn-primary" :disabled="busy">{{ nameDialog.mode === 'rename' ? t('files.rename') : t('common.actions.create') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="confirmTrash" :title="t('files.trashTitle', selection.length)" @close="confirmTrash = false">
      <p class="text-sm text-base-200">{{ isLinux ? t('files.trashTextLinux') : t('files.trashText') }}</p>
      <ul class="mt-3 max-h-40 space-y-0.5 overflow-y-auto rounded-md bg-base-950 p-2 font-mono text-xs text-base-400">
        <li v-for="e in selection.slice(0, 50)" :key="e.name" class="truncate">{{ e.dir ? '📁' : '·' }} {{ e.name }}</li>
        <li v-if="selection.length > 50">… +{{ selection.length - 50 }}</li>
      </ul>
      <template #actions>
        <button class="btn btn-ghost" @click="confirmTrash = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="busy" @click="trashSelected">{{ t('files.trash') }}</button>
      </template>
    </BaseDialog>
  </section>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.crumb {
  @apply inline-flex min-w-0 items-center gap-1.5 rounded-md px-1.5 py-1 text-base-400 transition-colors hover:bg-base-800 hover:text-base-50;
}
.crumb-on {
  @apply text-base-50;
}
.quick {
  @apply inline-flex items-center gap-1.5 rounded-md bg-base-850 px-2 py-0.5 font-mono text-[11px] text-base-400 ring-1 ring-base-800 transition-colors hover:bg-base-800 hover:text-base-50;
}
.th-btn {
  @apply flex items-center gap-1 font-semibold hover:text-base-50;
}
.sel-btn {
  @apply rounded px-2 py-0.5 text-base-200 transition-colors hover:bg-base-800 hover:text-base-50 disabled:opacity-40;
}
.file-row {
  @apply cursor-default outline-none;
}
.file-row > td {
  @apply border-b border-base-850 transition-colors;
}
.file-row:hover > td {
  @apply bg-base-850;
}
.file-row:focus-visible > td {
  @apply bg-base-850;
  box-shadow: inset 0 1px 0 var(--color-redstone-500), inset 0 -1px 0 var(--color-redstone-500);
}
.file-row-on > td,
.file-row-on:hover > td {
  background: color-mix(in srgb, var(--color-redstone-900) 55%, transparent);
}
.file-icon {
  @apply flex size-6 shrink-0 items-center justify-center rounded-md bg-base-850;
}
/* Eigene Kästchen: das native weiße Kästchen passt nicht zum Deepslate-Look. */
.fcheck {
  appearance: none;
  display: inline-block;
  vertical-align: middle;
  width: 0.95rem;
  height: 0.95rem;
  border: 1.5px solid var(--color-base-600);
  border-radius: 0.25rem;
  background: var(--color-base-900) center / 0.7rem no-repeat;
  cursor: pointer;
  transition: border-color 0.12s, background-color 0.12s;
}
.fcheck:hover {
  border-color: var(--color-base-400);
}
.fcheck:checked {
  border-color: var(--color-redstone-500);
  background-color: var(--color-redstone-500);
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='white' stroke-width='3.5' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m5 12 5 5 9-10'/%3E%3C/svg%3E");
}
.file-icon-known {
  @apply ring-1 ring-current/30;
  background: color-mix(in srgb, currentColor 12%, var(--color-base-850));
}
</style>
