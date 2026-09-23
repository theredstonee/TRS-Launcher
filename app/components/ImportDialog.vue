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
  const t = Object.values(tasks.tasks).find((t) => t.kind === 'import' && t.status === 'running')
  return t ? { id: t.tag ?? '', percent: t.percent ?? 0 } : null
})
const done = computed(
  () => new Set(Object.values(tasks.tasks).filter((t) => t.kind === 'import' && t.status === 'done').map((t) => t.tag ?? '')),
)
const query = ref('')
const source = ref<ImportSource | 'all'>('all')
/** Bei selbst gewählten Ordnern: Version und Loader vor dem Import anpassbar. */
const overrides = ref<Record<string, { gameVersion: string; loader: LoaderKind }>>({})
const highlighted = ref<Set<string>>(new Set())

const releases = computed(() => (meta.manifest?.versions ?? []).filter((v) => v.type === 'release').map((v) => v.id))

const sources = computed(() => {
  const present = new Set(candidates.value.map((c) => c.source))
  return (Object.keys(importSourceLabels) as ImportSource[]).filter((s) => present.has(s))
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
      stage: `Wird aus ${importSourceLabels[candidate.source]} kopiert`,
      tag: candidate.id,
    },
    async (ctx) => {
      const instance = await backend.importInstance(
        candidate.id,
        o?.gameVersion ?? null,
        o ? { kind: o.loader, version: null } : null,
        (p) => ctx.progress(p.percent, p.totalFiles ? `${p.doneFiles} / ${p.totalFiles} Dateien kopiert` : undefined),
      )
      ctx.update({ instanceId: instance.id, doneText: `„${instance.name}“ importiert` })
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
    { key: PACK_FILE_KEY, kind: 'modpack-file', title: 'Modpack aus Datei', stage: 'Datei wählen …', cancellable: true },
    async (ctx) => {
      const id = await backend.importModpackFile((p) => ctx.progress(packPercent(p), packStageLabels[p.phase]), ctx.taskId)
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
        doneText: instance ? `„${instance.name}“ importiert` : 'Modpack importiert',
      })
      return id
    },
  )
}

function loaderText(c: ImportCandidate) {
  return c.loader.version ? `${loaderLabels[c.loader.kind]} ${c.loader.version}` : loaderLabels[c.loader.kind]
}
</script>

<template>
  <BaseDialog title="Aus anderem Launcher importieren" wide @close="emit('close')">
    <p class="mb-3 text-sm text-base-400">
      Welten, Mods, Einstellungen und Server werden kopiert, das Original bleibt unverändert. Anmeldedaten anderer
      Launcher werden nie übernommen.
    </p>

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <input v-model="query" class="field h-9 min-w-0 flex-1" maxlength="100" placeholder="Suchen …" spellcheck="false" aria-label="Installationen durchsuchen" />
      <select v-if="sources.length > 1" v-model="source" class="field h-9 w-44 py-1" aria-label="Quelle">
        <option value="all">Alle Quellen</option>
        <option v-for="s in sources" :key="s" :value="s">{{ importSourceLabels[s] }}</option>
      </select>
      <button class="btn btn-ghost h-9" :disabled="picking || !!running" @click="browse">
        {{ picking ? 'Durchsuche …' : 'Ordner durchsuchen …' }}
      </button>
      <button class="btn btn-ghost h-9" :disabled="packing !== null || !!running" @click="importPack">
        {{ packing !== null ? `Modpack ${packing} %` : 'Modpack-Datei (.mrpack) …' }}
      </button>
    </div>

    <div v-if="loading" class="space-y-2">
      <div v-for="i in 4" :key="i" class="skeleton h-14" />
    </div>
    <p v-else-if="!candidates.length && !error" class="py-6 text-center text-sm text-base-400">
      Nichts automatisch gefunden. Mit „Ordner durchsuchen“ kannst du jeden Ordner mit Minecraft-Daten wählen,
      zum Beispiel von einem anderen Client.
    </p>
    <p v-else-if="!visible.length" class="py-6 text-center text-sm text-base-400">Keine Installation passt zur Suche.</p>

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
              {{ importSourceLabels[c.source] }}<template v-if="!c.versionGuessed">, <span class="font-mono text-base-200">{{ c.gameVersion }}</span> {{ loaderText(c) }}</template><template v-if="c.modCount">, {{ c.modCount }} Mods</template><template v-if="c.worldCount">, {{ c.worldCount }} {{ c.worldCount === 1 ? 'Welt' : 'Welten' }}</template>
            </p>
          </div>
          <span v-if="done.has(c.id)" class="shrink-0 text-xs text-ok">Importiert</span>
          <span v-else-if="running?.id === c.id" class="display shrink-0 text-sm tabular-nums text-redstone-300">{{ running.percent }} %</span>
          <button v-else class="btn btn-primary shrink-0 px-3 py-1.5 text-xs" :disabled="!!running" @click="run(c)">Importieren</button>
        </div>

        <!-- Selbst gewählter Ordner: Version ist nur geraten, deshalb vor dem Import anpassbar. -->
        <div v-if="c.versionGuessed && overrides[c.id] && !done.has(c.id)" v-for="o in [overrides[c.id]!]" :key="c.id + '-o'" class="mt-2 flex flex-wrap items-center gap-2 text-xs text-base-400">
          <span>Version und Modloader prüfen:</span>
          <select v-model="o.gameVersion" class="field h-7 w-28 py-0 font-mono text-xs" :disabled="!!running" aria-label="Minecraft-Version">
            <option v-for="v in releases.length ? releases : [c.gameVersion]" :key="v" :value="v">{{ v }}</option>
          </select>
          <select v-model="o.loader" class="field h-7 w-32 py-0 text-xs" :disabled="!!running" aria-label="Modloader">
            <option v-for="k in loaderKinds" :key="k" :value="k">{{ loaderLabels[k] }}</option>
          </select>
        </div>

        <RedstoneWire v-if="running?.id === c.id" :percent="running.percent" :segments="36" class="mt-2" />
      </li>
    </ul>

    <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>

    <template #actions>
      <p v-if="running || packing !== null" class="mr-auto text-xs text-base-400">Läuft im Hintergrund weiter, wenn du schließt.</p>
      <button class="btn btn-ghost" @click="emit('close')">Fertig</button>
    </template>
  </BaseDialog>
</template>
