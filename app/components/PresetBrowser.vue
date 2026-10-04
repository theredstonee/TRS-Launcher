<script setup lang="ts">
import type { CategoryTag, ContentKind, ModrinthHit, ModrinthSearchParams, Platform, PresetItem, SortIndex } from '~/types'
import { MobileSheet } from '#components'

// Suche im Preset-Editor – wie die Entdecken-Seite (Arten, Quelle, Suche, Sortierung,
// Kategorien, Loader), aber versionsunabhängig und mit endlosem Nachladen. Jede Kachel
// fügt ihr Projekt dem Preset hinzu bzw. nimmt es wieder heraus.
const props = defineProps<{ items: PresetItem[]; full: boolean }>()
const emit = defineEmits<{ add: [item: PresetItem]; remove: [item: PresetItem] }>()

const curseforge = useCurseForgeStore()
const mobile = mobileUi
/** Farbe der Kategorie-Icons (base-400) – im <img> gilt kein CSS. */
const ICON_COLOR = '#8b8ba2'
const PAGE = 20
const searchLoaders = ['fabric', 'quilt', 'forge', 'neoforge'] as const

const kind = ref<ContentKind>('mod')
const platform = ref<Platform>('modrinth')
const isCf = computed(() => platform.value === 'curseforge')
const query = ref('')
const debouncedQuery = ref('')
const index = ref<SortIndex>('relevance')
const includeCats = ref<string[]>([])
const pickedLoaders = ref<string[]>([])
const clientOnly = ref(false)
const filtersOpen = ref(false)

const hits = ref<ModrinthHit[]>([])
const totalHits = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
const modrinthCategories = ref<CategoryTag[]>([])
const curseforgeCategories = ref<CategoryTag[]>([])
const categories = computed(() => (isCf.value ? curseforgeCategories.value : modrinthCategories.value))
const cfLabels = computed(() => categoryLabels(curseforgeCategories.value))
const sentinel = ref<HTMLElement | null>(null)

const hasLoaders = computed(() => kind.value === 'mod')
const kindCategories = computed(() => {
  const type = categoryTypeFor(platform.value, kind.value)
  return categories.value
    .filter((c) => c.projectType === type && c.header in categoryHeaderKeys)
    .sort((a, b) => compareText(catLabel(a.name), catLabel(b.name)))
})
const categoryIcons = computed(() => {
  const map = new Map<string, string>()
  for (const c of categories.value) {
    const url = c.icon ? svgIconUrl(c.icon, ICON_COLOR) : isCurseForgeImage(c.iconUrl) ? c.iconUrl : null
    if (url && !map.has(c.name)) map.set(c.name, url)
  }
  return map
})
const activeFilterCount = computed(
  () => includeCats.value.length + (hasLoaders.value ? pickedLoaders.value.length : 0) + (clientOnly.value && !isCf.value ? 1 : 0),
)

function catLabel(name: string): string {
  return isCf.value ? (cfLabels.value.get(name) ?? name) : categoryLabel(name)
}

const params = computed<Omit<ModrinthSearchParams, 'offset'>>(() => ({
  query: debouncedQuery.value.trim(),
  kind: kind.value,
  gameVersions: [],
  loaders: hasLoaders.value ? pickedLoaders.value : [],
  categories: includeCats.value,
  categoryMatch: 'all',
  excludeCategories: [],
  environments: hasLoaders.value && clientOnly.value && !isCf.value ? ['client'] : [],
  excludeProjectIds: [],
  openSource: false,
  index: index.value,
  limit: PAGE,
}))
const requestKey = computed(() => JSON.stringify({ platform: platform.value, ...params.value }))
const canLoadMore = computed(() => hits.value.length < Math.min(totalHits.value, MAX_SEARCH_OFFSET - PAGE) && !error.value)

let requestNo = 0
async function search(append = false) {
  const current = ++requestNo
  const parsed = modrinthSearchSchema.safeParse({ ...params.value, offset: append ? hits.value.length : 0 })
  if (!parsed.success) {
    error.value = firstIssue(parsed.error)
    return
  }
  if (isCf.value && !(await curseforge.load())) return
  loading.value = true
  error.value = null
  try {
    const result = isCf.value ? await backend.curseforge.search(parsed.data) : await backend.modrinthSearch(parsed.data)
    if (current !== requestNo) return
    // Beim Nachladen nichts doppelt zeigen (Sortierung kann sich zwischendurch verschieben).
    const seen = new Set(append ? hits.value.map((h) => h.projectId) : [])
    hits.value = [...(append ? hits.value : []), ...result.hits.filter((h) => !seen.has(h.projectId))]
    totalHits.value = result.totalHits
  } catch (e) {
    if (current === requestNo) error.value = errorMessage(e)
  } finally {
    if (current === requestNo) loading.value = false
  }
}

