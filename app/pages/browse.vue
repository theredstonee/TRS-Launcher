<script setup lang="ts">
import type {
  CategoryTag,
  ContentKind,
  ModrinthHit,
  ModrinthSearchParams,
  ModrinthVersion,
  Platform,
  ProjectKind,
  SearchEnvironment,
  SortIndex,
} from '~/types'

const route = useRoute()
const instances = useInstancesStore()
const toasts = useToasts()
const curseforge = useCurseForgeStore()

const kinds: ProjectKind[] = ['mod', 'resourcepack', 'datapack', 'shaderpack', 'modpack']
const searchLoaders = ['fabric', 'quilt', 'forge', 'neoforge'] as const
/** Farbe der Kategorie-Icons (base-400) – im <img> gilt kein CSS. */
const ICON_COLOR = '#8b8ba2'
/** Zuletzt gewählte Quelle (nur Bequemlichkeit, fehlt im Zweifel). */
const PLATFORM_KEY = 'trs.browse.platform'

const initialKind = route.query.kind as ProjectKind
const kind = ref<ProjectKind>(kinds.includes(initialKind) ? initialKind : 'mod')
const routeInstance = typeof route.query.instance === 'string' ? route.query.instance : ''
const instanceId = ref(routeInstance)

function initialPlatform(): Platform {
  if (isPlatform(route.query.platform)) return route.query.platform
  try {
    const saved = localStorage.getItem(PLATFORM_KEY)
    return isPlatform(saved) ? saved : 'modrinth'
  } catch {
    return 'modrinth'
  }
}
const platform = ref<Platform>(initialPlatform())
const isCf = computed(() => platform.value === 'curseforge')

// --- Filter ----------------------------------------------------------------------
const query = ref('')
const debouncedQuery = ref('')
const index = ref<SortIndex>('relevance')
const limit = ref<number>(20)
const page = ref(1)
const hideInstalled = ref(false)
const includeCats = ref<string[]>([])
const excludeCats = ref<string[]>([])
const categoryMatch = ref<'all' | 'any'>('all')
const environments = ref<SearchEnvironment[]>([])
const openSource = ref(false)
/** Spielversion/Loader kommen von der Instanz, bis der Nutzer entsperrt. */
const versionLocked = ref(true)
const loaderLocked = ref(true)
const pickedVersions = ref<string[]>([])
const pickedLoaders = ref<string[]>([])
const versionFilter = ref('')

// --- Ergebnisse ------------------------------------------------------------------
const hits = ref<ModrinthHit[]>([])
const totalHits = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
const installed = ref<Set<string>>(new Set())
// Installationen gehören dem Aufgaben-Store – sie laufen weiter, wenn man die Seite verlässt.
const tasks = useTasksStore()
const picking = ref<ModrinthHit | null>(null)
const modrinthCategories = ref<CategoryTag[]>([])
const curseforgeCategories = ref<CategoryTag[]>([])
const categories = computed(() => (isCf.value ? curseforgeCategories.value : modrinthCategories.value))
const cfLabels = computed(() => categoryLabels(curseforgeCategories.value))
const gameVersions = ref<string[]>([])
const listEl = ref<HTMLElement | null>(null)

const isPack = computed(() => kind.value === 'modpack')
const hasLoaders = computed(() => kind.value === 'mod' || kind.value === 'modpack')
const target = computed(() => instances.items.find((i) => i.id === instanceId.value) ?? null)
/** Über „Inhalte installieren“ einer Instanz geöffnet (Kopfzeile statt Auswahl). */
const instanceMode = computed(() => !!routeInstance && target.value?.id === routeInstance)
/** Filter richten sich nach der Instanz (Modpacks bringen ihre eigene Version mit). */
const bound = computed(() => (!isPack.value && target.value ? target.value : null))
// Vanilla mit TRS-Optimierung läuft als Fabric und nimmt Fabric-Mods.
const instanceLoaderTags = computed(() => loaderTags[effectiveLoader(bound.value) ?? 'vanilla'])
const modsBlocked = computed(
  () => kind.value === 'mod' && target.value?.loader.kind === 'vanilla' && target.value.overrides.boost === false,
)

const versionIsLocked = computed(() => !!bound.value && versionLocked.value)
const loaderIsLocked = computed(() => !!bound.value && loaderLocked.value && instanceLoaderTags.value.length > 0)
const activeVersions = computed(() => (versionIsLocked.value ? [bound.value!.gameVersion] : pickedVersions.value))
const activeLoaders = computed(() => {
  if (!hasLoaders.value) return []
  return loaderIsLocked.value ? instanceLoaderTags.value : pickedLoaders.value
})

/** Anzeigename einer Kategorie – bei CurseForge ist `name` eine ID. */
function catLabel(name: string): string {
  return isCf.value ? (cfLabels.value.get(name) ?? name) : categoryLabel(name)
}

