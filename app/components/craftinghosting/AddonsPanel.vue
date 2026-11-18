<script setup lang="ts">
import type { ModrinthHit } from '~/types'
import { type CraftingModsResult, type ServerAddon, addonConfigPath, addonKind, addonSearchFacets, serverGameVersion } from '~/utils/craftinghosting'

// Plugins (Paper/Purpur) bzw. Mods (Fabric/Forge) des Servers: Liste, entfernen, Konfiguration
// im Dateien-Reiter öffnen, auf Modrinth suchen und installieren. Gilt ab dem nächsten Start.
const emit = defineEmits<{ files: [] }>()
const store = useCraftingHostingStore()
const toasts = useToasts()

const server = computed(() => store.server!)
const kind = computed(() => store.addons?.kind ?? addonKind(server.value.serverType))
const plugins = computed(() => kind.value === 'plugins')
const installing = computed(() => server.value.state === 'installing')
const items = computed(() =>
  [...(store.addons?.items ?? [])].sort((a, b) => Number(b.protected) - Number(a.protected) || a.file.localeCompare(b.file)),
)
const typeName = computed(() => store.info?.serverTypes.find((ty) => ty.id === server.value.serverType)?.name ?? server.value.serverType)
const gameVersion = computed(() => serverGameVersion(server.value.serverType, server.value.version, store.info))

onMounted(() => {
  if (!installing.value) void store.loadAddons()
  void search(true)
})

// --- Entfernen und Konfiguration --------------------------------------------------------

const removing = ref<ServerAddon | null>(null)
async function remove() {
  const item = removing.value
  removing.value = null
  if (item) await store.removeAddon(item.file)
}

const opening = ref<string | null>(null)
async function openConfig(item: ServerAddon) {
  if (!kind.value) return
  opening.value = item.file
  try {
    let folders: string[] = []
    if (kind.value === 'plugins') {
      const dir = await backend.craftinghosting.files('/plugins').catch(() => null)
      folders = dir?.entries.filter((e) => e.type === 'directory').map((e) => e.name) ?? []
    }
    store.openFiles(addonConfigPath(kind.value, item.file, folders))
    emit('files')
  } finally {
    opening.value = null
  }
}

// --- Modrinth-Suche ----------------------------------------------------------------------

const PAGE = 20
const query = ref('')
const hits = ref<ModrinthHit[]>([])
const total = ref(0)
const searching = ref(false)
type SkipReason = CraftingModsResult['skipped'][number]['reason']
const results = ref<Record<string, { state: 'busy' | 'installed' | 'skipped'; reason?: SkipReason }>>({})
const facets = computed(() => addonSearchFacets(server.value.serverType, server.value.version, store.info))

let searchToken = 0
async function search(reset: boolean) {
  const f = facets.value
  if (!f) return
  const token = ++searchToken
  searching.value = true
  try {
    const page = await backend.craftinghosting.searchAddons(f, query.value.trim(), reset ? 0 : hits.value.length, PAGE)
    if (token !== searchToken) return
    hits.value = reset ? page.hits : [...hits.value, ...page.hits.filter((h) => !hits.value.some((x) => x.projectId === h.projectId))]
    total.value = page.totalHits
  } catch (e) {
    if (token === searchToken) toasts.error(e)
  } finally {
    if (token === searchToken) searching.value = false
  }
}

let debounce: ReturnType<typeof setTimeout> | null = null
watch(query, () => {
  if (debounce) clearTimeout(debounce)
  debounce = setTimeout(() => void search(true), 350)
})
onUnmounted(() => {
  if (debounce) clearTimeout(debounce)
})

async function install(hit: ModrinthHit) {
  results.value[hit.projectId] = { state: 'busy' }
  const outcome: CraftingModsResult | null = await store.installAddons([hit.projectId])
  if (!outcome) {
    delete results.value[hit.projectId]
    return
  }
  const skipped = outcome.skipped.find((s) => s.project === hit.projectId || s.project === hit.slug)
  if (skipped) {
    results.value[hit.projectId] = { state: 'skipped', reason: skipped.reason }
  } else {
    results.value[hit.projectId] = { state: 'installed' }
    toasts.ok(t('craftinghosting.addons.installedToast', { name: hit.title }))
  }
}
</script>

