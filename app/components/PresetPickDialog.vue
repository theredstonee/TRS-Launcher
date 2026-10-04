<script setup lang="ts">
import type { ContentKind, ModrinthHit, Platform, PresetItem, PresetPickItem, PresetPickList } from '~/types'

// „Aus Modpack übernehmen“: Inhalte einer eigenen Instanz, eines Modrinth-/CurseForge-
// Modpacks (nur gelesen, nichts installiert) oder eines TRS-Pack-Codes als Checkliste.
// Einträge ohne Modrinth-/CurseForge-Projekt sind sichtbar, aber nicht wählbar.
const props = defineProps<{ items: PresetItem[] }>()
const emit = defineEmits<{ close: []; pick: [items: PresetPickItem[]] }>()

type SourceTab = 'instance' | 'modpack' | 'code'
const sourceTabs: SourceTab[] = ['instance', 'modpack', 'code']

const instances = useInstancesStore()
const curseforge = useCurseForgeStore()
const tab = ref<SourceTab>('instance')
const list = ref<PresetPickList | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

// --- Quelle wählen -----------------------------------------------------------------
const instanceId = ref('')
const packPlatform = ref<Platform>('modrinth')
const packQuery = ref('')
const packHits = ref<ModrinthHit[]>([])
const packSearching = ref(false)
const codeInput = ref('')
const code = computed(() => normalizePackCode(codeInput.value))

onMounted(async () => {
  if (!instances.items.length) await instances.load().catch(() => {})
  instanceId.value = instances.items[0]?.id ?? ''
  await curseforge.load()
})

let request = 0
async function load(work: () => Promise<PresetPickList>) {
  const mine = ++request
  loading.value = true
  error.value = null
  try {
    const result = await work()
    if (mine !== request) return
    list.value = result
    // Vorauswahl: alles, was sich übernehmen lässt und noch nicht drin ist.
    selected.value = new Set(result.items.filter((i) => isPickable(i) && !inPreset(props.items, i)).map(pickKey))
    filter.value = emptyPickFilter()
  } catch (e) {
    if (mine === request) error.value = errorMessage(e)
  } finally {
    if (mine === request) loading.value = false
  }
}

const loadInstance = () => instanceId.value && load(() => backend.presetPickInstance(instanceId.value))
const loadPack = (hit: ModrinthHit) => load(() => backend.presetPickModpack(packPlatform.value, hit.projectId))
const loadCode = () => code.value && load(() => backend.presetPickPackCode(code.value!))

let packTimer: ReturnType<typeof setTimeout> | undefined
let packRequest = 0
async function searchPacks() {
  const mine = ++packRequest
  packSearching.value = true
  try {
    const params = modrinthSearchSchema.parse({
      query: packQuery.value.trim().slice(0, 100),
      kind: 'modpack',
      gameVersions: [],
      loaders: [],
      categories: [],
      categoryMatch: 'all',
      excludeCategories: [],
      environments: [],
      excludeProjectIds: [],
      openSource: false,
      index: packQuery.value.trim() ? 'relevance' : 'downloads',
      offset: 0,
      limit: 20,
    })
    const result = packPlatform.value === 'curseforge' ? await backend.curseforge.search(params) : await backend.modrinthSearch(params)
    if (mine === packRequest) packHits.value = result.hits
  } catch (e) {
    if (mine === packRequest) error.value = errorMessage(e)
  } finally {
    if (mine === packRequest) packSearching.value = false
  }
}
watch([packQuery, packPlatform], () => {
  clearTimeout(packTimer)
  packTimer = setTimeout(searchPacks, 300)
})
watch(tab, (next) => {
  error.value = null
  if (next === 'modpack' && !packHits.value.length) searchPacks()
})
onBeforeUnmount(() => clearTimeout(packTimer))

// --- Checkliste --------------------------------------------------------------------
const filter = ref(emptyPickFilter())
const selected = ref<Set<string>>(new Set())
const shown = computed(() => (list.value ? filterPicks(list.value.items, filter.value) : []))
const choosable = (i: PresetPickItem) => isPickable(i) && !inPreset(props.items, i)
// Übernommen wird, was gewählt UND gerade sichtbar ist – Filter wirken also auch auf „X übernehmen“.
const chosen = computed(() => shown.value.filter((i) => choosable(i) && selected.value.has(pickKey(i))))
const kindsInList = computed(() => [...new Set((list.value?.items ?? []).map((i) => i.kind))] as ContentKind[])
const skippedCount = computed(() => (list.value?.items ?? []).filter((i) => !isPickable(i)).length)