const kindCategories = computed(() => {
  const type = categoryTypeFor(platform.value, kind.value)
  const groups = new Map<string, CategoryTag[]>()
  for (const c of categories.value) {
    if (c.projectType !== type || !(c.header in categoryHeaderKeys)) continue
    groups.set(c.header, [...(groups.get(c.header) ?? []), c])
  }
  for (const list of groups.values()) list.sort((a, b) => compareText(catLabel(a.name), catLabel(b.name)))
  return [...groups.entries()]
})
const categoryIcons = computed(() => {
  const map = new Map<string, string>()
  for (const c of categories.value) {
    const url = c.icon ? svgIconUrl(c.icon, ICON_COLOR) : isCurseForgeImage(c.iconUrl) ? c.iconUrl : null
    if (url && !map.has(c.name)) map.set(c.name, url)
  }
  return map
})
const filteredVersions = computed(() => {
  const f = versionFilter.value.trim()
  return f ? gameVersions.value.filter((v) => v.includes(f)) : gameVersions.value
})

const params = computed<ModrinthSearchParams>(() => ({
  query: debouncedQuery.value.trim(),
  kind: kind.value,
  gameVersions: activeVersions.value,
  loaders: activeLoaders.value,
  categories: includeCats.value,
  // CurseForge kennt nur „alle Kategorien“ und keine Ausschlüsse, Umgebungen oder Lizenzen.
  categoryMatch: isCf.value ? 'all' : categoryMatch.value,
  excludeCategories: isCf.value ? [] : excludeCats.value,
  environments: hasLoaders.value && !isCf.value ? environments.value : [],
  // Modrinth blendet Installiertes selbst aus (Seitenzahlen stimmen), CurseForge nur hier im Launcher.
  excludeProjectIds:
    hideInstalled.value && bound.value && !isCf.value ? [...installed.value].filter((k) => !k.startsWith('cf:')).slice(0, 300) : [],
  openSource: isCf.value ? false : openSource.value,
  index: index.value,
  offset: (page.value - 1) * limit.value,
  limit: limit.value,
}))
/** Für Suche und Seiten-Reset: Quelle + Filter. */
const requestKey = computed(() => JSON.stringify({ platform: platform.value, ...params.value }))
const totalPages = computed(() => {
  const pages = pageCount(totalHits.value, limit.value)
  // CurseForge: index + pageSize ≤ 10 000.
  return isCf.value ? Math.max(1, Math.min(pages, Math.floor(MAX_SEARCH_OFFSET / limit.value))) : pages
})
const visibleHits = computed(() =>
  isCf.value && hideInstalled.value && bound.value ? hits.value.filter((h) => !isInstalled(h)) : hits.value,
)
const activeFilterCount = computed(
  () =>
    includeCats.value.length +
    excludeCats.value.length +
    (hasLoaders.value && !isCf.value ? environments.value.length : 0) +
    (openSource.value && !isCf.value ? 1 : 0) +
    (hideInstalled.value ? 1 : 0) +
    (versionIsLocked.value ? 0 : pickedVersions.value.length) +
    (hasLoaders.value && !loaderIsLocked.value ? pickedLoaders.value.length : 0),
)

function loadCategories() {
  if (isCf.value) {
    if (curseforgeCategories.value.length || !curseforge.available) return
    backend.curseforge
      .categories()
      .then((list) => (curseforgeCategories.value = list))
      .catch(() => {})
  } else if (!modrinthCategories.value.length) {
    backend
      .modrinthCategories()
      .then((list) => (modrinthCategories.value = list))
      .catch(() => {})
  }
}

onMounted(async () => {
  if (!instances.items.length) await instances.load()
  if (!target.value) {
    // Für Mods bevorzugt eine Instanz mit Modloader vorschlagen.
    instanceId.value = (instances.items.find((i) => i.loader.kind !== 'vanilla') ?? instances.items[0])?.id ?? ''
  }
  // Ohne API-Schlüssel im Build gibt es CurseForge nicht – dann zurück zu Modrinth.
  const available = await curseforge.load()
  if (!available && isCf.value) platform.value = 'modrinth'
  loadCategories()
})

watch(platform, (p) => {
  try {
    localStorage.setItem(PLATFORM_KEY, p)
  } catch {
    // Ohne Speicher merkt sich die Seite die Quelle eben nicht.
  }
  // Sortierung, Seitengröße und Kategorien gibt es je Quelle unterschiedlich.
  if (!sortIndexesFor(p).includes(index.value)) index.value = 'relevance'
  if (!pageSizesFor(p).includes(limit.value)) limit.value = 50
  includeCats.value = []
  excludeCats.value = []
  categoryMatch.value = 'all'
  hits.value = []
  totalHits.value = 0
  loadCategories()
})

function sortOptionLabel(o: SortIndex): string {
  return isCf.value && o === 'relevance' ? t('browse.cfPopularity') : sortLabel(o)
}

function isInstalled(hit: ModrinthHit): boolean {
  return installed.value.has(projectKey(platform.value, hit.projectId))
}

// Verwirft Antworten überholter Anfragen.
let requestNo = 0

async function search() {
  const current = ++requestNo
  const parsed = modrinthSearchSchema.safeParse(params.value)
  if (!parsed.success) {
    error.value = firstIssue(parsed.error)
    return
  }
  if (isCf.value && curseforge.available === null) await curseforge.load()
  if (isCf.value && !curseforge.available) return
  loading.value = true
  error.value = null
  try {
    const result = isCf.value ? await backend.curseforge.search(parsed.data) : await backend.modrinthSearch(parsed.data)
    if (current !== requestNo) return
    hits.value = result.hits
    totalHits.value = result.totalHits
  } catch (e) {
    if (current === requestNo) error.value = errorMessage(e)
  } finally {
    if (current === requestNo) loading.value = false
  }
}

