<script setup lang="ts">
import type { ContentKind, ModrinthHit, ModrinthVersion, ProjectKind } from '~/types'

const route = useRoute()
const router = useRouter()
const instances = useInstancesStore()
const toasts = useToasts()

const kinds: ProjectKind[] = ['mod', 'modpack', 'resourcepack', 'shaderpack']
const kindLabels: Record<ProjectKind, string> = { ...contentKindLabels, modpack: 'Modpacks' }

const query = ref('')
const kind = ref<ProjectKind>(kinds.includes(route.query.kind as ProjectKind) ? (route.query.kind as ProjectKind) : 'mod')
const instanceId = ref(typeof route.query.instance === 'string' ? route.query.instance : '')

const hits = ref<ModrinthHit[]>([])
const totalHits = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
const installed = ref<Set<string>>(new Set())
const installing = ref<Record<string, number | null>>({})
const picking = ref<ModrinthHit | null>(null)

const isPack = computed(() => kind.value === 'modpack')
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
      // Modpacks bringen ihre eigene Version mit – nicht nach der Instanz filtern.
      gameVersion: isPack.value ? null : (target.value?.gameVersion ?? null),
      loader: isPack.value ? null : (target.value?.loader.kind ?? null),
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

function setBusy(id: string, value: number | null | undefined) {
  const next = { ...installing.value }
  if (value === undefined) delete next[id]
  else next[id] = value
  installing.value = next
}

async function install(hit: ModrinthHit, version: ModrinthVersion | null = null) {
  picking.value = null
  if (hit.projectId in installing.value) return
  error.value = null
  setBusy(hit.projectId, isPack.value ? 0 : null)
  try {
    if (isPack.value) {
      const instance = await backend.installModpack(hit.projectId, (p) => {
        // Pack laden 0–10 %, Dateien 10–95 %, Overrides den Rest.
        const base = { pack: 0, files: 10, overrides: 95 }[p.phase]
        const span = { pack: 10, files: 85, overrides: 5 }[p.phase]
        setBusy(hit.projectId, Math.floor(base + (p.percent / 100) * span))
      })
      await instances.load()
      toasts.ok(`Modpack „${instance.name}“ ist bereit`)
      router.push(`/instances/${instance.id}`)
      return
    }
    if (!target.value) return
    const files = await backend.modrinthInstall(target.value.id, hit.projectId, kind.value as ContentKind, version?.id ?? null)
    await loadInstalled()
    toasts.ok(
      files.length > 1
        ? `${hit.title} und ${files.length - 1} benötigte Abhängigkeit(en) installiert`
        : `${hit.title} installiert`,
    )
  } catch (e) {
    toasts.error(e)
  } finally {
    setBusy(hit.projectId, undefined)
  }
}
</script>

<template>
  <div class="flex h-full flex-col p-6">
    <PageHeader title="Entdecken" subtitle="Mods, Modpacks, Ressourcenpakete und Shader von Modrinth." />

    <div class="mb-4 flex flex-wrap items-center gap-2">
      <input v-model="query" class="field max-w-xs" maxlength="100" placeholder="Suchen …" spellcheck="false" autofocus />
      <div class="flex overflow-hidden rounded-md border border-base-700 text-sm">
        <button v-for="k in kinds" :key="k" class="seg py-2" :class="{ 'seg-on': kind === k }" @click="kind = k">
          {{ kindLabels[k] }}
        </button>
      </div>
      <label v-if="!isPack" class="ml-auto flex items-center gap-2 text-xs text-base-400">
        Installieren in
        <select v-model="instanceId" class="field w-56 py-1.5" :disabled="!instances.items.length">
          <option v-if="!instances.items.length" value="">Keine Instanz vorhanden</option>
          <option v-for="i in instances.items" :key="i.id" :value="i.id">
            {{ i.name }} ({{ i.gameVersion }}, {{ loaderLabels[i.loader.kind] }})
          </option>
        </select>
      </label>
      <p v-else class="ml-auto text-xs text-base-400">Ein Modpack wird als neue Instanz angelegt.</p>
    </div>

    <p v-if="error" role="alert" class="card mb-3 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ error }}</p>
    <p v-if="modsBlocked" class="card mb-3 border-warn/40 px-4 py-2.5 text-sm text-warn">
      „{{ target?.name }}“ ist eine Vanilla-Instanz. Für Mods bitte eine Instanz mit Modloader wählen.
    </p>

    <div class="min-h-0 flex-1 overflow-y-auto pr-1">
      <ul v-if="loading && !hits.length" class="grid grid-cols-[repeat(auto-fill,minmax(22rem,1fr))] gap-3">
        <li v-for="i in 8" :key="i" class="skeleton h-[88px]" />
      </ul>

      <ul v-else class="grid grid-cols-[repeat(auto-fill,minmax(22rem,1fr))] gap-3">
        <li v-for="hit in hits" :key="hit.projectId" class="card flex gap-3 p-3">
          <img v-if="hit.iconUrl" :src="hit.iconUrl" alt="" loading="lazy" class="size-14 shrink-0 rounded-md bg-base-800 object-cover" />
          <div v-else class="display flex size-14 shrink-0 items-center justify-center rounded-md bg-base-800 text-xl text-base-600">
            {{ hit.title.charAt(0).toUpperCase() }}
          </div>
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{{ hit.title }}</p>
            <p class="truncate text-xs text-base-400">von {{ hit.author }}, {{ formatCount(hit.downloads) }} Downloads</p>
            <p class="mt-1 line-clamp-2 text-xs text-base-200">{{ hit.description }}</p>
          </div>

          <div class="flex w-28 shrink-0 flex-col items-stretch justify-center gap-1">
            <template v-if="hit.projectId in installing">
              <span class="display text-center text-sm tabular-nums text-redstone-300">
                {{ installing[hit.projectId] === null ? 'Lädt …' : `${installing[hit.projectId]} %` }}
              </span>
              <RedstoneWire :percent="installing[hit.projectId] ?? 50" :segments="12" />
            </template>
            <span v-else-if="!isPack && installed.has(hit.projectId)" class="rounded-full bg-base-800 px-2.5 py-1 text-center text-xs font-medium text-ok">
              Installiert
            </span>
            <template v-else>
              <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!isPack && (!target || modsBlocked)" @click="install(hit)">
                {{ isPack ? 'Als Instanz' : 'Installieren' }}
              </button>
              <button
                v-if="!isPack"
                class="text-[11px] text-base-400 hover:text-base-50 disabled:opacity-40"
                :disabled="!target || modsBlocked"
                @click="picking = hit"
              >
                Version wählen
              </button>
            </template>
          </div>
        </li>
      </ul>

      <p v-if="!loading && !hits.length && !error" class="py-16 text-center text-sm text-base-400">
        Nichts gefunden{{ !isPack && target ? ` für ${target.gameVersion} (${loaderLabels[target.loader.kind]})` : '' }}.
      </p>
      <div class="flex justify-center py-4">
        <button v-if="hits.length && hits.length < totalHits" class="btn btn-ghost" :disabled="loading" @click="search(true)">
          {{ loading ? 'Lade …' : 'Mehr laden' }}
        </button>
      </div>
    </div>

    <VersionPickerDialog
      v-if="picking && target && !isPack"
      :instance="target"
      :project-id="picking.projectId"
      :title="picking.title"
      :kind="kind as ContentKind"
      @close="picking = null"
      @pick="install(picking!, $event)"
    />
  </div>
</template>
