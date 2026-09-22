<script setup lang="ts">
import type { ContentItem, ContentKind, ContentUpdate, Instance, ModrinthVersion } from '~/types'

const props = defineProps<{ instance: Instance }>()

const kind = ref<ContentKind>(props.instance.loader.kind === 'vanilla' ? 'resourcepack' : 'mod')
const items = ref<ContentItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const filter = ref('')
const toDelete = ref<ContentItem | null>(null)
const toasts = useToasts()
const updates = ref<ContentUpdate[] | null>(null)
const checking = ref(false)
const updatingAll = ref(false)
const packBusy = ref(false)
const busyFile = ref<string | null>(null)
const menuFor = ref<string | null>(null)
const switching = ref<ContentItem | null>(null)
const changelogFor = ref<ContentItem | null>(null)

const updateFor = (item: ContentItem) => updates.value?.find((u) => u.kind === item.kind && u.fileName === item.fileName)
const pendingUpdates = computed(() => (updates.value ?? []).filter((u) => u.kind === kind.value))

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

async function applyUpdate(update: ContentUpdate) {
  await backend.applyContentUpdate(props.instance.id, update)
  updates.value = (updates.value ?? []).filter((u) => u !== update)
}

async function updateAll() {
  updatingAll.value = true
  try {
    for (const u of [...pendingUpdates.value]) await applyUpdate(u)
    toasts.ok('Updates installiert')
  } catch (e) {
    toasts.error(e)
  } finally {
    updatingAll.value = false
    load()
  }
}

async function updateOne(update: ContentUpdate) {
  busyFile.value = update.fileName
  try {
    await applyUpdate(update)
    toasts.ok(`Auf ${update.versionNumber} aktualisiert`)
  } catch (e) {
    toasts.error(e)
  } finally {
    busyFile.value = null
    load()
  }
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
    toasts.ok(`${item.title ?? item.fileName}: Version ${version.versionNumber} installiert`)
  } catch (e) {
    toasts.error(e)
  } finally {
    busyFile.value = null
    load()
  }
}

async function installPerformancePack() {
  packBusy.value = true
  try {
    const files = await backend.installPerformancePack(props.instance.id)
    toasts.ok(`Performance-Paket installiert (${files.length} Dateien)`)
    kind.value = 'mod'
    load()
  } catch (e) {
    toasts.error(e)
  } finally {
    packBusy.value = false
  }
}

const isVanilla = computed(() => props.instance.loader.kind === 'vanilla')

const visible = computed(() => {
  const needle = filter.value.trim().toLowerCase()
  if (!needle) return items.value
  return items.value.filter((i) => `${i.title ?? ''} ${i.fileName} ${i.author ?? ''}`.toLowerCase().includes(needle))
})
const enabledCount = computed(() => items.value.filter((i) => i.enabled).length)

