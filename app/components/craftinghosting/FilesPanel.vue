<script setup lang="ts">
import { detectFormat, editorSupports, type ConfigFormat } from '~/utils/config/editor'
import {
  FILE_CONTENT_MAX,
  type ServerDir,
  type ServerFile,
  fileIcon,
  joinServerPath,
  parentServerPath,
  serverCrumbs,
  sortServerFiles,
  validEntryName,
  validServerPath,
} from '~/utils/craftinghosting'
import { BackendError } from '~/utils/backend'

// Dateien des Partner-Servers: Ordner durchsuchen, Textdateien bearbeiten, Ordner anlegen, löschen.
// Geschützte Einträge (von TRS verwaltet) bleiben unangetastet. Gilt ab dem nächsten Start.
const store = useCraftingHostingStore()
const toasts = useToasts()

const dir = ref('/')
const listing = ref<ServerDir | null>(null)
const loading = ref(false)
const pending = ref(false)

interface OpenFile {
  path: string
  name: string
  original: string
  format: ConfigFormat | null
}
const open = ref<OpenFile | null>(null)
const content = ref('')
const opening = ref<string | null>(null)
const saving = ref(false)
const dirty = computed(() => !!open.value && content.value !== open.value.original)
const tooLarge = computed(() => new TextEncoder().encode(content.value).length > FILE_CONTENT_MAX)

const collator = computed(() => new Intl.Collator(intlLocale(), { numeric: true, sensitivity: 'base' }))
const rows = computed(() => sortServerFiles(listing.value?.entries ?? [], collator.value))
const crumbs = computed(() => serverCrumbs(dir.value))

let loadToken = 0
async function load(path = dir.value) {
  if (!validServerPath(path)) path = '/'
  const token = ++loadToken
  loading.value = true
  try {
    const result = await backend.craftinghosting.files(path)
    if (token !== loadToken) return
    listing.value = result
    dir.value = result.dir
    pending.value = false
  } catch (e) {
    if (token !== loadToken) return
    if (e instanceof BackendError && e.apiCode === 'server_installing') pending.value = true
    // Ordner fehlt (z. B. Konfiguration noch nicht angelegt): eine Ebene höher.
    else if (e instanceof BackendError && (e.apiCode === 'file_not_found' || e.apiCode === 'not_found') && path !== '/') return load(parentServerPath(path))
    else toasts.error(e)
  } finally {
    if (token === loadToken) loading.value = false
  }
}

onMounted(() => load(store.filesDir))
watch(
  () => store.filesJump,
  () => void load(store.filesDir),
)

// --- Öffnen, speichern, schließen (mit Rückfrage bei ungespeicherten Änderungen) -----------

const discardAsk = ref<{ name: string; then: () => void } | null>(null)
function guard(then: () => void) {
  if (dirty.value && open.value) discardAsk.value = { name: open.value.name, then }
  else then()
}
function confirmDiscard() {
  const next = discardAsk.value?.then
  discardAsk.value = null
  next?.()
}

function onRow(entry: ServerFile) {
  if (entry.type === 'directory') void load(entry.path)
  else if (entry.editable) guard(() => void openFile(entry))
}

async function openFile(entry: ServerFile) {
  opening.value = entry.path
  try {
    const file = await backend.craftinghosting.file(entry.path)
    open.value = {
      path: file.path,
      name: entry.name,
      original: file.content,
      format: editorSupports(entry.name) ? detectFormat(entry.name, file.content) : null,
    }
    content.value = file.content
  } catch (e) {
    toasts.error(e)
  } finally {
    opening.value = null
  }
}

function closeFile() {
  guard(() => {
    open.value = null
    content.value = ''
  })
}

async function save() {
  const file = open.value
  if (!file || !dirty.value || saving.value || tooLarge.value) return
  saving.value = true
  const text = content.value
  try {
    await backend.craftinghosting.saveFile(file.path, text)
    // server.properties bearbeitet die API nach – den echten Stand zurückholen.
    if (file.path === '/server.properties') {
      const fresh = await backend.craftinghosting.file(file.path).catch(() => null)
      file.original = fresh?.content ?? text
      if (fresh && content.value === text) content.value = fresh.content
      if (store.settings) void store.loadSettings()
      if (store.properties) void store.loadProperties()
    } else {
      file.original = text
    }
    toasts.ok(t('craftinghosting.files.saved'))
    void load()
  } catch (e) {
    toasts.error(e)
  } finally {
    saving.value = false
  }
}