async function loadInstalled() {
  installed.value = new Set(target.value ? await backend.installedProjects(target.value.id).catch(() => []) : [])
}

async function loadGameVersions() {
  if (gameVersions.value.length) return
  try {
    const manifest = await backend.getVersionManifest()
    gameVersions.value = manifest.versions.filter((v) => v.type === 'release').map((v) => v.id)
  } catch (e) {
    toasts.error(e)
  }
}

let debounce: ReturnType<typeof setTimeout> | undefined
watch(query, () => {
  clearTimeout(debounce)
  debounce = setTimeout(() => (debouncedQuery.value = query.value), 350)
})
onBeforeUnmount(() => clearTimeout(debounce))

// Neue Filter = wieder Seite 1. `sync`, damit die Suche unten nur einmal läuft.
watch(
  () => JSON.stringify({ platform: platform.value, ...params.value, offset: 0, excludeProjectIds: hideInstalled.value }),
  () => (page.value = 1),
  { flush: 'sync' },
)
watch(requestKey, () => search(), { immediate: true })
watch(page, () => listEl.value?.scrollTo({ top: 0 }))
watch(target, loadInstalled, { immediate: true })
// Inhalte, die (auch im Hintergrund) fertig geworden sind, als installiert markieren.
const finishedForTarget = computed(
  () =>
    Object.values(tasks.tasks).filter((task) => task.kind === 'content' && task.status === 'done' && task.instanceId === target.value?.id)
      .length,
)
watch(finishedForTarget, (n, before) => {
  if (n > (before ?? 0)) loadInstalled()
})
watch(kind, () => {
  // Kategorien gehören zur jeweiligen Art.
  includeCats.value = []
  excludeCats.value = []
})
watch(
  [bound, versionLocked, loaderLocked],
  () => {
    if (!versionIsLocked.value || !loaderIsLocked.value || !bound.value) loadGameVersions()
  },
  { immediate: true },
)

function toggled<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value]
}

function toggleInclude(name: string) {
  excludeCats.value = excludeCats.value.filter((c) => c !== name)
  includeCats.value = toggled(includeCats.value, name)
}
function toggleExclude(name: string) {
  includeCats.value = includeCats.value.filter((c) => c !== name)
  excludeCats.value = toggled(excludeCats.value, name)
}

function unlockVersion() {
  if (bound.value) pickedVersions.value = [bound.value.gameVersion]
  versionLocked.value = false
}
function unlockLoader() {
  pickedLoaders.value = [...instanceLoaderTags.value]
  loaderLocked.value = false
}

function resetFilters() {
  includeCats.value = []
  excludeCats.value = []
  environments.value = []
  openSource.value = false
  hideInstalled.value = false
  versionLocked.value = true
  loaderLocked.value = true
  pickedVersions.value = []
  pickedLoaders.value = []
}

function goToPage(n: number) {
  page.value = Math.min(Math.max(1, n), totalPages.value)
}

function detailLink(hit: ModrinthHit) {
  return projectRoute(platform.value, hit.projectId, !isPack.value && target.value ? target.value.id : null)
}

function hitLoaders(hit: ModrinthHit) {
  return hit.categories.filter((c) => c in loaderNames)
}
function hitCategories(hit: ModrinthHit) {
  return hit.categories.filter((c) => !(c in loaderNames)).slice(0, 4)
}
function loaderColor(name: string): string | undefined {
  return name in loaderColors ? loaderColors[name as keyof typeof loaderColors] : undefined
}

/** Laufende oder (in dieser Sitzung) fertige Installation zu einem Treffer. */
function hitTask(hit: ModrinthHit) {
  const key = projectKey(platform.value, hit.projectId)
  if (isPack.value) return tasks.get(modpackTaskKey(key))
  return target.value ? tasks.get(contentTaskKey(target.value.id, key)) : null
}

