<script setup lang="ts">
import type { WorldInfo } from '~/types'
import type { TransferStep } from '~/stores/craftinghosting'
import { modsMatch } from '~/utils/craftinghosting'

// Nach der Ersteinrichtung: Mods und Welt der Ursprungs-Instanz in einem Rutsch übernehmen.
const store = useCraftingHostingStore()
const instances = useInstancesStore()

const instance = computed(() => instances.items.find((i) => i.id === store.origin?.instanceId) ?? null)
const server = computed(() => store.server!)
const job = computed(() => store.transfer)
const withMods = ref(true)
const worlds = ref<WorldInfo[]>([])
const world = ref('')
const loadingWorlds = ref(false)

const modsPossible = computed(() => !!instance.value && modsMatch(instance.value.loader.kind, server.value.serverType))
const finished = computed(() => !!job.value && !job.value.active)
const succeeded = computed(() => finished.value && job.value!.steps.start === 'done')

onMounted(async () => {
  if (!instances.items.length) await instances.load().catch(() => {})
  if (!instance.value) return
  loadingWorlds.value = true
  try {
    worlds.value = await backend.instanceWorlds(instance.value.id)
    world.value = worlds.value[0]?.folder ?? ''
  } catch {
    worlds.value = []
  } finally {
    loadingWorlds.value = false
  }
})

const STEPS: TransferStep[] = ['stop', 'mods', 'world', 'start']

async function run() {
  if (!instance.value) return
  await store.transferFromInstance({ instanceId: instance.value.id, mods: modsPossible.value && withMods.value, world: world.value || null })
}

function close() {
  store.transfer = null
  store.dismissOrigin()
}
</script>