function toggle(item: PresetPickItem) {
  if (!choosable(item)) return
  const next = new Set(selected.value)
  const key = pickKey(item)
  if (!next.delete(key)) next.add(key)
  selected.value = next
}
function selectShown(on: boolean) {
  const next = new Set(selected.value)
  for (const i of shown.value.filter(choosable)) {
    if (on) next.add(pickKey(i))
    else next.delete(pickKey(i))
  }
  selected.value = next
}
function categoryText(item: PresetPickItem): string[] {
  return item.categories.slice(0, 3).map((c) => (item.source === 'modrinth' ? categoryLabel(c) : c))
}
function why(item: PresetPickItem): string | null {
  if (!isPickable(item)) return t('presets.pick.noProject')
  if (inPreset(props.items, item)) return t('presets.pick.alreadyIn')
  return null
}
function back() {
  list.value = null
  error.value = null
}
</script>

<template>
  <BaseDialog :title="list ? t('presets.pick.titleFrom', { name: list.name }) : t('presets.pick.title')" huge @close="emit('close')">
    <!-- Schritt 1: Quelle -->
    <div v-if="!list" class="space-y-4">
      <div class="inline-flex rounded-full bg-base-900 p-1 ring-1 ring-base-800 mobile-scroll-x mobile:flex mobile:w-full" role="tablist" :aria-label="t('presets.pick.sourceLabel')">
        <button v-for="s in sourceTabs" :key="s" type="button" role="tab" :aria-selected="tab === s" class="tab px-4 py-1.5 mobile:shrink-0" :class="{ 'tab-on': tab === s }" @click="tab = s">
          {{ t(`presets.pick.source.${s}`) }}
        </button>
      </div>

      <div v-if="tab === 'instance'" class="space-y-3">
        <p class="text-sm text-base-400">{{ t('presets.pick.instanceHint') }}</p>
        <ul v-if="instances.items.length" class="grid gap-2 sm:grid-cols-2">
          <li v-for="i in instances.items" :key="i.id">
            <button
              type="button"
              class="card card-hover flex w-full items-center gap-3 px-3 py-2.5 text-left"
              :class="{ 'border-redstone-500/60': instanceId === i.id }"
              :disabled="loading"
              @click="instanceId = i.id; loadInstance()"
            >
              <InstanceIcon :instance="i" :size="36" />
              <span class="min-w-0 flex-1">
                <span class="block truncate text-sm font-medium">{{ i.name }}</span>
                <span class="block text-xs text-base-400">{{ i.gameVersion }} · {{ loaderLabels[i.loader.kind] }}</span>
              </span>
            </button>
          </li>
        </ul>
        <p v-else class="text-sm text-base-400">{{ t('browse.noInstance') }}</p>
      </div>

      <div v-else-if="tab === 'modpack'" class="space-y-3">
        <div class="flex flex-wrap items-center gap-2">
          <input v-model="packQuery" class="field min-w-60 flex-1" maxlength="100" :placeholder="t('browse.searchPlaceholder.modpack')" :aria-label="t('presets.pick.searchPack')" spellcheck="false" />
          <div class="inline-flex rounded-full bg-base-900 p-1 ring-1 ring-base-800" role="radiogroup" :aria-label="t('browse.source.label')">
            <button
              v-for="p in platforms"
              :key="p"
              type="button"
              role="radio"
              :aria-checked="packPlatform === p"
              class="tab px-3 py-1 disabled:opacity-40"
              :class="{ 'tab-on': packPlatform === p }"
              :disabled="p === 'curseforge' && curseforge.available === false"
              @click="packPlatform = p"
            >
              {{ t(`browse.source.${p}`) }}
            </button>
          </div>
        </div>
        <p class="text-xs text-base-400">{{ t('presets.pick.packHint') }}</p>
        <ul class="grid gap-2 sm:grid-cols-2" :aria-busy="packSearching">
          <li v-for="hit in packHits" :key="hit.projectId">
            <button type="button" class="card card-hover flex w-full items-center gap-3 px-3 py-2.5 text-left" :disabled="loading" @click="loadPack(hit)">
              <ModIcon :src="hit.iconUrl" :name="hit.title" :size="40" />
              <span class="min-w-0 flex-1">
                <span class="block truncate text-sm font-medium">{{ hit.title }}</span>
                <span class="block truncate text-xs text-base-400">{{ hit.description }}</span>
              </span>
              <span class="shrink-0 text-[11px] text-base-400 tabular-nums">{{ formatCount(hit.downloads) }}</span>
            </button>
          </li>
          <template v-if="packSearching && !packHits.length">
            <li v-for="i in 6" :key="i" class="skeleton h-[62px] rounded-xl" />
          </template>
        </ul>
      </div>

      <form v-else class="space-y-3" @submit.prevent="loadCode">
        <p class="text-sm text-base-400">{{ t('presets.pick.codeHint') }}</p>
        <div class="flex gap-2">
          <input v-model="codeInput" class="field flex-1 font-mono uppercase" maxlength="80" placeholder="TRS-XXXX-XXXX" :aria-label="t('presets.pick.codeLabel')" spellcheck="false" autocomplete="off" />
          <button type="submit" class="btn btn-primary" :disabled="!code || loading">{{ t('presets.pick.read') }}</button>
        </div>
        <p v-if="codeInput && !code" class="text-xs text-redstone-300">{{ t('packs.code.invalid') }}</p>
      </form>

      <div v-if="loading" class="flex items-center gap-3 text-sm text-base-400" role="status">
        <RedstoneWire :percent="50" :segments="10" class="w-32" />
        {{ t('presets.pick.reading') }}
      </div>
      <p v-if="error" role="alert" class="card border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ error }}</p>
    </div>

    <!-- Schritt 2: Checkliste -->
    <div v-else class="space-y-3">
      <p class="flex flex-wrap items-center gap-x-2 text-xs text-base-400">
        <button type="button" class="inline-flex items-center gap-1 text-base-200 hover:text-base-50" @click="back">
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m15 18-6-6 6-6" /></svg>
          {{ t('presets.pick.otherSource') }}
        </button>
        <span v-if="list.gameVersion">· Minecraft {{ list.gameVersion }}</span>
        <span v-if="list.loader">· {{ loaderLabels[list.loader] }}</span>
        <span>· {{ t('presets.pick.count', list.items.length) }}</span>
      </p>

      <div class="flex flex-wrap items-center gap-2">
        <input v-model="filter.query" class="field min-w-48 flex-1 py-1.5" maxlength="100" :placeholder="t('presets.pick.filterPlaceholder')" :aria-label="t('presets.pick.filterPlaceholder')" spellcheck="false" />
        <select v-model="filter.kind" class="field w-auto py-1.5" :aria-label="t('presets.pick.kindLabel')">
          <option value="all">{{ t('presets.pick.allKinds') }}</option>
          <option v-for="k in kindsInList" :key="k" :value="k">{{ contentKindLabel(k) }}</option>
        </select>
        <button type="button" class="chip mobile:min-h-11" :class="filter.performance ? 'bg-base-700 text-base-50 ring-1 ring-redstone-500/60' : 'text-base-400 hover:text-base-50'" :aria-pressed="filter.performance" @click="filter.performance = !filter.performance">
          {{ t('presets.pick.onlyPerformance') }}
        </button>
        <button type="button" class="chip mobile:min-h-11" :class="filter.clientOnly ? 'bg-base-700 text-base-50 ring-1 ring-redstone-500/60' : 'text-base-400 hover:text-base-50'" :aria-pressed="filter.clientOnly" @click="filter.clientOnly = !filter.clientOnly">
          {{ t('presets.pick.onlyClient') }}
        </button>
      </div>

      <div class="flex items-center gap-3 text-xs">
        <button type="button" class="text-base-200 underline-offset-2 hover:text-base-50 hover:underline" @click="selectShown(true)">{{ t('presets.pick.all') }}</button>
        <button type="button" class="text-base-200 underline-offset-2 hover:text-base-50 hover:underline" @click="selectShown(false)">{{ t('presets.pick.none') }}</button>
        <span v-if="skippedCount" class="ml-auto text-base-400">{{ t('presets.pick.withoutProject', skippedCount) }}</span>
      </div>

      <ul class="grid gap-1.5 sm:grid-cols-2">
        <li v-for="item in shown" :key="pickKey(item)">
          <label
            class="flex items-center gap-2.5 rounded-lg px-2 py-1.5 ring-1 mobile:min-h-11"
            :class="choosable(item) ? 'cursor-pointer bg-base-900 ring-base-800 hover:ring-base-700' : 'cursor-not-allowed bg-base-900/50 opacity-60 ring-transparent'"
            :title="why(item) ?? undefined"
          >
            <input type="checkbox" class="accent-redstone-500" :checked="choosable(item) && selected.has(pickKey(item))" :disabled="!choosable(item)" @change="toggle(item)" />
            <ModIcon :src="item.iconUrl" :name="item.title" :size="32" />
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm">{{ item.title }}</span>
              <span class="flex flex-wrap items-center gap-1 text-[10px] text-base-400">
                <span>{{ contentKindLabel(item.kind) }}</span>
                <span v-if="item.performance" class="rounded bg-base-800 px-1 text-ok">{{ t('presets.pick.performance') }}</span>
                <span v-if="item.clientOnly" class="rounded bg-base-800 px-1">{{ t('presets.pick.client') }}</span>
                <span v-for="c in categoryText(item)" :key="c" class="rounded bg-base-800 px-1">{{ c }}</span>
                <span v-if="why(item)" class="text-warn">{{ why(item) }}</span>
              </span>
            </span>
          </label>
        </li>
      </ul>
      <p v-if="!shown.length" class="py-6 text-center text-sm text-base-400">{{ t('presets.editor.noResults') }}</p>
    </div>

    <template #actions>
      <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button v-if="list" type="button" class="btn btn-primary" :disabled="!chosen.length" data-testid="preset-pick-apply" @click="emit('pick', chosen)">
        {{ t('presets.pick.apply', chosen.length) }}
      </button>
    </template>
  </BaseDialog>
</template>
