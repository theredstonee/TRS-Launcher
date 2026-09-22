<script setup lang="ts">
import type { ExportEntry, ExportProgress, Instance } from '~/types'

// Instanz als .mrpack exportieren: Name, Version, Beschreibung und die Ordner,
// die mitkommen sollen. Der Speicherort kommt aus dem nativen Dialog in Rust.
const props = defineProps<{ instance: Instance }>()
const emit = defineEmits<{ close: [] }>()

const toasts = useToasts()

const entries = ref<ExportEntry[]>([])
const selected = ref<string[]>([])
const loading = ref(true)
const name = ref(props.instance.name)
const version = ref('1.0.0')
const summary = ref('')
const formError = ref<string | null>(null)
const progress = ref<ExportProgress | null>(null)

const phaseLabels: Record<ExportProgress['phase'], string> = {
  hashing: 'Dateien werden geprüft',
  lookup: 'Abgleich mit Modrinth',
  writing: 'Modpack wird geschrieben',
}

const totalSize = computed(() =>
  entries.value.filter((e) => selected.value.includes(e.name)).reduce((sum, e) => sum + e.size, 0),
)

onMounted(async () => {
  try {
    entries.value = await backend.exportCandidates(props.instance.id)
    selected.value = entries.value.filter((e) => e.recommended).map((e) => e.name)
  } catch (e) {
    toasts.error(e)
  } finally {
    loading.value = false
  }
})

function toggle(entry: ExportEntry) {
  selected.value = selected.value.includes(entry.name)
    ? selected.value.filter((n) => n !== entry.name)
    : [...selected.value, entry.name]
}

async function start() {
  const parsed = exportOptionsSchema.safeParse({
    name: name.value,
    version: version.value,
    summary: summary.value.trim() || null,
    include: selected.value,
  })
  if (!parsed.success) {
    formError.value = firstIssue(parsed.error)
    return
  }
  formError.value = null
  progress.value = { phase: 'hashing', percent: 0 }
  try {
    const result = await backend.exportModpack(props.instance.id, parsed.data, (p) => (progress.value = p))
    if (!result) return // Speichern abgebrochen
    toasts.ok(
      `„${result.fileName}“ geschrieben – ${result.downloads} Dateien von Modrinth, ` +
        `${result.overrides} mitkopiert (${formatBytes(result.bytes)})`,
    )
    emit('close')
  } catch (e) {
    toasts.error(e)
  } finally {
    progress.value = null
  }
}
</script>

<template>
  <BaseDialog title="Als Modpack exportieren" wide @close="progress ? undefined : emit('close')">
    <div class="grid gap-3 sm:grid-cols-[1fr_9rem]">
      <div>
        <label class="label" for="ex-name">Name</label>
        <input id="ex-name" v-model="name" class="field" maxlength="64" :disabled="!!progress" />
      </div>
      <div>
        <label class="label" for="ex-version">Version</label>
        <input id="ex-version" v-model="version" class="field" maxlength="32" :disabled="!!progress" />
      </div>
    </div>
    <div class="mt-3">
      <label class="label" for="ex-summary">Kurzbeschreibung (optional)</label>
      <input id="ex-summary" v-model="summary" class="field" maxlength="512" placeholder="Worum geht es in diesem Pack?" :disabled="!!progress" />
    </div>

    <p class="label mt-4">Das kommt mit</p>
    <div v-if="loading" class="space-y-1.5">
      <div v-for="i in 4" :key="i" class="skeleton h-9" />
    </div>
    <p v-else-if="!entries.length" class="text-sm text-base-400">
      In dieser Instanz liegt noch nichts, was sich exportieren ließe.
    </p>
    <ul v-else class="max-h-56 space-y-1 overflow-y-auto pr-1">
      <li v-for="entry in entries" :key="entry.name">
        <label class="flex cursor-pointer items-center gap-3 rounded-md px-2.5 py-1.5 hover:bg-base-850">
          <input
            type="checkbox"
            class="size-4 accent-redstone-500"
            :checked="selected.includes(entry.name)"
            :disabled="!!progress"
            @change="toggle(entry)"
          />
          <svg viewBox="0 0 24 24" class="size-4 shrink-0 text-base-600" fill="none" stroke="currentColor" stroke-width="2">
            <path v-if="entry.isDir" d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />
            <path v-else d="M6 3h8l4 4v14H6zM14 3v4h4" />
          </svg>
          <span class="min-w-0 flex-1 truncate font-mono text-xs">{{ entry.name }}</span>
          <span class="shrink-0 text-[11px] text-base-400">
            <template v-if="entry.isDir">{{ entry.files }} Dateien · </template>{{ formatBytes(entry.size) }}
          </span>
        </label>
      </li>
    </ul>

    <p class="mt-3 text-xs leading-relaxed text-base-400">
      Mods, die es auf Modrinth gibt, werden nur verlinkt – das Pack bleibt klein und lässt sich weitergeben. Alles
      andere landet als Kopie darin. Ausgewählt: {{ formatBytes(totalSize) }}.
    </p>
    <p v-if="formError" role="alert" class="mt-2 text-xs text-redstone-300">{{ formError }}</p>

    <div v-if="progress" class="mt-4 flex items-center gap-3">
      <RedstoneWire class="flex-1" :percent="progress.percent" :segments="40" />
      <span class="display shrink-0 text-sm text-redstone-300 tabular-nums">{{ Math.floor(progress.percent) }} %</span>
    </div>
    <p v-if="progress" class="mt-1 text-xs text-base-400">{{ phaseLabels[progress.phase] }} …</p>

    <template #actions>
      <button class="btn btn-ghost" :disabled="!!progress" @click="emit('close')">Abbrechen</button>
      <button class="btn btn-primary" :disabled="!!progress || loading || !selected.length" @click="start">
        {{ progress ? 'Exportiere …' : 'Speichern unter …' }}
      </button>
    </template>
  </BaseDialog>
</template>