<template>
  <section class="ch-transfer card relative overflow-hidden p-5" data-testid="hosting-transfer" aria-labelledby="ch-transfer-title">
    <span class="ch-transfer-glow" aria-hidden="true" />
    <div class="relative flex items-start gap-4">
      <InstanceIcon v-if="instance" :instance="instance" :size="52" />
      <div class="min-w-0 flex-1">
        <h3 id="ch-transfer-title" class="text-base font-semibold text-base-50">
          {{ t('craftinghosting.transfer.title', { instance: instance?.name ?? '…' }) }}
        </h3>
        <p v-if="!instance" class="mt-1 text-sm text-base-400">{{ t('craftinghosting.transfer.missing') }}</p>
        <p v-else-if="!job" class="mt-1 text-sm text-base-200">{{ t('craftinghosting.transfer.text') }}</p>
        <p v-else-if="succeeded" class="mt-1 text-sm text-ok" data-testid="hosting-transfer-done">{{ t('craftinghosting.transfer.done') }}</p>
        <p v-else-if="finished" class="mt-1 text-sm text-redstone-300">{{ t('craftinghosting.transfer.failed') }}</p>
      </div>
    </div>

    <!-- Auswahl -->
    <div v-if="instance && !job?.active && !succeeded" class="relative mt-4 grid gap-3 sm:grid-cols-2">
      <label
        class="flex items-center justify-between gap-3 rounded-lg bg-base-950/50 px-3 py-2.5 text-sm ring-1 ring-base-800"
        :class="{ 'opacity-60': !modsPossible }"
      >
        <span class="min-w-0">
          <span class="block text-base-50">{{ t('craftinghosting.transfer.mods') }}</span>
          <span class="block text-xs text-base-400">{{ modsPossible ? t('craftinghosting.transfer.modsHint') : t('craftinghosting.transfer.modsNo') }}</span>
        </span>
        <ToggleSwitch v-model="withMods" :label="t('craftinghosting.transfer.mods')" :disabled="!modsPossible" />
      </label>
      <div>
        <label for="ch-transfer-world" class="mb-1 block text-xs text-base-400">{{ t('craftinghosting.world.world') }}</label>
        <select id="ch-transfer-world" v-model="world" class="field" :disabled="loadingWorlds">
          <option v-for="w in worlds" :key="w.folder" :value="w.folder">{{ w.name }}{{ w.version ? ` · ${w.version}` : '' }}</option>
          <option value="">{{ t('craftinghosting.transfer.noWorld') }}</option>
        </select>
      </div>
    </div>

    <!-- Fortschritt -->
    <ol v-if="job" class="relative mt-4 grid gap-2 sm:grid-cols-4" data-testid="hosting-transfer-steps">
      <li
        v-for="id in STEPS"
        :key="id"
        class="flex items-center gap-2 rounded-lg px-3 py-2 text-xs ring-1"
        :class="{
          'bg-base-950/50 text-base-400 ring-base-800': job.steps[id] === 'pending' || job.steps[id] === 'skipped',
          'bg-lamp-900/30 text-lamp-300 ring-lamp-400/30': job.steps[id] === 'running',
          'bg-ok/10 text-ok ring-ok/30': job.steps[id] === 'done',
          'bg-redstone-900/40 text-redstone-300 ring-redstone-600/50': job.steps[id] === 'failed',
        }"
        :data-state="job.steps[id]"
      >
        <svg v-if="job.steps[id] === 'done'" viewBox="0 0 16 16" class="size-3.5 shrink-0" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="m3.5 8.5 3 3 6-7" /></svg>
        <svg v-else-if="job.steps[id] === 'failed'" viewBox="0 0 16 16" class="size-3.5 shrink-0" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="m4 4 8 8M12 4l-8 8" /></svg>
        <span v-else-if="job.steps[id] === 'running'" class="size-2 shrink-0 animate-lamp rounded-full bg-current" aria-hidden="true" />
        <span v-else class="size-2 shrink-0 rounded-full bg-current opacity-40" aria-hidden="true" />
        <span :class="{ 'line-through': job.steps[id] === 'skipped' }">{{ t(`craftinghosting.transfer.steps.${id}`) }}</span>
        <span v-if="id === 'world' && job.steps.world === 'running' && store.worldProgress" class="ml-auto tabular-nums">{{ store.worldProgress.percent }} %</span>
      </li>
    </ol>
    <p class="sr-only" aria-live="polite">{{ job?.active ? t('craftinghosting.transfer.working') : '' }}</p>

    <!-- Mods-Ergebnis -->
    <div v-if="job?.mods" class="relative mt-3 space-y-1.5 text-xs" data-testid="hosting-transfer-mods">
      <p v-if="job.mods.result" class="text-ok">{{ t('craftinghosting.mods.installed', { n: job.mods.result.installed.length }) }}</p>
      <p v-else class="text-base-400">{{ t('craftinghosting.mods.noneTransferable') }}</p>
      <details v-if="job.mods.result?.skipped.length || job.mods.other.length" class="text-base-400">
        <summary class="cursor-pointer text-base-200 hover:text-base-50">
          {{ t('craftinghosting.transfer.notMoved', { n: (job.mods.result?.skipped.length ?? 0) + job.mods.other.length }) }}
        </summary>
        <ul class="mt-1 max-h-40 space-y-0.5 overflow-y-auto pl-1">
          <li v-for="s in job.mods.result?.skipped ?? []" :key="s.project">
            <span class="font-mono">{{ s.project }}</span> – {{ t(`craftinghosting.mods.reason.${s.reason}`) }}
          </li>
          <li v-for="name in job.mods.other" :key="name" class="truncate">{{ name }} – {{ t('craftinghosting.transfer.notModrinth') }}</li>
        </ul>
      </details>
    </div>

    <div class="relative mt-4 flex flex-wrap items-center gap-2">
      <template v-if="instance && !succeeded">
        <button class="btn btn-primary" :disabled="!!job?.active || !!store.busy" data-testid="hosting-transfer-run" @click="run">
          {{ job?.active ? t('craftinghosting.transfer.working') : finished ? t('common.actions.retry') : t('craftinghosting.transfer.button') }}
        </button>
        <button v-if="!job?.active" class="btn btn-ghost" data-testid="hosting-transfer-dismiss" @click="close">{{ t('craftinghosting.transfer.dismiss') }}</button>
      </template>
      <button v-else class="btn btn-ghost" @click="close">{{ t('common.actions.close') }}</button>
    </div>
  </section>
</template>

<style scoped>
.ch-transfer {
  border-color: color-mix(in srgb, var(--color-redstone-500) 35%, var(--color-base-800));
}
.ch-transfer-glow {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(90% 120% at 0% 0%, color-mix(in srgb, var(--color-redstone-500) 14%, transparent), transparent 60%),
    radial-gradient(60% 80% at 100% 100%, color-mix(in srgb, var(--color-lamp-400) 7%, transparent), transparent 60%);
}
</style>
