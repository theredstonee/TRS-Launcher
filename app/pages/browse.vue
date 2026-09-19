<script setup lang="ts">
import type { ContentKind, ModrinthHit } from '~/types'

const route = useRoute()
const instances = useInstancesStore()

const query = ref('')
const kind = ref<ContentKind>(contentKinds.includes(route.query.kind as ContentKind) ? (route.query.kind as ContentKind) : 'mod')
const instanceId = ref(typeof route.query.instance === 'string' ? route.query.instance : '')

const hits = ref<ModrinthHit[]>([])
const totalHits = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
const installed = ref<Set<string>>(new Set())
const installing = ref<Set<string>>(new Set())
const notice = ref<string | null>(null)

const target = computed(() => instances.items.find((i) => i.id === instanceId.value) ?? null)
const modsBlocked = computed(() => kind.value === 'mod' && target.value?.loader.kind === 'vanilla')

onMounted(async () => {
  if (!instances.items.length) await instances.load()
  if (!target.value) {
    // Für Mods bevorzugt eine Instanz mit Modloader vorschlagen.
    instanceId.value = (instances.items.find((i) => i.loader.kind !== 'vanilla') ?? instances.items[0])?.id ?? ''
  }
})

// Verwirft Antworten überholter Anfragen.
let requestNo = 0

async function search(append = false) {
  const current = ++requestNo
  loading.value = true
  error.value = null
  try {
    const result = await backend.modrinthSearch({
      query: query.value,
      kind: kind.value,
      gameVersion: target.value?.gameVersion ?? null,
      loader: target.value?.loader.kind ?? null,
      offset: append ? hits.value.length : 0,
    })
    if (current !== requestNo) return
    hits.value = append ? [...hits.value, ...result.hits] : result.hits
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

let debounce: ReturnType<typeof setTimeout> | undefined
watch(query, () => {
  clearTimeout(debounce)
  debounce = setTimeout(() => search(), 350)
})
watch([kind, target], () => search(), { immediate: true })
watch(target, loadInstalled, { immediate: true })
onBeforeUnmount(() => clearTimeout(debounce))

async function install(hit: ModrinthHit) {
  if (!target.value || installing.value.has(hit.projectId)) return
  error.value = null
  notice.value = null
  installing.value = new Set(installing.value).add(hit.projectId)
  try {
    const files = await backend.modrinthInstall(target.value.id, hit.projectId, kind.value)
    await loadInstalled()
    notice.value =
      files.length > 1
        ? `${hit.title} und ${files.length - 1} benötigte Abhängigkeit(en) installiert.`
        : `${hit.title} installiert.`
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    const next = new Set(installing.value)
    next.delete(hit.projectId)
    installing.value = next
  }
}
</script>

<template>
  <div class="flex h-full flex-col p-6">
    <PageHeader title="Entdecken" subtitle="Inhalte von Modrinth – passend zur gewählten Instanz gefiltert." />

    <div class="mb-4 flex flex-wrap items-center gap-2">
      <input v-model="query" class="field max-w-xs" maxlength="100" placeholder="Suchen …" spellcheck="false" autofocus />
      <div class="flex overflow-hidden rounded-md border border-base-700 text-sm">
        <button v-for="k in contentKinds" :key="k" class="seg" :class="{ 'seg-on': kind === k }" @click="kind = k">
          {{ contentKindLabels[k] }}
        </button>
      </div>
      <label class="ml-auto flex items-center gap-2 text-xs text-base-400">
        Installieren in
        <select v-model="instanceId" class="field w-56 py-1.5" :disabled="!instances.items.length">
          <option v-if="!instances.items.length" value="">Keine Instanz vorhanden</option>
          <option v-for="i in instances.items" :key="i.id" :value="i.id">
            {{ i.name }} ({{ i.gameVersion }}, {{ loaderLabels[i.loader.kind] }})
          </option>
        </select>
      </label>
    </div>

    <p v-if="error" role="alert" class="card mb-3 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ error }}</p>
    <p v-else-if="notice" role="status" class="card mb-3 border-ok/30 px-4 py-2.5 text-sm text-ok">{{ notice }}</p>
    <p v-if="modsBlocked" class="card mb-3 border-warn/40 px-4 py-2.5 text-sm text-warn">
      „{{ target?.name }}“ ist eine Vanilla-Instanz. Für Mods bitte eine Instanz mit Fabric oder Quilt wählen.
    </p>

    <div class="min-h-0 flex-1 overflow-y-auto pr-1">
      <ul class="grid grid-cols-[repeat(auto-fill,minmax(22rem,1fr))] gap-3">
        <li v-for="hit in hits" :key="hit.projectId" class="card flex gap-3 p-3">
          <img v-if="hit.iconUrl" :src="hit.iconUrl" alt="" loading="lazy" class="size-14 shrink-0 rounded-md bg-base-800 object-cover" />
          <div v-else class="flex size-14 shrink-0 items-center justify-center rounded-md bg-base-800 font-mono text-xl font-bold text-base-600">
            {{ hit.title.charAt(0).toUpperCase() }}
          </div>
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{{ hit.title }}</p>
            <p class="truncate text-xs text-base-400">von {{ hit.author }} · {{ formatCount(hit.downloads) }} Downloads</p>
            <p class="mt-1 line-clamp-2 text-xs text-base-200">{{ hit.description }}</p>
          </div>
          <div class="flex shrink-0 items-center">
            <span v-if="installed.has(hit.projectId) && !installing.has(hit.projectId)" class="rounded-full bg-base-800 px-2.5 py-1 text-xs font-medium text-ok">Installiert</span>
            <button v-else class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!target || modsBlocked || installing.has(hit.projectId)" @click="install(hit)">
              {{ installing.has(hit.projectId) ? 'Lädt …' : 'Installieren' }}
            </button>
          </div>
        </li>
      </ul>

      <p v-if="!loading && !hits.length && !error" class="py-16 text-center text-sm text-base-400">
        Nichts gefunden{{ target ? ` für ${target.gameVersion} (${loaderLabels[target.loader.kind]})` : '' }}.
      </p>
      <div class="flex justify-center py-4">
        <button v-if="hits.length < totalHits" class="btn btn-ghost" :disabled="loading" @click="search(true)">
          {{ loading ? 'Lade …' : 'Mehr laden' }}
        </button>
        <span v-else-if="loading" class="text-sm text-base-400">Lade …</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.seg {
  @apply px-3 py-2 text-base-400 transition-colors hover:text-base-50;
}
.seg-on {
  @apply bg-base-700 text-base-50;
}
</style>
