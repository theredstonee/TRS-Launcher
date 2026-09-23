<script setup lang="ts">
import type { Instance, LoaderKind } from '~/types'
import type { LibraryGroupBy, LibrarySort } from '~/utils/library'

// Bibliothek wie in der Modrinth App: große Kacheln, Suche, Sortieren,
// Gruppieren (auch eigene Gruppen) und Filter nach Loader und Version.
const instances = useInstancesStore()
const settings = useSettingsStore()
const meta = useMetaStore()
const toasts = useToasts()
const shell = useUiStore()

const newGroupFor = ref<{ preselect: string | null } | null>(null)
const toDelete = ref<Instance | null>(null)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

// Ansicht merken (nur Komfort – ohne Speicher gelten die Standardwerte).
const PREFS_KEY = 'trs.library'
interface Prefs { sort: LibrarySort; groupBy: LibraryGroupBy; loaders: LoaderKind[]; versions: string[] }
function readPrefs(): Prefs {
  const fallback: Prefs = { sort: 'played', groupBy: 'custom', loaders: [], versions: [] }
  try {
    const raw = JSON.parse(localStorage.getItem(PREFS_KEY) ?? '{}')
    return {
      sort: librarySorts.includes(raw.sort) ? raw.sort : fallback.sort,
      groupBy: libraryGroupBys.includes(raw.groupBy) ? raw.groupBy : fallback.groupBy,
      loaders: Array.isArray(raw.loaders) ? raw.loaders.filter((l: unknown) => loaderKinds.includes(l as LoaderKind)) : [],
      versions: Array.isArray(raw.versions) ? raw.versions.filter((v: unknown) => typeof v === 'string' && v.length < 16) : [],
    }
  } catch {
    return fallback
  }
}
const prefs = reactive(readPrefs())
watch(prefs, (p) => {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify(p))
  } catch {
    // Nicht speicherbar – dann eben nur für diese Sitzung.
  }
})

const search = ref('')
const filterOpen = ref(false)
const collapsed = ref<Set<string>>(new Set())

onMounted(() => {
  instances.load()
  if (!settings.current) settings.load().catch(() => {})
  meta.loadManifest().catch(() => {})
})

const ui = computed(() => settings.current?.ui)
const versionOrder = computed(() => new Map((meta.manifest?.versions ?? []).map((v, i) => [v.id, i])))
const groups = computed(() => customGroups(instances.items))
const availableVersions = computed(() =>
  [...new Set(instances.items.map((i) => majorVersion(i.gameVersion)))].sort((a, b) => compareGameVersions(a, b)),
)
const availableLoaders = computed(() => loaderKinds.filter((k) => instances.items.some((i) => i.loader.kind === k)))
const activeFilters = computed(() => prefs.loaders.length + prefs.versions.length)

const visible = computed(() =>
  sortInstances(filterInstances(instances.items, { query: search.value, loaders: prefs.loaders, versions: prefs.versions }), prefs.sort, versionOrder.value),
)
const grouped = computed(() => groupInstances(visible.value, prefs.groupBy, versionOrder.value))

function toggleIn<T>(list: T[], value: T) {
  const i = list.indexOf(value)
  if (i >= 0) list.splice(i, 1)
  else list.push(value)
}
function toggleCollapsed(key: string) {
  const next = new Set(collapsed.value)
  if (!next.delete(key)) next.add(key)
  collapsed.value = next
}

async function move(instance: Instance, group: string | null) {
  try {
    await backend.setInstanceGroup(instance.id, group)
    await instances.load()
    toasts.ok(
      group
        ? t('library.toasts.movedToGroup', { name: instance.name, group })
        : t('library.toasts.removedFromGroup', { name: instance.name }),
    )
  } catch (e) {
    toasts.error(e)
  }
}

async function onGroupCreated() {
  newGroupFor.value = null
  prefs.groupBy = 'custom'
  await instances.load()
}

async function confirmDelete() {
  if (!toDelete.value) return
  deleting.value = true
  deleteError.value = null
  try {
    await instances.remove(toDelete.value.id)
    toDelete.value = null
  } catch (e) {
    deleteError.value = errorMessage(e)
  } finally {
    deleting.value = false
  }
}

function closeFilter(e: MouseEvent) {
  if (!(e.target as HTMLElement | null)?.closest('[data-filter-menu]')) filterOpen.value = false
}
onMounted(() => document.addEventListener('mousedown', closeFilter))
onBeforeUnmount(() => document.removeEventListener('mousedown', closeFilter))
</script>