function loadMore() {
  if (!loading.value && canLoadMore.value) search(true)
}

function loadCategories() {
  if (isCf.value) {
    if (curseforgeCategories.value.length || !curseforge.available) return
    backend.curseforge.categories().then((list) => (curseforgeCategories.value = list)).catch(() => {})
  } else if (!modrinthCategories.value.length) {
    backend.modrinthCategories().then((list) => (modrinthCategories.value = list)).catch(() => {})
  }
}

let debounce: ReturnType<typeof setTimeout> | undefined
watch(query, () => {
  clearTimeout(debounce)
  debounce = setTimeout(() => (debouncedQuery.value = query.value), 350)
})
watch(requestKey, () => search(false), { immediate: true })
watch(kind, () => (includeCats.value = []))
watch(platform, (p) => {
  if (!sortIndexesFor(p).includes(index.value)) index.value = 'relevance'
  includeCats.value = []
  hits.value = []
  totalHits.value = 0
  loadCategories()
})

// Endloses Nachladen: der Platzhalter am Ende der Liste kommt in Sicht.
let observer: IntersectionObserver | null = null
onMounted(async () => {
  await curseforge.load()
  loadCategories()
  observer = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && loadMore(), { rootMargin: '400px' })
  if (sentinel.value) observer.observe(sentinel.value)
})
watch(sentinel, (el, old) => {
  if (old) observer?.unobserve(old)
  if (el) observer?.observe(el)
})
onBeforeUnmount(() => {
  clearTimeout(debounce)
  observer?.disconnect()
})

const addedKeys = computed(() => new Set(props.items.map(presetItemKey)))
function isAdded(hit: ModrinthHit): boolean {
  return addedKeys.value.has(`${platform.value}:${hit.projectId}`)
}
function toggle(hit: ModrinthHit) {
  const item = hitToItem(hit, platform.value, kind.value)
  if (isAdded(hit)) emit('remove', item)
  else emit('add', item)
}

function hitCategories(hit: ModrinthHit) {
  return hit.categories.filter((c) => !(c in loaderNames)).slice(0, 3)
}
function hitLoaders(hit: ModrinthHit) {
  return hit.categories.filter((c) => c in loaderNames)
}
function loaderColor(name: string): string | undefined {
  return name in loaderColors ? loaderColors[name as keyof typeof loaderColors] : undefined
}
function toggled<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value]
}
function resetFilters() {
  includeCats.value = []
  pickedLoaders.value = []
  clientOnly.value = false
}
</script>