async function load(quiet = false) {
  if (!quiet) loading.value = true
  error.value = null
  try {
    items.value = await backend.listContent(props.instance.id, kind.value)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

// Icons, Titel und Autoren von Modrinth im Hintergrund ergänzen – einmal je Instanz.
let enriched = false
async function enrich() {
  if (enriched) return
  enriched = true
  try {
    if (await backend.refreshContentMeta(props.instance.id)) await load(true)
  } catch {
    // Offline oder Modrinth nicht erreichbar: Liste funktioniert auch ohne.
  }
}

watch(kind, () => load(), { immediate: true })
onMounted(async () => {
  await enrich()
  checkUpdates(true)
})

async function toggle(item: ContentItem) {
  error.value = null
  try {
    await backend.setContentEnabled(props.instance.id, item.kind, item.fileName, !item.enabled)
    item.enabled = !item.enabled
  } catch (e) {
    error.value = errorMessage(e)
    load()
  }
}

async function confirmDelete() {
  const item = toDelete.value
  toDelete.value = null
  if (!item) return
  try {
    await backend.deleteContent(props.instance.id, item.kind, item.fileName)
    items.value = items.value.filter((i) => i.fileName !== item.fileName)
  } catch (e) {
    error.value = errorMessage(e)
  }
}

function projectLink(item: ContentItem) {
  return item.source ? { path: `/project/${item.source.projectId}`, query: { instance: props.instance.id } } : null
}

function openMenu(item: ContentItem) {
  menuFor.value = menuFor.value === item.fileName ? null : item.fileName
}
function closeMenu(e: MouseEvent) {
  if (!(e.target as HTMLElement | null)?.closest('[data-row-menu]')) menuFor.value = null
}
onMounted(() => document.addEventListener('mousedown', closeMenu))
onBeforeUnmount(() => document.removeEventListener('mousedown', closeMenu))

const displayVersion = (item: ContentItem) => item.version ?? item.source?.versionNumber ?? null
</script>

<template>
  <div class="flex min-h-0 flex-1 flex-col">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <div class="flex rounded-full bg-base-900 p-0.5 ring-1 ring-base-800">
        <button v-for="k in contentKinds" :key="k" class="tab px-3 py-1 text-xs" :class="{ 'tab-on': kind === k }" @click="kind = k">
          {{ contentKindLabels[k] }}
        </button>
      </div>
      <div class="relative">
        <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-base-600" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="11" cy="11" r="6" /><path d="m20 20-4.5-4.5" /></svg>
        <input v-model="filter" class="field h-8 w-52 rounded-full py-0 pl-8 text-xs" maxlength="100" placeholder="Filtern …" spellcheck="false" aria-label="Filtern" />
      </div>
      <span v-if="items.length" class="text-xs text-base-600">{{ enabledCount }} von {{ items.length }} aktiv</span>
      <div class="ml-auto flex items-center gap-2">
        <button v-if="pendingUpdates.length" class="btn h-8 bg-lamp-900 py-0 text-xs text-lamp-300 ring-1 ring-lamp-400/40 hover:bg-base-800" :disabled="updatingAll" @click="updateAll">
          {{ updatingAll ? 'Aktualisiere …' : `${pendingUpdates.length} ${pendingUpdates.length === 1 ? 'Update' : 'Updates'} installieren` }}
        </button>
        <button v-else class="btn btn-ghost h-8 py-0 text-xs" :disabled="checking || !items.length" @click="checkUpdates()">
          {{ checking ? 'Prüfe …' : 'Nach Updates suchen' }}
        </button>
        <button v-if="!isVanilla" class="btn btn-ghost h-8 py-0 text-xs" :disabled="packBusy" title="Sodium, Lithium & Co. – nur was es für diese Version gibt" @click="installPerformancePack">
          {{ packBusy ? 'Installiere …' : 'Performance-Paket' }}
        </button>
        <NuxtLink :to="{ path: '/browse', query: { instance: instance.id, kind } }" class="btn btn-primary h-8 py-0 text-xs">
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
          Hinzufügen
        </NuxtLink>
      </div>
    </div>

    <p v-if="error" role="alert" class="card mb-3 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ error }}</p>

    <p v-if="kind === 'mod' && isVanilla" class="card mb-3 px-4 py-2.5 text-sm text-base-400">
      {{ instance.overrides.boost === false
        ? 'TRS-Optimierung ist aus – diese Instanz startet als echtes Vanilla und lädt keine Mods.'
        : 'Diese Instanz nutzt die TRS-Optimierung: Sie startet mit Fabric, dem TRS Client und Performance-Mods. Hier siehst du, was dafür installiert ist.' }}
    </p>

    <div v-if="loading && !items.length" class="card divide-y divide-base-800">
      <div v-for="i in 6" :key="i" class="flex items-center gap-3 px-3 py-2.5">
        <div class="skeleton size-10 rounded-lg" />
        <div class="flex-1 space-y-1.5"><div class="skeleton h-3.5 w-48" /><div class="skeleton h-3 w-72" /></div>
        <div class="skeleton h-5 w-9 rounded-full" />
      </div>
    </div>

    <div v-else-if="visible.length" class="card min-h-0 flex-1 overflow-y-auto">
      <div class="sticky top-0 z-10 grid grid-cols-[2.5rem_minmax(0,1fr)_9rem_2.25rem_4.5rem] items-center gap-3 border-b border-base-800 bg-base-900/95 px-3 py-2 text-[11px] font-medium text-base-600 backdrop-blur">
        <span />
        <span>Name</span>
        <span>Version</span>
        <span class="text-center">Aktiv</span>
        <span />
      </div>
      <ul class="divide-y divide-base-800/70">
        <li
          v-for="item in visible"
          :key="item.fileName"
          class="group grid grid-cols-[2.5rem_minmax(0,1fr)_9rem_2.25rem_4.5rem] items-center gap-3 px-3 py-2 transition-colors hover:bg-base-850"
        >
          <ModIcon :src="item.iconUrl" :name="item.title ?? item.fileName" :size="40" :class="{ 'opacity-50 grayscale': !item.enabled }" />

          <div class="min-w-0" :class="{ 'opacity-60': !item.enabled }">
            <NuxtLink v-if="projectLink(item)" :to="projectLink(item)!" class="block truncate text-sm font-medium hover:text-redstone-300">
              {{ item.title ?? item.fileName }}
            </NuxtLink>
            <p v-else class="truncate text-sm font-medium">{{ item.title ?? item.fileName }}</p>
            <p class="truncate text-xs text-base-400" :title="item.description ?? item.fileName">
              <template v-if="item.author">von {{ item.author }}<span class="text-base-600"> · </span></template>{{ item.description ?? item.fileName }}
            </p>
          </div>

          <div class="flex min-w-0 flex-col items-start gap-1">
            <span v-if="displayVersion(item)" class="max-w-full truncate font-mono text-xs text-base-200" :title="displayVersion(item)!">{{ displayVersion(item) }}</span>
            <span v-else class="text-xs text-base-600">–</span>
            <span v-if="busyFile === item.fileName" class="w-20"><RedstoneWire :percent="60" :segments="8" /></span>
            <button
              v-else-if="updateFor(item)"
              class="badge bg-lamp-900 text-lamp-300 ring-1 ring-lamp-400/30 hover:bg-lamp-400 hover:text-base-950"
              :title="`Update auf ${updateFor(item)!.versionNumber}`"
              @click="item.source ? (changelogFor = item) : updateOne(updateFor(item)!)"
            >
              <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 19V5m0 0-6 6m6-6 6 6" /></svg>
              Update
            </button>
          </div>

          <button
            role="switch"
            :aria-checked="item.enabled"
            :aria-label="`${item.title ?? item.fileName} ${item.enabled ? 'deaktivieren' : 'aktivieren'}`"
            class="relative mx-auto h-5 w-9 shrink-0 rounded-full transition-colors"
            :class="item.enabled ? 'bg-redstone-500' : 'bg-base-700'"
            @click="toggle(item)"
          >
            <span class="absolute top-0.5 left-0.5 size-4 rounded-full bg-white shadow transition-transform" :class="{ 'translate-x-4': item.enabled }" />
          </button>

          <div class="relative flex justify-end gap-1" data-row-menu>
            <button class="btn-icon size-8 bg-transparent opacity-70 group-hover:opacity-100" :aria-label="`Aktionen für ${item.title ?? item.fileName}`" :aria-expanded="menuFor === item.fileName" @click="openMenu(item)">
              <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><circle cx="12" cy="5.5" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="12" cy="18.5" r="1.7" /></svg>
            </button>
            <div v-if="menuFor === item.fileName" class="menu top-9 right-0" role="menu">
              <button v-if="updateFor(item)" class="menu-item text-lamp-300" role="menuitem" @click="menuFor = null; updateOne(updateFor(item)!)">
                Auf {{ updateFor(item)!.versionNumber }} aktualisieren
              </button>
              <button v-if="item.source" class="menu-item" role="menuitem" @click="menuFor = null; changelogFor = item">Changelog ansehen</button>
              <button v-if="item.source" class="menu-item" role="menuitem" @click="menuFor = null; switching = item">Version wechseln</button>
              <NuxtLink v-if="projectLink(item)" :to="projectLink(item)!" class="menu-item" role="menuitem">Projektseite</NuxtLink>
              <p v-if="!item.source" class="px-2.5 py-1.5 text-xs text-base-400">Nicht über Modrinth installiert – Versionen und Changelog gibt es nur für Modrinth-Inhalte.</p>
              <div class="my-1 border-t border-base-700" />
              <button class="menu-item text-redstone-300" role="menuitem" @click="menuFor = null; toDelete = item">Löschen</button>
            </div>
          </div>
        </li>
      </ul>
    </div>

    <div v-else-if="!loading" class="card flex flex-col items-center px-6 py-14 text-center">
      <div class="mb-4 grid size-14 place-items-center rounded-xl bg-base-800 text-base-400">
        <svg viewBox="0 0 24 24" class="size-7" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 7.5 12 3l8 4.5v9L12 21l-8-4.5z" /><path d="m4 7.5 8 4.5 8-4.5M12 12v9" /></svg>
      </div>
      <h2 class="font-semibold">{{ items.length ? 'Nichts gefunden' : `Noch keine ${contentKindLabels[kind]}` }}</h2>
      <p class="mx-auto mt-1 max-w-md text-sm text-base-400">
        {{ items.length ? 'Kein Eintrag passt zum Filter.' : 'Installiere welche über „Entdecken“ oder lege Dateien direkt in den Instanz-Ordner.' }}
      </p>
      <NuxtLink v-if="!items.length" :to="{ path: '/browse', query: { instance: instance.id, kind } }" class="btn btn-primary mt-5">
        {{ contentKindLabels[kind] }} entdecken
      </NuxtLink>
    </div>

    <VersionPickerDialog
      v-if="switching?.source"
      :instance="instance"
      :project-id="switching.source.projectId"
      :title="switching.title ?? switching.fileName"
      :kind="switching.kind"
      :current-version-id="switching.source.versionId"
      @close="switching = null"
      @pick="switchVersion(switching!, $event)"
    />

    <ChangelogDialog
      v-if="changelogFor?.source"
      :instance="instance"
      :item="changelogFor"
      @close="changelogFor = null"
      @install="switchVersion(changelogFor!, $event)"
    />

    <BaseDialog v-if="toDelete" title="Datei löschen?" @close="toDelete = null">
      <p class="text-sm text-base-200">
        <strong class="text-base-50">{{ toDelete.title ?? toDelete.fileName }}</strong> wird aus dieser Instanz gelöscht.
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">Abbrechen</button>
        <button class="btn btn-danger" @click="confirmDelete">Löschen</button>
      </template>
    </BaseDialog>
  </div>
</template>