<template>
  <div class="p-6">
    <PageHeader :title="t('library.title')" :subtitle="t('library.subtitle')">
      <button class="btn btn-ghost" @click="shell.importing = true">{{ t('common.actions.import') }}</button>
      <button v-if="instances.items.length" class="btn btn-ghost" @click="newGroupFor = { preselect: null }">{{ t('library.newGroup') }}</button>
      <button class="btn btn-primary" @click="shell.creating = true">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        {{ t('library.newInstance') }}
      </button>
    </PageHeader>

    <p v-if="instances.error" role="alert" class="card mb-4 border-redstone-600/50 px-4 py-3 text-sm text-redstone-300">{{ instances.error }}</p>

    <div v-if="instances.items.length" class="mb-5 flex flex-wrap items-center gap-2">
      <div class="relative min-w-56 flex-1">
        <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-base-600" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="11" cy="11" r="6" /><path d="m20 20-4.5-4.5" /></svg>
        <input v-model="search" class="field h-9 rounded-full py-0 pl-9" maxlength="64" :placeholder="t('library.searchPlaceholder', instances.items.length)" spellcheck="false" :aria-label="t('library.searchLabel')" />
      </div>
      <label class="flex items-center gap-2 text-xs text-base-400">
        {{ t('library.sortLabel') }}
        <select v-model="prefs.sort" class="field h-9 w-auto py-0 text-xs text-base-50">
          <option v-for="key in librarySorts" :key="key" :value="key">{{ t(`library.sort.${key}`) }}</option>
        </select>
      </label>
      <label class="flex items-center gap-2 text-xs text-base-400">
        {{ t('library.groupLabel') }}
        <select v-model="prefs.groupBy" class="field h-9 w-auto py-0 text-xs text-base-50">
          <option v-for="key in libraryGroupBys" :key="key" :value="key">{{ t(`library.groupBy.${key}`) }}</option>
        </select>
      </label>
      <div class="relative" data-filter-menu>
        <button class="btn btn-ghost h-9 py-0 text-xs" :aria-expanded="filterOpen" @click="filterOpen = !filterOpen">
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M4 5h16l-6 8v5l-4 2v-7z" /></svg>
          {{ t('library.filter') }}
          <span v-if="activeFilters" class="badge bg-redstone-500 text-white">{{ activeFilters }}</span>
        </button>
        <div v-if="filterOpen" class="menu top-10 right-0 w-64 p-3">
          <p class="mb-1.5 text-[11px] font-semibold tracking-wider text-base-600 uppercase">{{ t('common.labels.loader') }}</p>
          <label v-for="k in availableLoaders" :key="k" class="flex cursor-pointer items-center gap-2.5 rounded-md px-1.5 py-1 text-sm hover:bg-base-700">
            <input type="checkbox" class="size-4 accent-redstone-500" :checked="prefs.loaders.includes(k)" @change="toggleIn(prefs.loaders, k)" />
            <span class="size-2 rounded-full" :style="{ background: loaderColors[k] }" />{{ loaderLabels[k] }}
          </label>
          <p class="mt-3 mb-1.5 text-[11px] font-semibold tracking-wider text-base-600 uppercase">{{ t('common.labels.version') }}</p>
          <div class="max-h-40 overflow-y-auto">
            <label v-for="v in availableVersions" :key="v" class="flex cursor-pointer items-center gap-2.5 rounded-md px-1.5 py-1 text-sm hover:bg-base-700">
              <input type="checkbox" class="size-4 accent-redstone-500" :checked="prefs.versions.includes(v)" @change="toggleIn(prefs.versions, v)" />
              <span class="font-mono">{{ v }}</span>
            </label>
          </div>
          <button v-if="activeFilters" class="mt-2 w-full text-xs text-base-400 hover:text-base-50" @click="prefs.loaders = []; prefs.versions = []">{{ t('library.resetFilters') }}</button>
        </div>
      </div>
    </div>

    <div v-if="instances.loading && !instances.items.length" class="grid grid-cols-[repeat(auto-fill,minmax(11rem,1fr))] gap-4">
      <div v-for="i in 5" :key="i" class="skeleton h-44 rounded-xl" />
    </div>

    <template v-else-if="visible.length">
      <section v-for="g in grouped" :key="g.key" class="mb-6">
        <button v-if="prefs.groupBy !== 'none'" class="mb-3 flex items-center gap-2 text-sm font-semibold text-base-50" :aria-expanded="!collapsed.has(g.key)" @click="toggleCollapsed(g.key)">
          <svg viewBox="0 0 24 24" class="size-4 text-base-400 transition-transform" :class="{ '-rotate-90': collapsed.has(g.key) }" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m6 9 6 6 6-6" /></svg>
          {{ g.label }}
          <span class="text-xs font-normal text-base-600">{{ g.items.length }}</span>
        </button>
        <div
          v-if="!collapsed.has(g.key)"
          class="grid gap-4"
          :class="ui?.compactLibrary ? 'grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))]' : 'grid-cols-[repeat(auto-fill,minmax(13rem,1fr))]'"
        >
          <InstanceCard
            v-for="i in g.items"
            :key="i.id"
            :instance="i"
            :groups="groups"
            :compact="ui?.compactLibrary"
            :show-play-time="ui?.showPlayTime !== false"
            @delete="toDelete = $event"
            @move="move(i, $event)"
            @new-group="newGroupFor = { preselect: $event.id }"
          />
        </div>
      </section>
    </template>
    <RedstoneEmpty
      v-else-if="instances.items.length"
      compact
      :seed="0x21"
      :title="t('library.noMatch.title')"
      :text="t('library.noMatch.text')"
    />

    <RedstoneEmpty
      v-else-if="!instances.loading && !instances.error"
      :seed="0x11"
      :title="t('library.empty.title')"
      :text="t('library.empty.text')"
    >
      <button class="btn btn-primary" @click="shell.creating = true">{{ t('library.empty.create') }}</button>
      <button class="btn btn-ghost" @click="shell.importing = true">{{ t('library.empty.import') }}</button>
    </RedstoneEmpty>

    <NewGroupDialog v-if="newGroupFor" :instances="instances.items" :preselect="newGroupFor.preselect" @close="newGroupFor = null" @done="onGroupCreated" />

    <BaseDialog v-if="toDelete" :title="t('library.delete.title')" @close="toDelete = null">
      <i18n-t keypath="library.delete.text" tag="p" scope="global" class="text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ toDelete.name }}</strong></template>
      </i18n-t>
      <p v-if="deleteError" role="alert" class="mt-3 text-sm text-redstone-300">{{ deleteError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="deleting" @click="confirmDelete">{{ deleting ? t('library.delete.deleting') : t('library.delete.confirm') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