<template>
  <div class="space-y-4" data-testid="hosting-addons">
    <p class="flex items-start gap-2 rounded-lg bg-base-900 px-3 py-2 text-xs text-base-300 ring-1 ring-base-800">
      <svg viewBox="0 0 24 24" class="mt-px size-3.5 shrink-0 text-lamp-300" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 8v4M12 16h.01" /></svg>
      {{ t('craftinghosting.settings.nextStart') }}
    </p>

    <div class="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.25fr)]">
      <!-- Installiert -->
      <section class="card flex min-w-0 flex-col" aria-labelledby="ch-addons-title">
        <div class="flex items-center gap-2 border-b border-base-800 px-4 py-3">
          <h3 id="ch-addons-title" class="flex-1 text-sm font-semibold text-base-50">
            {{ plugins ? t('craftinghosting.addons.installedPlugins') : t('craftinghosting.addons.installedMods') }}
            <span v-if="store.addons" class="ml-1 rounded-full bg-base-800 px-2 py-0.5 text-xs font-normal text-base-300 tabular-nums">{{ items.length }}</span>
          </h3>
          <button class="btn-icon size-8" :disabled="store.addonsLoading || installing" :aria-label="t('common.actions.refresh')" :title="t('common.actions.refresh')" @click="store.loadAddons()">
            <svg viewBox="0 0 24 24" class="size-4" :class="{ 'animate-spin motion-reduce:animate-none': store.addonsLoading }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7" /></svg>
          </button>
        </div>
        <p v-if="installing" class="p-4 text-sm text-base-400">{{ t('craftinghosting.addons.pending') }}</p>
        <div v-else-if="!store.addons && store.addonsLoading" class="space-y-2 p-4">
          <div v-for="i in 3" :key="i" class="skeleton h-12" />
        </div>
        <p v-else-if="!items.length" class="p-4 text-sm text-base-500">{{ t('craftinghosting.addons.empty') }}</p>
        <ul v-else class="max-h-[30rem] divide-y divide-base-800/60 overflow-y-auto" data-testid="hosting-addons-list">
          <li v-for="item in items" :key="item.file" class="flex items-center gap-3 px-4 py-2.5" :data-testid="`hosting-addon-${item.file}`">
            <span class="grid size-9 shrink-0 place-items-center rounded-lg ring-1" :class="item.protected ? 'bg-lamp-900/30 text-lamp-300 ring-lamp-700/40' : 'bg-base-800 text-emerald-300 ring-base-700'">
              <svg v-if="item.protected" viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" role="img" :aria-label="t('craftinghosting.addons.protected')"><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
              <svg v-else viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M8 3h8v3H8zM7 6h10l1 3v10a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V9zM9 13h6" /></svg>
            </span>
            <div class="min-w-0 flex-1">
              <p class="truncate text-sm text-base-50" :title="item.file">{{ item.file }}</p>
              <p class="truncate text-xs text-base-500">
                <template v-if="item.protected">{{ t('craftinghosting.addons.protected') }}</template>
                <template v-else>{{ formatBytes(item.size) }}<template v-if="item.modifiedAt"> · {{ formatDate(item.modifiedAt) }}</template></template>
              </p>
            </div>
            <button class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="opening === item.file" :title="t('craftinghosting.addons.configTitle')" :data-testid="`hosting-addon-config-${item.file}`" @click="openConfig(item)">
              {{ t('craftinghosting.addons.config') }}
            </button>
            <button
              v-if="!item.protected"
              class="btn-icon size-8 text-base-400 hover:text-redstone-300"
              :disabled="!!store.busy"
              :aria-label="t('craftinghosting.addons.removeLabel', { file: item.file })"
              :title="t('craftinghosting.addons.remove')"
              @click="removing = item"
            >
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
            </button>
          </li>
        </ul>
      </section>

      <!-- Modrinth -->
      <section class="card flex min-w-0 flex-col" aria-labelledby="ch-search-title" data-testid="hosting-addons-search">
        <div class="border-b border-base-800 px-4 py-3">
          <h3 id="ch-search-title" class="text-sm font-semibold text-base-50">{{ t('craftinghosting.addons.searchTitle') }}</h3>
          <p class="mt-0.5 text-xs text-base-400">
            {{ t('craftinghosting.addons.searchFor', { type: typeName, version: gameVersion ?? t('craftinghosting.latest') }) }}
          </p>
          <div class="relative mt-2.5">
            <svg viewBox="0 0 24 24" class="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-base-500" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></svg>
            <input
              v-model="query"
              type="search"
              class="field pl-8 text-sm"
              :placeholder="plugins ? t('craftinghosting.addons.searchPlugins') : t('craftinghosting.addons.searchMods')"
              :aria-label="plugins ? t('craftinghosting.addons.searchPlugins') : t('craftinghosting.addons.searchMods')"
              data-testid="hosting-addons-query"
            />
          </div>
        </div>
        <div v-if="searching && !hits.length" class="space-y-2 p-4">
          <div v-for="i in 4" :key="i" class="skeleton h-16" />
        </div>
        <p v-else-if="!hits.length" class="p-4 text-sm text-base-500">{{ t('craftinghosting.addons.noResults') }}</p>
        <ul v-else class="max-h-[34rem] divide-y divide-base-800/60 overflow-y-auto" :class="{ 'opacity-70': searching }" aria-live="polite">
          <li v-for="hit in hits" :key="hit.projectId" class="flex gap-3 px-4 py-3" :data-testid="`hosting-hit-${hit.slug}`">
            <ModIcon :src="hit.iconUrl" :name="hit.title" :size="44" />
            <div class="min-w-0 flex-1">
              <p class="flex items-baseline gap-1.5">
                <span class="truncate text-sm font-semibold text-base-50">{{ hit.title }}</span>
                <span v-if="hit.author" class="truncate text-xs text-base-500">{{ t('craftinghosting.addons.by', { author: hit.author }) }}</span>
              </p>
              <p class="line-clamp-2 text-xs text-base-400">{{ hit.description }}</p>
              <p class="mt-1 flex items-center gap-1 text-[11px] text-base-500">
                <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M12 4v11M7 10l5 5 5-5M5 20h14" /></svg>
                {{ t('craftinghosting.addons.downloads', { n: formatCount(hit.downloads) }) }}
              </p>
            </div>
            <div class="flex shrink-0 flex-col items-end justify-center gap-1">
              <span v-if="results[hit.projectId]?.state === 'installed'" class="inline-flex items-center gap-1 text-xs text-ok" data-testid="hosting-hit-installed">
                <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" aria-hidden="true"><path d="M5 12l5 5 9-10" /></svg>
                {{ t('craftinghosting.addons.installed') }}
              </span>
              <template v-else>
                <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="results[hit.projectId]?.state === 'busy' || !!store.busy" :data-testid="`hosting-hit-install-${hit.slug}`" @click="install(hit)">
                  {{ results[hit.projectId]?.state === 'busy' ? t('craftinghosting.addons.installing') : t('craftinghosting.addons.install') }}
                </button>
                <span v-if="results[hit.projectId]?.state === 'skipped'" class="max-w-32 text-right text-[11px] text-lamp-300">
                  {{ t('craftinghosting.addons.skipped', { reason: t(`craftinghosting.mods.reason.${results[hit.projectId]?.reason ?? 'unsupported'}`) }) }}
                </span>
              </template>
            </div>
          </li>
          <li v-if="hits.length < total" class="p-3 text-center">
            <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="searching" @click="search(false)">{{ t('craftinghosting.addons.more') }}</button>
          </li>
        </ul>
      </section>
    </div>

    <CraftinghostingInstanceModsCard />

    <BaseDialog v-if="removing" :title="t('craftinghosting.addons.removeTitle', { file: removing.file })" @close="removing = null">
      <p class="text-sm text-base-200">{{ t('craftinghosting.addons.removeText') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="removing = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" data-testid="hosting-addon-remove-confirm" @click="remove">{{ t('craftinghosting.addons.remove') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