function onEditorKey(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 's') {
    e.preventDefault()
    void save()
  }
}

// --- Ordner anlegen, löschen ---------------------------------------------------------------

const folderOpen = ref(false)
const folderName = ref('')
const folderValid = computed(() => validEntryName(folderName.value) && validServerPath(joinServerPath(dir.value, folderName.value)))
const busy = ref(false)

function askFolder() {
  folderName.value = ''
  folderOpen.value = true
}

async function createFolder() {
  if (!folderValid.value || busy.value) return
  busy.value = true
  const path = joinServerPath(dir.value, folderName.value)
  try {
    await backend.craftinghosting.createFolder(path)
    folderOpen.value = false
    toasts.ok(t('craftinghosting.files.folderCreated'))
    await load(path)
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}

const deleting = ref<ServerFile | null>(null)
async function remove() {
  const entry = deleting.value
  if (!entry || busy.value) return
  busy.value = true
  try {
    await backend.craftinghosting.deleteFiles([entry.path])
    deleting.value = null
    if (open.value && (open.value.path === entry.path || open.value.path.startsWith(`${entry.path}/`))) {
      open.value = null
      content.value = ''
    }
    toasts.ok(t('craftinghosting.files.deleted', { name: entry.name }))
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}

const ICONS: Record<ReturnType<typeof fileIcon>, { d: string; tone: string }> = {
  folder: { d: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z', tone: 'text-lamp-300' },
  config: { d: 'M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1M15 4v4M9 10v4M17 16v4', tone: 'text-sky-300' },
  text: { d: 'M7 3h7l5 5v13H7zM14 3v5h5M10 13h6M10 17h6', tone: 'text-base-300' },
  log: { d: 'M7 3h7l5 5v13H7zM14 3v5h5M10 12h2M10 15h6M10 18h4', tone: 'text-base-400' },
  archive: { d: 'M4 4h16v4H4zM5 8h14v12H5zM10 12h4', tone: 'text-violet-300' },
  jar: { d: 'M8 3h8v3H8zM7 6h10l1 3v10a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V9zM9 13h6', tone: 'text-emerald-300' },
  image: { d: 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15 9h.01', tone: 'text-pink-300' },
  data: { d: 'M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3', tone: 'text-base-400' },
  file: { d: 'M7 3h7l5 5v13H7zM14 3v5h5', tone: 'text-base-400' },
}
</script>

<template>
  <div class="space-y-3" data-testid="hosting-files">
    <p class="flex items-start gap-2 rounded-lg bg-base-900 px-3 py-2 text-xs text-base-300 ring-1 ring-base-800">
      <svg viewBox="0 0 24 24" class="mt-px size-3.5 shrink-0 text-lamp-300" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 8v4M12 16h.01" /></svg>
      {{ t('craftinghosting.files.nextStart') }}
    </p>

    <p v-if="pending" class="card p-4 text-sm text-base-400" data-testid="hosting-files-pending">{{ t('craftinghosting.files.pending') }}</p>

    <div v-else class="grid gap-3" :class="open ? 'lg:grid-cols-[minmax(16rem,22rem)_1fr]' : ''">
      <!-- Liste -->
      <section class="card flex min-w-0 flex-col overflow-hidden" :class="open ? 'max-lg:hidden' : ''" :aria-label="t('craftinghosting.files.title')">
        <div class="flex items-center gap-1 border-b border-base-800 px-2 py-1.5">
          <button class="btn-icon size-8" :disabled="dir === '/' || loading" :aria-label="t('craftinghosting.files.up')" :title="t('craftinghosting.files.up')" @click="load(parentServerPath(dir))">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7" /></svg>
          </button>
          <nav class="flex min-w-0 flex-1 items-center overflow-x-auto text-sm whitespace-nowrap" :aria-label="t('craftinghosting.files.breadcrumb')" data-testid="hosting-files-crumbs">
            <template v-for="(c, i) in crumbs" :key="c.path">
              <span v-if="i > 1" class="px-0.5 text-base-600" aria-hidden="true">/</span>
              <button
                class="rounded px-1.5 py-0.5 font-mono text-xs hover:bg-base-800"
                :class="i === crumbs.length - 1 ? 'text-base-50' : 'text-base-400'"
                :aria-current="i === crumbs.length - 1 ? 'location' : undefined"
                @click="load(c.path)"
              >
                {{ c.name }}
              </button>
            </template>
          </nav>
          <button class="btn-icon size-8" :disabled="loading" :aria-label="t('craftinghosting.files.newFolder')" :title="t('craftinghosting.files.newFolder')" data-testid="hosting-files-new-folder" @click="askFolder">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM12 11v5M9.5 13.5h5" /></svg>
          </button>
          <button class="btn-icon size-8" :disabled="loading" :aria-label="t('common.actions.refresh')" :title="t('common.actions.refresh')" @click="load()">
            <svg viewBox="0 0 24 24" class="size-4" :class="{ 'animate-spin motion-reduce:animate-none': loading }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7" /></svg>
          </button>
        </div>

        <div v-if="!listing && loading" class="space-y-2 p-3">
          <div v-for="i in 6" :key="i" class="skeleton h-8" />
        </div>
        <p v-else-if="!rows.length" class="px-4 py-8 text-center text-sm text-base-500">{{ t('craftinghosting.files.empty') }}</p>
        <ul v-else class="max-h-[32rem] divide-y divide-base-800/60 overflow-y-auto" :class="{ 'opacity-60': loading }" data-testid="hosting-files-list">
          <li v-for="entry in rows" :key="entry.path" class="group flex items-center gap-1 pr-1">
            <button
              class="flex min-w-0 flex-1 items-center gap-2.5 px-3 py-2 text-left text-sm outline-none focus-visible:bg-base-800"
              :class="[
                entry.type === 'directory' || entry.editable ? 'hover:bg-base-800/70' : 'cursor-default',
                open?.path === entry.path ? 'bg-redstone-900/30' : '',
              ]"
              :aria-disabled="entry.type === 'file' && !entry.editable"
              :title="entry.protected ? t('craftinghosting.files.protected') : entry.type === 'file' && !entry.editable ? t('craftinghosting.files.notEditable') : entry.path"
              :data-testid="`hosting-file-${entry.name}`"
              @click="onRow(entry)"
            >
              <svg viewBox="0 0 24 24" class="size-4 shrink-0" :class="ICONS[fileIcon(entry)].tone" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="ICONS[fileIcon(entry)].d" /></svg>
              <span class="min-w-0 flex-1 truncate" :class="entry.type === 'file' && !entry.editable ? 'text-base-400' : 'text-base-50'">{{ entry.name }}</span>
              <svg v-if="entry.protected" viewBox="0 0 24 24" class="size-3.5 shrink-0 text-lamp-300" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" :aria-label="t('craftinghosting.files.protected')" role="img"><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
              <span v-if="opening === entry.path" class="size-3.5 shrink-0 animate-spin rounded-full border-2 border-base-600 border-t-base-200 motion-reduce:animate-none" aria-hidden="true" />
              <span class="hidden w-16 shrink-0 text-right text-xs text-base-500 tabular-nums sm:block">{{ entry.type === 'file' ? formatBytes(entry.size) : '' }}</span>
              <span v-if="!open" class="hidden w-24 shrink-0 text-right text-xs text-base-500 md:block">{{ entry.modifiedAt ? formatDate(entry.modifiedAt) : '' }}</span>
            </button>
            <button
              v-if="!entry.protected"
              class="btn-icon size-7 shrink-0 text-base-500 opacity-0 group-focus-within:opacity-100 group-hover:opacity-100 hover:text-redstone-300 focus-visible:opacity-100"
              :aria-label="t('craftinghosting.files.deleteLabel', { name: entry.name })"
              :title="t('common.actions.delete')"
              @click="deleting = entry"
            >
              <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
            </button>
            <span v-else class="size-7 shrink-0" aria-hidden="true" />
          </li>
        </ul>
      </section>

      <!-- Editor -->
      <section v-if="open" class="card flex min-w-0 flex-col p-3" :aria-label="open.name" data-testid="hosting-file-editor" @keydown="onEditorKey">
        <div class="mb-2 flex flex-wrap items-center gap-2">
          <button class="btn-icon size-8 lg:hidden" :aria-label="t('craftinghosting.files.back')" @click="closeFile">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M15 6l-6 6 6 6" /></svg>
          </button>
          <div class="min-w-0 flex-1">
            <p class="flex items-center gap-2 truncate text-sm font-semibold text-base-50">
              {{ open.name }}
              <span v-if="dirty" class="inline-flex items-center gap-1 text-xs font-normal text-lamp-300" data-testid="hosting-file-dirty">
                <span class="size-1.5 rounded-full bg-lamp-300" aria-hidden="true" />{{ t('craftinghosting.files.unsaved') }}
              </span>
            </p>
            <p class="truncate font-mono text-[11px] text-base-500">{{ open.path }}</p>
          </div>
          <span class="hidden text-[11px] text-base-500 sm:inline">{{ t('craftinghosting.files.shortcut') }}</span>
          <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!dirty || saving || tooLarge" data-testid="hosting-file-save" @click="save">
            {{ saving ? t('craftinghosting.files.saving') : t('common.actions.save') }}
          </button>
          <button class="btn-icon hidden size-8 lg:inline-flex" :aria-label="t('craftinghosting.files.close')" :title="t('craftinghosting.files.close')" @click="closeFile">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
        <p v-if="open.path === '/server.properties'" class="mb-2 rounded-md bg-sky-950/40 px-2.5 py-1.5 text-xs text-sky-200 ring-1 ring-sky-800/40" data-testid="hosting-file-properties-note">
          {{ t('craftinghosting.files.propertiesNote') }}
        </p>
        <p v-if="tooLarge" class="mb-2 text-xs text-redstone-300">{{ t('errors.trsApi.file_too_large') }}</p>
        <div class="flex h-[28rem] min-h-0 mobile:h-[60vh]">
          <ConfigTextEditor v-model="content" :format="open.format" :error-line="null" :label="t('craftinghosting.files.editorLabel', { name: open.name })" />
        </div>
      </section>
    </div>

    <BaseDialog v-if="folderOpen" :title="t('craftinghosting.files.newFolder')" @close="folderOpen = false">
      <form @submit.prevent="createFolder">
        <label for="ch-folder" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.files.folderName') }}</label>
        <input id="ch-folder" v-model="folderName" class="field font-mono" maxlength="100" autocomplete="off" autofocus data-testid="hosting-files-folder-name" />
        <p class="mt-1 truncate font-mono text-[11px] text-base-500">{{ joinServerPath(dir, folderName || '…') }}</p>
      </form>
      <template #actions>
        <button class="btn btn-ghost" @click="folderOpen = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="!folderValid || busy" data-testid="hosting-files-folder-create" @click="createFolder">{{ t('craftinghosting.files.create') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="deleting" :title="t('craftinghosting.files.deleteTitle', { name: deleting.name })" @close="deleting = null">
      <p class="text-sm text-base-200">{{ deleting.type === 'directory' ? t('craftinghosting.files.deleteFolderText') : t('craftinghosting.files.deleteText') }}</p>
      <p class="mt-1 truncate font-mono text-xs text-base-500">{{ deleting.path }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="deleting = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="busy" data-testid="hosting-files-delete-confirm" @click="remove">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="discardAsk" :title="t('craftinghosting.files.discardTitle')" @close="discardAsk = null">
      <p class="text-sm text-base-200">{{ t('craftinghosting.files.discardText', { name: discardAsk.name }) }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="discardAsk = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmDiscard">{{ t('craftinghosting.files.discard') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
