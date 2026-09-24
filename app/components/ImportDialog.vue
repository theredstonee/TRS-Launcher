<script setup lang="ts">
import type { ImportCandidate, ImportSource, LoaderKind } from '~/types'

const emit = defineEmits<{ close: [] }>()

const instances = useInstancesStore()
const meta = useMetaStore()
// Importe laufen als Aufgaben – der Dialog darf zu, die Kopie läuft weiter.
const tasks = useTasksStore()

const candidates = ref<ImportCandidate[]>([])
const loading = ref(true)
const picking = ref(false)
const error = ref<string | null>(null)
const running = computed(() => {
  const task = Object.values(tasks.tasks).find((x) => x.kind === 'import' && x.status === 'running')
  return task ? { id: task.tag ?? '', percent: task.percent ?? 0 } : null
})
const done = computed(
  () => new Set(Object.values(tasks.tasks).filter((x) => x.kind === 'import' && x.status === 'done').map((x) => x.tag ?? '')),
)
const query = ref('')
const source = ref<ImportSource | 'all'>('all')
/** Bei selbst gewählten Ordnern: Version und Loader vor dem Import anpassbar. */
const overrides = ref<Record<string, { gameVersion: string; loader: LoaderKind }>>({})
const highlighted = ref<Set<string>>(new Set())

const releases = computed(() => (meta.manifest?.versions ?? []).filter((v) => v.type === 'release').map((v) => v.id))

const sources = computed(() => {
  const present = new Set(candidates.value.map((c) => c.source))
  return importSources.filter((s) => present.has(s))
})

const visible = computed(() => {
  // Einfacher Teilstring-Vergleich – kein RegExp aus Nutzereingaben.
  const needle = query.value.trim().toLowerCase()
  return candidates.value.filter(
    (c) =>
      (source.value === 'all' || c.source === source.value) &&
      (!needle || `${c.name} ${c.gameVersion} ${c.loader.kind}`.toLowerCase().includes(needle)),
  )
})

function remember(list: ImportCandidate[]) {
  for (const c of list) {
    if (c.versionGuessed && !overrides.value[c.id]) {
      overrides.value[c.id] = { gameVersion: c.gameVersion, loader: c.loader.kind }
    }
  }
}

