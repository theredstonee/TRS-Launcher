<script setup lang="ts">
import type { ImportCandidate } from '~/types'

const emit = defineEmits<{ close: [] }>()

const instances = useInstancesStore()
const toasts = useToasts()

const candidates = ref<ImportCandidate[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const running = ref<{ id: string; percent: number } | null>(null)
const done = ref<Set<string>>(new Set())

onMounted(async () => {
  try {
    candidates.value = await backend.scanImports()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
})

async function run(candidate: ImportCandidate) {
  if (running.value) return
  error.value = null
  running.value = { id: candidate.id, percent: 0 }
  try {
    const instance = await backend.importInstance(candidate.id, (p) => {
      if (running.value) running.value.percent = Math.floor(p.percent)
    })
    done.value = new Set(done.value).add(candidate.id)
    await instances.load()
    toasts.ok(`„${instance.name}“ importiert`)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    running.value = null
  }
}

function loaderText(c: ImportCandidate) {
  return c.loader.version ? `${loaderLabels[c.loader.kind]} ${c.loader.version}` : loaderLabels[c.loader.kind]
}
</script>

<template>
  <BaseDialog title="Aus anderem Launcher importieren" @close="running ? undefined : emit('close')">
    <p class="mb-3 text-sm text-base-400">
      Welten, Mods, Einstellungen und Server werden kopiert – das Original bleibt unverändert. Anmeldedaten anderer
      Launcher werden nie übernommen.
    </p>

    <div v-if="loading" class="space-y-2">
      <div v-for="i in 3" :key="i" class="skeleton h-14" />
    </div>
    <p v-else-if="!candidates.length && !error" class="py-6 text-center text-sm text-base-400">
      Keine Installationen gefunden. Unterstützt werden der offizielle Minecraft Launcher, Prism, MultiMC und CurseForge.
    </p>

    <ul v-else class="-mr-2 max-h-96 space-y-1.5 overflow-y-auto pr-2">
      <li v-for="c in candidates" :key="c.id" class="flex items-center gap-3 rounded-md border border-base-700 bg-base-900 px-3 py-2">
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-medium">{{ c.name }}</p>
          <p class="truncate text-xs text-base-400">
            {{ importSourceLabels[c.source] }} · <span class="font-mono text-base-200">{{ c.gameVersion }}</span> {{ loaderText(c) }}
            <template v-if="c.modCount"> · {{ c.modCount }} Mods</template>
            <template v-if="c.worldCount"> · {{ c.worldCount }} {{ c.worldCount === 1 ? 'Welt' : 'Welten' }}</template>
          </p>
          <RedstoneWire v-if="running?.id === c.id" :percent="running.percent" :segments="28" class="mt-1.5" />
        </div>
        <span v-if="done.has(c.id)" class="shrink-0 text-xs text-ok">Importiert</span>
        <span v-else-if="running?.id === c.id" class="display shrink-0 text-sm tabular-nums text-redstone-300">{{ running.percent }} %</span>
        <button v-else class="btn btn-primary shrink-0 px-3 py-1.5 text-xs" :disabled="!!running" @click="run(c)">Importieren</button>
      </li>
    </ul>

    <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>

    <template #actions>
      <button class="btn btn-ghost" :disabled="!!running" @click="emit('close')">Fertig</button>
    </template>
  </BaseDialog>
</template>
