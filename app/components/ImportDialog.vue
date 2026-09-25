<script setup lang="ts">
import type { DetectedLauncher, ImportCandidate, ImportResult, ImportSource, LoaderKind } from '~/types'

const emit = defineEmits<{ close: [] }>()

const instances = useInstancesStore()
const meta = useMetaStore()
// Importe laufen als Aufgaben – der Dialog darf zu, die Kopie läuft weiter.
const tasks = useTasksStore()

const candidates = ref<ImportCandidate[]>([])
/** Erkannte Launcher (auch ohne Installationen oder nicht unterstützt). */
const launchers = ref<DetectedLauncher[]>([])
/** Aufgeklappte Vorschau je Kandidat. */
const expanded = ref<Set<string>>(new Set())
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
    const overview = await backend.importOverview()
    candidates.value = overview.candidates
    launchers.value = [...overview.launchers].sort((a, b) => importSources.indexOf(a.source) - importSources.indexOf(b.source))
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
      const result = await backend.importInstance(
        candidate.id,
        o?.gameVersion ?? null,
        o ? { kind: o.loader, version: null } : null,
        (p) => ctx.progress(p.percent, p.totalFiles ? t('import.task.filesCopied', { done: p.doneFiles, total: p.totalFiles }) : undefined),
      )
      const { instance } = result
      ctx.update({ instanceId: instance.id, doneText: resultText(result) })
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

/** Abschlusstext: Name plus, was beim Nachladen von CurseForge passiert ist. */
function resultText(result: ImportResult): string {
  const parts = [t('import.task.done', { name: result.instance.name })]
  if (result.downloaded) parts.push(t('import.task.downloaded', result.downloaded))
  if (result.blocked) parts.push(t('import.task.manual', result.blocked))
  if (result.failed) parts.push(t('import.task.failed', result.failed))
  return parts.join(' · ')
}

function toggleDetails(id: string) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

/** Was mitkommt – nur Einträge, die es wirklich gibt. */
function previewItems(c: ImportCandidate): string[] {
  const items: string[] = []
  if (c.worldCount) items.push(t('import.worldCount', c.worldCount))
  if (c.modCount && c.loader.kind !== 'vanilla') items.push(t('import.modCount', c.modCount))
  if (c.resourcePackCount) items.push(t('import.preview.resourcePacks', c.resourcePackCount))
  if (c.shaderPackCount) items.push(t('import.preview.shaderPacks', c.shaderPackCount))
  if (c.hasOptions) items.push(t('import.preview.options'))
  if (c.hasServers) items.push(t('import.preview.servers'))
  if (c.trackedCount) items.push(t('import.preview.tracked', c.trackedCount))
  if (c.missingCount) items.push(t('import.preview.missing', c.missingCount))
  return items
}

/** Kurzzeichen für die Launcher-Plakette (keine fremden Logos). */
function badge(source: ImportSource): string {
  const label = importSourceLabel(source).replace(/[^A-Za-z ]/g, '').trim()
  const words = label.split(' ').filter(Boolean)
  const letters = words.length > 1 ? `${words[0]?.[0] ?? ''}${words[1]?.[0] ?? ''}` : label.slice(0, 2)
  return (letters || '?').toUpperCase()
}

/** Klick auf einen erkannten Launcher filtert die Liste (erneuter Klick hebt auf). */
function pickSource(picked: ImportSource) {
  source.value = source.value === picked ? 'all' : picked
}

function loaderText(c: ImportCandidate) {
  return c.loader.version ? `${loaderLabels[c.loader.kind]} ${c.loader.version}` : loaderLabels[c.loader.kind]
}
</script>

<template>
  <BaseDialog :title="t('import.title')" wide @close="emit('close')">
    <p class="mb-3 text-sm text-base-400">{{ t('import.intro') }}</p>

    <!-- Erkannte Launcher: Klick filtert, nicht unterstützte erklären sich selbst. -->
    <section v-if="launchers.length" class="mb-3" :aria-label="t('import.launchers.title')">
      <h3 class="mb-1.5 text-xs font-medium uppercase tracking-wide text-base-400">{{ t('import.launchers.title') }}</h3>
      <ul class="flex flex-wrap gap-1.5">
        <li v-for="l in launchers" :key="l.source">
          <button
            type="button"
            class="flex items-center gap-2 rounded-md border px-2 py-1 text-xs transition-colors"
            :class="[
              source === l.source ? 'border-lamp-400/60 bg-base-800' : 'border-base-700 bg-base-900 hover:border-base-600',
              !l.supported || !l.instances ? 'cursor-default opacity-70' : '',
            ]"
            :disabled="!l.supported || !l.instances"
            :aria-pressed="source === l.source"
            :title="l.supported ? undefined : t('import.launchers.unsupportedHint', { name: importSourceLabel(l.source) })"
            @click="pickSource(l.source)"
          >
            <span class="grid size-5 shrink-0 place-items-center rounded bg-base-700 text-[10px] font-semibold text-base-200" aria-hidden="true">{{ badge(l.source) }}</span>
            <span class="text-base-200">{{ importSourceLabel(l.source) }}</span>
            <span v-if="l.supported" class="tabular-nums text-base-400">{{ t('import.launchers.count', l.instances) }}</span>
            <span v-else class="text-redstone-300">{{ t('import.launchers.unsupported') }}</span>
          </button>
        </li>
      </ul>
      <p v-for="l in launchers.filter((x) => !x.supported)" :key="l.source + '-hint'" class="mt-1.5 text-xs text-base-400">
        {{ t('import.launchers.unsupportedHint', { name: importSourceLabel(l.source) }) }}
      </p>
    </section>

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <input v-model="query" class="field h-9 min-w-48 flex-1" maxlength="100" :placeholder="t('import.search')" spellcheck="false" :aria-label="t('import.searchLabel')" />
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
          <button
            type="button"
            class="btn btn-ghost shrink-0 px-2 py-1 text-xs"
            :aria-expanded="expanded.has(c.id)"
            @click="toggleDetails(c.id)"
          >
            {{ expanded.has(c.id) ? t('import.preview.hide') : t('import.preview.show') }}
          </button>
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

        <!-- Vorschau: was mitkommt und was nicht. -->
        <div v-if="expanded.has(c.id)" class="mt-2 border-t border-base-800 pt-2 text-xs">
          <p class="mb-1 font-medium text-base-200">{{ t('import.preview.title') }}</p>
          <ul v-if="previewItems(c).length" class="flex flex-wrap gap-x-3 gap-y-1 text-base-200">
            <li v-for="item in previewItems(c)" :key="item">{{ item }}</li>
          </ul>
          <p v-else class="text-base-400">{{ t('import.preview.empty') }}</p>
          <p v-for="n in c.notes" :key="n" class="mt-1.5 text-lamp-300">{{ tKey(`import.notes.${n}`) }}</p>
          <p class="mt-1.5 text-base-400">{{ t('import.preview.never') }}</p>
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