onMounted(async () => {
  meta.loadManifest().catch(() => {})
  try {
    candidates.value = await backend.scanImports()
    remember(candidates.value)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
})

async function browse() {
  error.value = null
  picking.value = true
  try {
    const found = await backend.pickImportFolder()
    if (!found) return
    remember(found)
    const known = new Set(candidates.value.map((c) => c.id))
    candidates.value = [...found.filter((c) => !known.has(c.id)), ...candidates.value]
    highlighted.value = new Set(found.map((c) => c.id))
    query.value = ''
    source.value = 'all'
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    picking.value = false
  }
}

function run(candidate: ImportCandidate) {
  if (running.value) return
  error.value = null
  const o = overrides.value[candidate.id]
  tasks.run(
    {
      key: taskKey('import', candidate.id),
      kind: 'import',
      title: candidate.name,
      stage: t('import.task.stage', { source: importSourceLabel(candidate.source) }),
      tag: candidate.id,
    },
    async (ctx) => {
      const instance = await backend.importInstance(
        candidate.id,
        o?.gameVersion ?? null,
        o ? { kind: o.loader, version: null } : null,
        (p) => ctx.progress(p.percent, p.totalFiles ? t('import.task.filesCopied', { done: p.doneFiles, total: p.totalFiles }) : undefined),
      )
      ctx.update({ instanceId: instance.id, doneText: t('import.task.done', { name: instance.name }) })
      await instances.load()
      return instance
    },
  )
}

/** Eine .mrpack-Datei einlesen – etwa ein eigener Export. */
const PACK_FILE_KEY = 'mrpack-file'
const packTask = computed(() => tasks.get(PACK_FILE_KEY))
const packing = computed(() => (packTask.value?.status === 'running' ? (packTask.value.percent ?? 0) : null))
function importPack() {
  if (packing.value !== null || running.value) return
  error.value = null
  tasks.run(
    { key: PACK_FILE_KEY, kind: 'modpack-file', title: t('import.task.packTitle'), stage: t('import.task.pickFile'), cancellable: true },
    async (ctx) => {
      const id = await backend.importModpackFile((p) => ctx.progress(packPercent(p), packStageLabel(p.phase)), ctx.taskId)
      if (!id) {
        // Dateidialog abgebrochen – keine Aufgabe, kein Verlauf.
        ctx.discard()
        return null
      }
      await instances.load()
      const instance = instances.items.find((i) => i.id === id)
      ctx.update({
        instanceId: id,
        title: instance?.name ?? 'Modpack',
        doneText: instance ? t('import.task.done', { name: instance.name }) : t('import.task.packDone'),
      })
      // Presets mit „immer automatisch“ laufen danach als eigene Aufgabe.
      if (instance) void applyAutoPresetsTask(instance)
      return id
    },
  )
}

function loaderText(c: ImportCandidate) {
  return c.loader.version ? `${loaderLabels[c.loader.kind]} ${c.loader.version}` : loaderLabels[c.loader.kind]
}
</script>

<template>
  <BaseDialog :title="t('import.title')" wide @close="emit('close')">
    <p class="mb-3 text-sm text-base-400">{{ t('import.intro') }}</p>

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <input v-model="query" class="field h-9 min-w-0 flex-1" maxlength="100" :placeholder="t('import.search')" spellcheck="false" :aria-label="t('import.searchLabel')" />
      <select v-if="sources.length > 1" v-model="source" class="field h-9 w-44 py-1" :aria-label="t('import.sourceLabel')">
        <option value="all">{{ t('import.allSources') }}</option>
        <option v-for="s in sources" :key="s" :value="s">{{ importSourceLabel(s) }}</option>
      </select>
      <button class="btn btn-ghost h-9" :disabled="picking || !!running" @click="browse">
        {{ picking ? t('import.scanning') : t('import.browseFolder') }}
      </button>
      <button class="btn btn-ghost h-9" :disabled="packing !== null || !!running" @click="importPack">
        {{ packing !== null ? t('import.packProgress', { percent: packing }) : t('import.packFile') }}
      </button>
    </div>

    <div v-if="loading" class="space-y-2">
      <div v-for="i in 4" :key="i" class="skeleton h-14" />
    </div>
    <p v-else-if="!candidates.length && !error" class="py-6 text-center text-sm text-base-400">
      {{ t('import.nothingFound') }}
    </p>
    <p v-else-if="!visible.length" class="py-6 text-center text-sm text-base-400">{{ t('import.noMatch') }}</p>

    <ul v-else class="-mr-2 max-h-[26rem] space-y-1.5 overflow-y-auto pr-2">
      <li
        v-for="c in visible"
        :key="c.id"
        class="rounded-md border bg-base-900 px-3 py-2 transition-colors"
        :class="highlighted.has(c.id) ? 'border-lamp-400/50' : 'border-base-700'"
      >
        <div class="flex items-center gap-3">
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium">{{ c.name }}</p>
            <p class="truncate text-xs text-base-400">
              {{ importSourceLabel(c.source) }}<template v-if="!c.versionGuessed">, <span class="font-mono text-base-200">{{ c.gameVersion }}</span> {{ loaderText(c) }}</template><template v-if="c.modCount">, {{ t('import.modCount', c.modCount) }}</template><template v-if="c.worldCount">, {{ t('import.worldCount', c.worldCount) }}</template>
            </p>
          </div>
          <span v-if="done.has(c.id)" class="shrink-0 text-xs text-ok">{{ t('import.imported') }}</span>
          <span v-else-if="running?.id === c.id" class="display shrink-0 text-sm tabular-nums text-redstone-300">{{ running.percent }} %</span>
          <button v-else class="btn btn-primary shrink-0 px-3 py-1.5 text-xs" :disabled="!!running" @click="run(c)">{{ t('common.actions.import') }}</button>
        </div>

        <!-- Selbst gewählter Ordner: Version ist nur geraten, deshalb vor dem Import anpassbar. -->
        <div v-if="c.versionGuessed && overrides[c.id] && !done.has(c.id)" v-for="o in [overrides[c.id]!]" :key="c.id + '-o'" class="mt-2 flex flex-wrap items-center gap-2 text-xs text-base-400">
          <span>{{ t('import.checkVersion') }}</span>
          <select v-model="o.gameVersion" class="field h-7 w-28 py-0 font-mono text-xs" :disabled="!!running" :aria-label="t('import.gameVersion')">
            <option v-for="v in releases.length ? releases : [c.gameVersion]" :key="v" :value="v">{{ v }}</option>
          </select>
          <select v-model="o.loader" class="field h-7 w-32 py-0 text-xs" :disabled="!!running" :aria-label="t('common.labels.loader')">
            <option v-for="k in loaderKinds" :key="k" :value="k">{{ loaderLabels[k] }}</option>
          </select>
        </div>

        <RedstoneWire v-if="running?.id === c.id" :percent="running.percent" :segments="36" class="mt-2" />
      </li>
    </ul>

    <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>

    <template #actions>
      <p v-if="running || packing !== null" class="mr-auto text-xs text-base-400">{{ t('import.backgroundHint') }}</p>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.done') }}</button>
    </template>
  </BaseDialog>
</template>