<template>
  <section class="flex min-h-0 flex-col" :aria-label="t('presets.editor.searchLabel')">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <div class="inline-flex rounded-full bg-base-900 p-1 ring-1 ring-base-800 mobile-scroll-x mobile:flex mobile:w-full" role="tablist" :aria-label="t('browse.kindTabs')">
        <button
          v-for="k in presetKinds"
          :key="k"
          type="button"
          role="tab"
          :aria-selected="kind === k"
          class="tab px-4 py-1.5 mobile:shrink-0 mobile:px-3.5"
          :class="{ 'tab-on': kind === k }"
          @click="kind = k"
        >
          {{ contentKindLabel(k) }}
        </button>
      </div>
      <div class="ml-auto inline-flex rounded-full bg-base-900 p-1 ring-1 ring-base-800 mobile:ml-0" role="radiogroup" :aria-label="t('browse.source.label')">
        <button
          v-for="p in platforms"
          :key="p"
          type="button"
          role="radio"
          :aria-checked="platform === p"
          class="tab px-3 py-1.5 disabled:cursor-not-allowed disabled:opacity-40"
          :class="{ 'tab-on': platform === p }"
          :disabled="p === 'curseforge' && curseforge.available === false"
          :title="p === 'curseforge' && curseforge.available === false ? t('browse.source.unavailable') : undefined"
          @click="platform = p"
        >
          {{ t(`browse.source.${p}`) }}
        </button>
      </div>
    </div>

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <div class="relative min-w-60 flex-1">
        <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-3.5 size-4 -translate-y-1/2 text-base-400" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="11" cy="11" r="6" /><path d="m20 20-4.5-4.5" /></svg>
        <input
          v-model="query"
          class="field rounded-lg py-2.5 pl-10"
          maxlength="100"
          :placeholder="t(`browse.searchPlaceholder.${kind}`)"
          spellcheck="false"
          :aria-label="t('presets.editor.searchLabel')"
          data-testid="preset-search"
        />
        <button v-if="query" type="button" class="absolute top-1/2 right-2 -translate-y-1/2 rounded p-1 text-base-400 hover:text-base-50" :aria-label="t('browse.clearSearch')" @click="query = ''">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 6l12 12M18 6 6 18" /></svg>
        </button>
      </div>
      <label class="flex items-center gap-2 rounded-lg bg-base-900 py-1 pr-1 pl-3 text-sm text-base-400 ring-1 ring-base-800 mobile:min-h-11">
        <span class="mobile:sr-only">{{ t('browse.sortBy') }}</span>
        <select v-model="index" class="rounded-md bg-base-800 px-2 py-1 text-sm text-base-50 outline-none">
          <option v-for="o in sortIndexesFor(platform)" :key="o" :value="o">{{ isCf && o === 'relevance' ? t('browse.cfPopularity') : sortLabel(o) }}</option>
        </select>
      </label>
      <button type="button" class="btn btn-ghost" :aria-expanded="filtersOpen" @click="filtersOpen = !filtersOpen">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.filter" /></svg>
        {{ t('browse.filters.label') }}
        <span v-if="activeFilterCount" class="rounded-full bg-redstone-500 px-1.5 text-[11px] leading-4 font-bold text-white tabular-nums">{{ activeFilterCount }}</span>
      </button>
    </div>

    <!-- Filter: Loader, nur Client, Kategorien (am Handy als Sheet) -->
    <component
      :is="mobile ? MobileSheet : 'div'"
      v-if="filtersOpen"
      v-bind="mobile ? { title: t('browse.filters.label'), onClose: () => (filtersOpen = false) } : { class: 'card mb-3 space-y-3 px-4 py-3' }"
    >
      <div v-if="hasLoaders" class="flex flex-wrap items-center gap-1.5">
        <span class="mr-1 text-xs text-base-400">{{ t('browse.filters.loader') }}</span>
        <button
          v-for="l in searchLoaders"
          :key="l"
          type="button"
          class="chip gap-1.5 mobile:min-h-11"
          :class="pickedLoaders.includes(l) ? 'bg-base-700 text-base-50 ring-1 ring-redstone-500/60' : 'text-base-400 hover:text-base-50'"
          :aria-pressed="pickedLoaders.includes(l)"
          @click="pickedLoaders = toggled(pickedLoaders, l)"
        >
          <span class="size-2 rounded-full" :style="{ background: loaderColors[l] }" />
          {{ loaderNames[l] }}
        </button>
        <button
          v-if="!isCf"
          type="button"
          class="chip ml-2 mobile:min-h-11"
          :class="clientOnly ? 'bg-base-700 text-base-50 ring-1 ring-redstone-500/60' : 'text-base-400 hover:text-base-50'"
          :aria-pressed="clientOnly"
          @click="clientOnly = !clientOnly"
        >
          {{ t('modrinth.environment.client') }}
        </button>
      </div>
      <div class="flex flex-wrap gap-1.5 mobile:mt-3">
        <button
          v-for="c in kindCategories"
          :key="c.name"
          type="button"
          class="chip gap-1.5 mobile:min-h-11"
          :class="includeCats.includes(c.name) ? 'bg-base-700 text-base-50 ring-1 ring-redstone-500/60' : 'text-base-400 hover:text-base-50'"
          :aria-pressed="includeCats.includes(c.name)"
          @click="includeCats = toggled(includeCats, c.name)"
        >
          <img v-if="categoryIcons.get(c.name)" :src="categoryIcons.get(c.name)" alt="" class="size-3.5" draggable="false" />
          {{ catLabel(c.name) }}
        </button>
        <p v-if="!kindCategories.length" class="text-xs text-base-400">{{ t('browse.filters.loadingCategories') }}</p>
      </div>
      <template v-if="mobile" #actions>
        <button v-if="activeFilterCount" type="button" class="btn btn-ghost" @click="resetFilters">{{ t('browse.chips.resetAll') }}</button>
        <button type="button" class="btn btn-primary flex-1" @click="filtersOpen = false">{{ t('mobile.browse.showResults', { count: formatCount(totalHits) }, totalHits) }}</button>
      </template>
    </component>

    <div class="mb-3 flex items-center gap-3 text-xs text-base-400">
      <span class="tabular-nums">{{ t('browse.results', { count: formatCount(totalHits) }, totalHits) }}</span>
      <span v-if="isCf" class="flex items-center gap-1.5"><span class="size-1.5 rounded-full bg-[#f16436]" aria-hidden="true" />{{ t('browse.viaCurseForge') }}</span>
      <button v-if="activeFilterCount" type="button" class="ml-auto underline-offset-2 hover:text-base-50 hover:underline" @click="resetFilters">{{ t('browse.chips.resetAll') }}</button>
    </div>

    <p v-if="error" role="alert" class="card mb-3 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ error }}</p>

    <ul class="grid gap-3" :class="full ? 'grid-cols-[repeat(auto-fill,minmax(340px,1fr))]' : 'grid-cols-[repeat(auto-fill,minmax(300px,1fr))]'" :aria-busy="loading">
      <li
        v-for="hit in hits"
        :key="hit.projectId"
        class="card card-hover group flex flex-col gap-3 p-4 mobile:p-3"
        :class="{ 'border-ok/40': isAdded(hit) }"
      >
        <div class="flex min-w-0 gap-3">
          <ModIcon :src="hit.iconUrl" :name="hit.title" :size="mobile ? 48 : 64" />
          <div class="min-w-0 flex-1">
            <p class="truncate font-semibold group-hover:text-redstone-300">{{ hit.title }}</p>
            <p class="truncate text-xs text-base-400">{{ t('browse.hit.by', { author: hit.author }) }}</p>
            <p class="mt-1 line-clamp-2 text-sm leading-snug text-base-200">{{ hit.description }}</p>
          </div>
        </div>
        <div class="flex flex-wrap items-center gap-1.5 text-[11px]">
          <span v-for="c in hitCategories(hit)" :key="c" class="badge bg-base-800 text-base-200">
            <img v-if="categoryIcons.get(c)" :src="categoryIcons.get(c)" alt="" class="size-3" draggable="false" />
            {{ catLabel(c) }}
          </span>
          <span v-for="l in hitLoaders(hit)" :key="l" class="badge bg-base-800" :style="loaderColor(l) ? { color: loaderColor(l) } : undefined">{{ loaderNames[l] }}</span>
        </div>
        <div class="mt-auto flex items-center gap-3">
          <span class="flex items-center gap-1.5 text-xs text-base-400 tabular-nums" :title="t('browse.hit.downloads', { count: formatNumber(hit.downloads) }, hit.downloads)">
            <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M12 4v11m0 0-4-4m4 4 4-4M5 20h14" /></svg>
            <span class="font-medium text-base-200">{{ formatCount(hit.downloads) }}</span>
          </span>
          <button
            type="button"
            class="ml-auto inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium ring-1 transition-colors mobile:min-h-11"
            :class="isAdded(hit) ? 'text-ok ring-ok/50 hover:bg-base-800' : 'text-redstone-300 ring-redstone-500/60 hover:bg-redstone-900/60'"
            :aria-pressed="isAdded(hit)"
            :aria-label="isAdded(hit) ? t('presets.editor.remove', { title: hit.title }) : t('presets.editor.addTitle', { title: hit.title })"
            @click="toggle(hit)"
          >
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.6"><path :d="isAdded(hit) ? 'm5 12 5 5 9-10' : 'M12 5v14M5 12h14'" /></svg>
            {{ isAdded(hit) ? t('presets.editor.added') : t('presets.editor.add') }}
          </button>
        </div>
      </li>
      <template v-if="loading">
        <li v-for="i in hits.length ? 3 : 9" :key="`s${i}`" class="skeleton h-[168px] rounded-xl" />
      </template>
    </ul>

    <RedstoneEmpty v-if="!loading && !hits.length && !error" compact :seed="0x5e" :title="t('presets.editor.noResults')" :text="t('presets.editor.hint')">
      <button v-if="activeFilterCount" type="button" class="btn btn-ghost" @click="resetFilters">{{ t('browse.empty.resetFilters') }}</button>
    </RedstoneEmpty>
    <div ref="sentinel" class="h-8" aria-hidden="true" />
    <button v-if="canLoadMore && !loading && hits.length" type="button" class="btn btn-ghost mx-auto mb-4" @click="loadMore">{{ t('presets.editor.loadMore') }}</button>
  </section>
</template>