function install(hit: ModrinthHit, version: ModrinthVersion | null = null) {
  picking.value = null
  error.value = null
  if (isPack.value) {
    installModpackTask(hit, platform.value)
    return
  }
  if (!target.value) return
  installContentTask({
    instance: target.value,
    projectId: hit.projectId,
    title: hit.title,
    iconUrl: hit.iconUrl,
    kind: kind.value as ContentKind,
    version,
    platform: platform.value,
  })
}
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- Kopf: Instanz oder Auswahl -->
    <header class="flex items-center gap-4 px-6 pt-6 pb-4">
      <template v-if="instanceMode && target">
        <NuxtLink :to="`/instances/${target.id}`" class="btn-icon rounded-full" :aria-label="t('browse.backToInstance')">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m15 18-6-6 6-6" /></svg>
        </NuxtLink>
        <InstanceIcon :instance="target" :size="48" />
        <div class="min-w-0">
          <h1 class="truncate text-xl font-semibold tracking-tight">{{ target.name }}</h1>
          <p class="mt-0.5 flex items-center gap-1.5 text-sm text-base-400">
            {{ t('browse.installContent') }}
            <span aria-hidden="true">·</span>
            <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="m12 3 8 4.5v9L12 21l-8-4.5v-9L12 3Z" /><path d="M12 12 4 7.5M12 12l8-4.5M12 12v9" /></svg>
            Minecraft {{ target.gameVersion }}
            <span aria-hidden="true">·</span>
            {{ loaderLabels[target.loader.kind] }}
          </p>
        </div>
      </template>
      <template v-else>
        <div class="min-w-0 flex-1">
          <h1 class="text-xl font-semibold tracking-tight">{{ t('browse.title') }}</h1>
          <p class="mt-0.5 text-sm text-base-400">{{ t('browse.subtitle') }}</p>
        </div>
        <label v-if="!isPack" class="flex items-center gap-2 text-xs text-base-400">
          {{ t('browse.installInto') }}
          <select v-model="instanceId" class="field w-60 py-1.5" :disabled="!instances.items.length">
            <option v-if="!instances.items.length" value="">{{ t('browse.noInstance') }}</option>
            <option v-for="i in instances.items" :key="i.id" :value="i.id">
              {{ i.name }} ({{ i.gameVersion }}, {{ loaderLabels[i.loader.kind] }})
            </option>
          </select>
        </label>
        <p v-else class="text-xs text-base-400">{{ t('browse.packHint') }}</p>
      </template>
    </header>

    <div class="flex min-h-0 flex-1 gap-5 px-6 pb-6">
      <!-- Ergebnisse -->
      <div ref="listEl" class="min-w-0 flex-1 overflow-y-auto pr-1">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <div class="inline-flex flex-wrap rounded-full bg-base-900 p-1 ring-1 ring-base-800" role="tablist" :aria-label="t('browse.kindTabs')">
            <button
              v-for="k in kinds"
              :key="k"
              role="tab"
              :aria-selected="kind === k"
              class="tab px-4 py-1.5"
              :class="{ 'tab-on': kind === k }"
              @click="kind = k"
            >
              {{ k === 'modpack' ? t('contentKind.modpack') : contentKindLabel(k) }}
            </button>
          </div>
          <!-- Quelle: Modrinth oder CurseForge (ohne API-Schlüssel im Build ausgegraut). -->
          <div class="ml-auto inline-flex rounded-full bg-base-900 p-1 ring-1 ring-base-800" role="radiogroup" :aria-label="t('browse.source.label')">
            <button
              v-for="p in platforms"
              :key="p"
              role="radio"
              :aria-checked="platform === p"
              class="tab flex items-center gap-1.5 px-3 py-1.5 disabled:cursor-not-allowed disabled:opacity-40"
              :class="{ 'tab-on': platform === p }"
              :disabled="p === 'curseforge' && curseforge.available === false"
              :title="p === 'curseforge' && curseforge.available === false ? t('browse.source.unavailable') : undefined"
              @click="platform = p"
            >
              <span class="size-2 rounded-full" :class="p === 'curseforge' ? 'bg-[#f16436]' : 'bg-[#1bd96a]'" aria-hidden="true" />
              {{ t(`browse.source.${p}`) }}
            </button>
          </div>
        </div>

        <div class="relative mb-3">
          <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-3.5 size-4 -translate-y-1/2 text-base-400" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="11" cy="11" r="6" /><path d="m20 20-4.5-4.5" /></svg>
          <input
            v-model="query"
            class="field rounded-lg py-2.5 pl-10"
            maxlength="100"
            :placeholder="t(`browse.searchPlaceholder.${kind}`)"
            spellcheck="false"
            autofocus
            :aria-label="t('common.actions.search')"
          />
          <button v-if="query" class="absolute top-1/2 right-2 -translate-y-1/2 rounded p-1 text-base-400 hover:text-base-50" :aria-label="t('browse.clearSearch')" @click="query = ''">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 6l12 12M18 6 6 18" /></svg>
          </button>
        </div>

        <div class="mb-3 flex flex-wrap items-center gap-2">
          <label class="flex items-center gap-2 rounded-lg bg-base-900 py-1 pr-1 pl-3 text-sm text-base-400 ring-1 ring-base-800">
            {{ t('browse.sortBy') }}
            <select v-model="index" class="rounded-md bg-base-800 px-2 py-1 text-sm text-base-50 outline-none">
              <option v-for="o in sortIndexesFor(platform)" :key="o" :value="o">{{ sortOptionLabel(o) }}</option>
            </select>
          </label>
          <label class="flex items-center gap-2 rounded-lg bg-base-900 py-1 pr-1 pl-3 text-sm text-base-400 ring-1 ring-base-800">
            {{ t('browse.perPage') }}
            <select v-model.number="limit" class="rounded-md bg-base-800 px-2 py-1 text-sm text-base-50 outline-none">
              <option v-for="n in pageSizesFor(platform)" :key="n" :value="n">{{ n }}</option>
            </select>
          </label>
          <span class="text-xs text-base-400 tabular-nums">{{ t('browse.results', { count: formatCount(totalHits) }, totalHits) }}</span>

          <nav v-if="totalPages > 1" class="ml-auto flex items-center gap-1" :aria-label="t('browse.pages')">
            <button class="btn-icon size-8" :disabled="page <= 1" :aria-label="t('browse.prevPage')" @click="goToPage(page - 1)">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m15 18-6-6 6-6" /></svg>
            </button>
            <template v-for="(p, i) in pageItems(page, totalPages)" :key="i">
              <span v-if="p === null" class="px-1 text-base-400">…</span>
              <button
                v-else
                class="h-8 min-w-8 rounded-md px-2 text-sm tabular-nums transition-colors"
                :class="p === page ? 'bg-redstone-500 font-semibold text-white' : 'bg-base-800 text-base-200 hover:bg-base-700'"
                :aria-current="p === page ? 'page' : undefined"
                @click="goToPage(p)"
              >
                {{ p }}
              </button>
            </template>
            <button class="btn-icon size-8" :disabled="page >= totalPages" :aria-label="t('browse.nextPage')" @click="goToPage(page + 1)">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m9 18 6-6-6-6" /></svg>
            </button>
          </nav>
        </div>

        <!-- Aktive Filter -->
        <div v-if="versionIsLocked || loaderIsLocked || activeFilterCount" class="mb-3 flex flex-wrap items-center gap-1.5">
          <span v-if="versionIsLocked" class="chip gap-1.5 text-base-400" :title="t('browse.lockedByInstance')">
            <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="2.6"><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
            {{ bound?.gameVersion }}
          </span>
          <span v-if="hasLoaders && loaderIsLocked" class="chip gap-1.5 text-base-400" :title="t('browse.lockedByInstance')">
            <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="2.6"><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
            {{ loaderLabels[effectiveLoader(bound) ?? 'vanilla'] }}
          </span>
          <template v-if="!versionIsLocked">
            <button v-for="v in pickedVersions" :key="`v-${v}`" class="chip gap-1 hover:bg-base-700" @click="pickedVersions = toggled(pickedVersions, v)">
              {{ v }} <span aria-hidden="true" class="text-base-400">×</span>
            </button>
          </template>
          <template v-if="hasLoaders && !loaderIsLocked">
            <button v-for="l in pickedLoaders" :key="`l-${l}`" class="chip gap-1 hover:bg-base-700" @click="pickedLoaders = toggled(pickedLoaders, l)">
              {{ loaderNames[l] ?? l }} <span aria-hidden="true" class="text-base-400">×</span>
            </button>
          </template>
          <button v-for="c in includeCats" :key="`i-${c}`" class="chip gap-1 hover:bg-base-700" @click="toggleInclude(c)">
            {{ catLabel(c) }} <span aria-hidden="true" class="text-base-400">×</span>
          </button>
          <button v-for="c in excludeCats" :key="`e-${c}`" class="chip gap-1 text-redstone-300 line-through hover:bg-base-700" @click="toggleExclude(c)">
            {{ catLabel(c) }} <span aria-hidden="true" class="text-base-400 no-underline">×</span>
          </button>
          <template v-if="hasLoaders && !isCf">
            <button v-for="e in environments" :key="`env-${e}`" class="chip gap-1 hover:bg-base-700" @click="environments = toggled(environments, e)">
              {{ t(`modrinth.environment.${e}`) }} <span aria-hidden="true" class="text-base-400">×</span>
            </button>
          </template>
          <button v-if="openSource && !isCf" class="chip gap-1 hover:bg-base-700" @click="openSource = false">
            {{ t('browse.chips.openSource') }} <span aria-hidden="true" class="text-base-400">×</span>
          </button>
          <button v-if="hideInstalled" class="chip gap-1 hover:bg-base-700" @click="hideInstalled = false">
            {{ t('browse.chips.hideInstalled') }} <span aria-hidden="true" class="text-base-400">×</span>
          </button>
          <button v-if="activeFilterCount" class="ml-1 text-xs text-base-400 underline-offset-2 hover:text-base-50 hover:underline" @click="resetFilters">
            {{ t('browse.chips.resetAll') }}
          </button>
        </div>

        <!-- Herkunft: dezenter Hinweis, die Projekte gehören ihren Autoren. -->
        <p v-if="isCf" class="mb-3 flex items-center gap-2 text-xs text-base-400">
          <span class="size-1.5 rounded-full bg-[#f16436]" aria-hidden="true" />
          {{ t('browse.viaCurseForge') }}
        </p>
        <p v-if="error" role="alert" class="card mb-3 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ error }}</p>
        <p v-if="modsBlocked" class="card mb-3 border-warn/40 px-4 py-2.5 text-sm text-warn">
          {{ t('browse.vanillaBlocked', { name: target?.name ?? '' }) }}
        </p>
        <i18n-t
          v-if="kind === 'datapack' && !isPack"
          keypath="browse.datapackHint.text"
          tag="p"
          scope="global"
          class="card mb-3 px-4 py-2.5 text-xs leading-relaxed text-base-400"
        >
          <template #folder><span class="font-mono text-base-200">datapacks</span></template>
          <template #worldFolder><span class="font-mono text-base-200">saves/&lt;{{ t('browse.datapackHint.world') }}&gt;/datapacks</span></template>
        </i18n-t>

        <ul v-if="loading && !hits.length" class="flex flex-col gap-2.5">
          <li v-for="i in 6" :key="i" class="skeleton h-[124px] rounded-xl" />
        </ul>

        <ul v-else class="flex flex-col gap-2.5 transition-opacity" :class="{ 'opacity-60': loading }">
          <li v-for="hit in visibleHits" :key="hit.projectId" class="card card-hover group flex gap-4 p-4">
            <NuxtLink :to="detailLink(hit)" class="flex min-w-0 flex-1 gap-4 rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-redstone-500">
              <ModIcon :src="hit.iconUrl" :name="hit.title" :size="80" />
              <div class="min-w-0 flex-1">
                <p class="flex items-baseline gap-2">
                  <span class="truncate text-base font-semibold group-hover:text-redstone-300">{{ hit.title }}</span>
                  <span class="shrink-0 truncate text-xs text-base-400">{{ t('browse.hit.by', { author: hit.author }) }}</span>
                </p>
                <p class="mt-1 line-clamp-2 text-sm leading-relaxed text-base-200">{{ hit.description }}</p>
                <div class="mt-2.5 flex flex-wrap items-center gap-1.5 text-[11px]">
                  <span v-if="hasLoaders && environmentLabel(hit)" class="badge bg-base-800 text-base-200">
                    <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="2.4"><rect x="3" y="4" width="18" height="12" rx="2" /><path d="M8 20h8M12 16v4" /></svg>
                    {{ environmentLabel(hit) }}
                  </span>
                  <span v-for="c in hitCategories(hit)" :key="c" class="badge bg-base-800 text-base-200">
                    <img v-if="categoryIcons.get(c)" :src="categoryIcons.get(c)" alt="" class="size-3" draggable="false" />
                    {{ catLabel(c) }}
                  </span>
                  <span
                    v-for="l in hitLoaders(hit)"
                    :key="l"
                    class="badge bg-base-800"
                    :style="loaderColor(l) ? { color: loaderColor(l) } : undefined"
                    :class="{ 'text-base-400': !loaderColor(l) }"
                  >
                    {{ loaderNames[l] }}
                  </span>
                </div>
              </div>
            </NuxtLink>

            <div class="flex w-40 shrink-0 flex-col items-end justify-between gap-2">
              <div class="flex w-full flex-col items-stretch gap-1">
                <template v-if="hitTask(hit)?.status === 'running'">
                  <button
                    class="display text-center text-sm tabular-nums text-redstone-300 hover:text-redstone-200"
                    :aria-label="t('browse.hit.installingLabel', { title: hit.title })"
                    @click="tasks.openPanel(hitTask(hit)!.key)"
                  >
                    {{ hitTask(hit)!.percent === null ? t('common.status.loading') : t('browse.hit.percent', { percent: hitTask(hit)!.percent! }) }}
                  </button>
                  <RedstoneWire :percent="hitTask(hit)!.percent ?? 50" :segments="12" />
                </template>
                <NuxtLink
                  v-else-if="isPack && hitTask(hit)?.status === 'done' && hitTask(hit)!.instanceId"
                  :to="`/instances/${hitTask(hit)!.instanceId}`"
                  class="inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium text-ok ring-1 ring-ok/50 hover:bg-base-800"
                >
                  {{ t('browse.hit.openInstance') }}
                </NuxtLink>
                <span
                  v-else-if="!isPack && isInstalled(hit)"
                  class="inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium text-ok ring-1 ring-ok/50"
                >
                  <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.6"><path d="m5 12 5 5 9-10" /></svg>
                  {{ t('browse.hit.installed') }}
                </span>
                <template v-else>
                  <button
                    class="inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium text-redstone-300 ring-1 ring-redstone-500/60 transition-colors hover:bg-redstone-900/60 hover:text-redstone-300 disabled:cursor-not-allowed disabled:opacity-40"
                    :disabled="!isPack && (!target || modsBlocked)"
                    @click="install(hit)"
                  >
                    <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.6"><path d="M12 5v14M5 12h14" /></svg>
                    {{ isPack ? t('browse.hit.asInstance') : t('common.actions.install') }}
                  </button>
                  <button
                    v-if="!isPack"
                    class="text-[11px] text-base-400 hover:text-base-50 disabled:opacity-40"
                    :disabled="!target || modsBlocked"
                    @click="picking = hit"
                  >
                    {{ t('browse.hit.chooseVersion') }}
                  </button>
                </template>
              </div>
              <div class="flex flex-col items-end gap-0.5 text-xs text-base-400 tabular-nums">
                <span class="flex items-center gap-1.5" :title="t('browse.hit.downloads', { count: formatNumber(hit.downloads) }, hit.downloads)">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M12 4v11m0 0-4-4m4 4 4-4M5 20h14" /></svg>
                  <span class="font-medium text-base-200">{{ formatCount(hit.downloads) }}</span>
                </span>
                <span v-if="!isCf" class="flex items-center gap-1.5" :title="t('browse.hit.followers', { count: formatNumber(hit.follows) }, hit.follows)">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10Z" /></svg>
                  <span class="font-medium text-base-200">{{ formatCount(hit.follows) }}</span>
                </span>
                <span v-if="hit.dateModified" class="flex items-center gap-1.5" :title="formatDate(hit.dateModified)">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M20 12a8 8 0 1 1-2.3-5.6M20 4v5h-5" /></svg>
                  {{ formatRelative(hit.dateModified) }}
                </span>
              </div>
            </div>
          </li>
        </ul>

        <RedstoneEmpty
          v-if="!loading && !visibleHits.length && !error"
          compact
          :seed="0x88"
          :title="t('browse.empty.title')"
          :text="bound ? t('browse.empty.textFor', { versions: activeVersions.join(', ') || t('browse.empty.allVersions') }) : t('browse.empty.text')"
        >
          <button v-if="activeFilterCount" class="btn btn-ghost" @click="resetFilters">{{ t('browse.empty.resetFilters') }}</button>
        </RedstoneEmpty>

        <nav v-if="totalPages > 1 && hits.length" class="flex items-center justify-center gap-1 py-4" :aria-label="t('browse.pagesBottom')">
          <button class="btn-icon size-8" :disabled="page <= 1" :aria-label="t('browse.prevPage')" @click="goToPage(page - 1)">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m15 18-6-6 6-6" /></svg>
          </button>
          <template v-for="(p, i) in pageItems(page, totalPages)" :key="i">
            <span v-if="p === null" class="px-1 text-base-400">…</span>
            <button
              v-else
              class="h-8 min-w-8 rounded-md px-2 text-sm tabular-nums transition-colors"
              :class="p === page ? 'bg-redstone-500 font-semibold text-white' : 'bg-base-800 text-base-200 hover:bg-base-700'"
              :aria-current="p === page ? 'page' : undefined"
              @click="goToPage(p)"
            >
              {{ p }}
            </button>
          </template>
          <button class="btn-icon size-8" :disabled="page >= totalPages" :aria-label="t('browse.nextPage')" @click="goToPage(page + 1)">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m9 18 6-6-6-6" /></svg>
          </button>
        </nav>
      </div>

      <!-- Filterleiste -->
      <aside class="card max-h-full w-72 shrink-0 self-start overflow-y-auto px-4 py-1" :aria-label="t('browse.filters.label')">
        <section v-if="bound" class="border-b border-base-800 py-3">
          <label class="flex cursor-pointer items-center justify-between gap-3 text-sm text-base-200">
            {{ t('browse.filters.hideInstalled') }}
            <button
              role="switch"
              :aria-checked="hideInstalled"
              class="relative h-5 w-9 shrink-0 rounded-full transition-colors"
              :class="hideInstalled ? 'bg-redstone-500' : 'bg-base-700'"
              @click="hideInstalled = !hideInstalled"
            >
              <span class="absolute top-0.5 left-0.5 size-4 rounded-full bg-white transition-transform" :class="{ 'translate-x-4': hideInstalled }" />
            </button>
          </label>
        </section>

        <FilterSection :title="t('common.labels.gameVersion')" :locked="versionIsLocked" :count="versionIsLocked ? 0 : pickedVersions.length">
          <div v-if="versionIsLocked" class="rounded-lg bg-base-850 p-3 text-xs leading-relaxed text-base-400">
            <p>{{ t('browse.filters.versionLocked', { version: bound?.gameVersion ?? '' }) }}</p>
            <button class="btn btn-ghost mt-2 w-full py-1.5 text-xs" @click="unlockVersion">
              <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4"><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V8a4 4 0 0 1 7.5-2" /></svg>
              {{ t('browse.filters.unlock') }}
            </button>
          </div>
          <template v-else>
            <input v-model="versionFilter" class="field mb-2 py-1.5 text-xs" :placeholder="t('browse.filters.searchVersionPlaceholder')" maxlength="32" spellcheck="false" :aria-label="t('browse.filters.searchVersionLabel')" />
            <ul class="max-h-56 overflow-y-auto pr-1">
              <li v-for="v in filteredVersions" :key="v">
                <label class="flex cursor-pointer items-center gap-2.5 rounded-md px-1.5 py-1 text-sm text-base-200 hover:bg-base-850">
                  <input type="checkbox" class="accent-redstone-500" :checked="pickedVersions.includes(v)" @change="pickedVersions = toggled(pickedVersions, v)" />
                  {{ v }}
                </label>
              </li>
              <li v-if="!filteredVersions.length" class="px-1.5 py-1 text-xs text-base-400">{{ t('browse.filters.noVersion') }}</li>
            </ul>
            <button v-if="bound" class="mt-2 text-xs text-base-400 hover:text-base-50" @click="versionLocked = true">{{ t('browse.filters.rebind') }}</button>
          </template>
        </FilterSection>

        <FilterSection v-if="hasLoaders" :title="t('browse.filters.loader')" :locked="loaderIsLocked" :count="loaderIsLocked ? 0 : pickedLoaders.length">
          <div v-if="loaderIsLocked" class="rounded-lg bg-base-850 p-3 text-xs leading-relaxed text-base-400">
            <p>{{ t('browse.filters.loaderLocked', { loader: loaderLabels[effectiveLoader(bound) ?? 'vanilla'] }) }}</p>
            <button class="btn btn-ghost mt-2 w-full py-1.5 text-xs" @click="unlockLoader">
              <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4"><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V8a4 4 0 0 1 7.5-2" /></svg>
              {{ t('browse.filters.unlock') }}
            </button>
          </div>
          <template v-else>
            <label v-for="l in searchLoaders" :key="l" class="flex cursor-pointer items-center gap-2.5 rounded-md px-1.5 py-1 text-sm text-base-200 hover:bg-base-850">
              <input type="checkbox" class="accent-redstone-500" :checked="pickedLoaders.includes(l)" @change="pickedLoaders = toggled(pickedLoaders, l)" />
              <span class="size-2 rounded-full" :style="{ background: loaderColors[l] }" />
              {{ loaderNames[l] }}
            </label>
            <button v-if="bound && instanceLoaderTags.length" class="mt-2 text-xs text-base-400 hover:text-base-50" @click="loaderLocked = true">
              {{ t('browse.filters.rebind') }}
            </button>
          </template>
        </FilterSection>

        <FilterSection
          v-for="[header, list] in kindCategories"
          :key="header"
          :title="categoryHeaderLabel(header)"
          :count="list.filter((c) => includeCats.includes(c.name) || excludeCats.includes(c.name)).length"
        >
          <div v-if="!isCf && header === 'categories' && includeCats.length > 1" class="mb-2 flex rounded-md bg-base-850 p-0.5 text-xs">
            <button class="seg flex-1 rounded" :class="{ 'seg-on': categoryMatch === 'all' }" @click="categoryMatch = 'all'">{{ t('common.labels.all') }}</button>
            <button class="seg flex-1 rounded" :class="{ 'seg-on': categoryMatch === 'any' }" @click="categoryMatch = 'any'">{{ t('browse.filters.matchAny') }}</button>
          </div>
          <ul>
            <li v-for="c in list" :key="c.name" class="group/cat flex items-center gap-1 rounded-md hover:bg-base-850">
              <label class="flex min-w-0 flex-1 cursor-pointer items-center gap-2.5 px-1.5 py-1 text-sm" :class="excludeCats.includes(c.name) ? 'text-redstone-300 line-through' : 'text-base-200'">
                <input type="checkbox" class="accent-redstone-500" :checked="includeCats.includes(c.name)" @change="toggleInclude(c.name)" />
                <img v-if="categoryIcons.get(c.name)" :src="categoryIcons.get(c.name)" alt="" class="size-4 shrink-0" draggable="false" />
                <span class="truncate">{{ catLabel(c.name) }}</span>
              </label>
              <button
                v-if="!isCf"
                class="mr-1 rounded p-1 text-base-400 transition-opacity hover:text-redstone-300"
                :class="excludeCats.includes(c.name) ? 'text-redstone-300 opacity-100' : 'opacity-0 group-hover/cat:opacity-100 focus-visible:opacity-100'"
                :aria-pressed="excludeCats.includes(c.name)"
                :aria-label="t('browse.filters.exclude', { category: catLabel(c.name) })"
                :title="t('browse.filters.exclude', { category: catLabel(c.name) })"
                @click="toggleExclude(c.name)"
              >
                <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4"><circle cx="12" cy="12" r="8" /><path d="m6.5 6.5 11 11" /></svg>
              </button>
            </li>
          </ul>
        </FilterSection>
        <p v-if="!kindCategories.length" class="border-b border-base-800 py-3 text-xs text-base-400">{{ t('browse.filters.loadingCategories') }}</p>

        <FilterSection v-if="hasLoaders && !isCf" :title="t('browse.filters.environment')" :count="environments.length">
          <label v-for="e in (['client', 'server'] as const)" :key="e" class="flex cursor-pointer items-center gap-2.5 rounded-md px-1.5 py-1 text-sm text-base-200 hover:bg-base-850">
            <input type="checkbox" class="accent-redstone-500" :checked="environments.includes(e)" @change="environments = toggled(environments, e)" />
            {{ t(`modrinth.environment.${e}`) }}
          </label>
        </FilterSection>

        <FilterSection v-if="!isCf" :title="t('browse.filters.license')" :count="openSource ? 1 : 0">
          <label class="flex cursor-pointer items-center justify-between gap-3 px-1.5 py-1 text-sm text-base-200">
            {{ t('browse.filters.openSourceOnly') }}
            <button
              role="switch"
              :aria-checked="openSource"
              class="relative h-5 w-9 shrink-0 rounded-full transition-colors"
              :class="openSource ? 'bg-redstone-500' : 'bg-base-700'"
              @click="openSource = !openSource"
            >
              <span class="absolute top-0.5 left-0.5 size-4 rounded-full bg-white transition-transform" :class="{ 'translate-x-4': openSource }" />
            </button>
          </label>
        </FilterSection>
      </aside>
    </div>

    <VersionPickerDialog
      v-if="picking && target && !isPack"
      :instance="target"
      :project-id="picking.projectId"
      :title="picking.title"
      :kind="kind as ContentKind"
      :platform="platform"
      @close="picking = null"
      @pick="install(picking!, $event)"
    />
  </div>
</template>
