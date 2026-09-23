<script setup lang="ts">
import type { ExportEntry, ExportProgress, Instance } from '~/types'

// Instanz als .mrpack exportieren: Name, Version, Beschreibung und die Ordner,
// die mitkommen sollen. Der Speicherort kommt aus dem nativen Dialog in Rust.
const props = defineProps<{ instance: Instance }>()
const emit = defineEmits<{ close: [] }>()

const toasts = useToasts()
// Der Export läuft als Aufgabe – der Dialog darf zu, das Schreiben läuft weiter.
const tasks = useTasksStore()
const exportKey = computed(() => taskKey('export', props.instance.id))
const exportTask = computed(() => tasks.get(exportKey.value))

const entries = ref<ExportEntry[]>([])
const selected = ref<string[]>([])
const loading = ref(true)
const name = ref(props.instance.name)
const version = ref('1.0.0')
const summary = ref('')
const formError = ref<string | null>(null)
const progress = computed(() =>
  exportTask.value?.status === 'running' ? { percent: exportTask.value.percent ?? 0, stage: exportTask.value.stage } : null,
)

/** Text der Export-Phase („Dateien werden geprüft“ …). */
function phaseLabel(phase: ExportProgress['phase']): string {
  return t(`exportPack.phase.${phase}`)
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
  const instance = props.instance
  const result = await tasks.run(
    { key: exportKey.value, kind: 'export', title: parsed.data.name, stage: t('exportPack.pickLocation'), instanceId: instance.id },
    async (ctx) => {
      const summary = await backend.exportModpack(instance.id, parsed.data, (p) =>
        ctx.progress(p.percent, `${phaseLabel(p.phase)} …`),
      )
      if (!summary) {
        // Speichern abgebrochen.
        ctx.discard()
        return null
      }
      ctx.update({
        doneText: t('exportPack.done', {
          file: summary.fileName,
          downloads: summary.downloads,
          overrides: summary.overrides,
          size: formatBytes(summary.bytes),
        }),
      })
      return summary
    },
  )
  if (result.ok) emit('close')
}
</script>

<template>
  <BaseDialog :title="t('exportPack.title')" wide @close="emit('close')">
    <div class="grid gap-3 sm:grid-cols-[1fr_9rem]">
      <div>
        <label class="label" for="ex-name">{{ t('common.labels.name') }}</label>
        <input id="ex-name" v-model="name" class="field" maxlength="64" :disabled="!!progress" />
      </div>
      <div>
        <label class="label" for="ex-version">{{ t('common.labels.version') }}</label>
        <input id="ex-version" v-model="version" class="field" maxlength="32" :disabled="!!progress" />
      </div>
    </div>
    <div class="mt-3">
      <label class="label" for="ex-summary">{{ t('exportPack.summary') }}</label>
      <input id="ex-summary" v-model="summary" class="field" maxlength="512" :placeholder="t('exportPack.summaryPlaceholder')" :disabled="!!progress" />
    </div>

    <p class="label mt-4">{{ t('exportPack.included') }}</p>
    <div v-if="loading" class="space-y-1.5">
      <div v-for="i in 4" :key="i" class="skeleton h-9" />
    </div>
    <p v-else-if="!entries.length" class="text-sm text-base-400">
      {{ t('exportPack.nothing') }}
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
            {{ entry.isDir ? t('exportPack.dirSize', { size: formatBytes(entry.size) }, entry.files) : formatBytes(entry.size) }}
          </span>
        </label>
      </li>
    </ul>

    <p class="mt-3 text-xs leading-relaxed text-base-400">{{ t('exportPack.hint', { size: formatBytes(totalSize) }) }}</p>
    <p v-if="formError" role="alert" class="mt-2 text-xs text-redstone-300">{{ formError }}</p>

    <div v-if="progress" class="mt-4 flex items-center gap-3">
      <RedstoneWire class="flex-1" :percent="progress.percent" :segments="40" />
      <span class="display shrink-0 text-sm text-redstone-300 tabular-nums">{{ Math.floor(progress.percent) }} %</span>
    </div>
    <p v-if="progress" class="mt-1 text-xs text-base-400">{{ t('exportPack.runsInBackground', { stage: progress.stage }) }}</p>

    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ progress ? t('common.actions.close') : t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" :disabled="!!progress || loading || !selected.length" @click="start">
        {{ progress ? t('exportPack.exporting') : t('exportPack.saveAs') }}
      </button>
    </template>
  </BaseDialog>
</template>
