<script setup lang="ts">
import { bisectModName, bisectPercent, type BisectMod } from '~/utils/bisect'

// „Schuldige Mod finden“: kleines Fenster unten in der Mitte, solange eine Suche
// läuft (auch nach einem Neustart des Launchers). Dazu der Start-Dialog.
const bisect = useBisectStore()
const games = useGamesStore()
const instances = useInstancesStore()
const router = useRouter()

const v = computed(() => bisect.current)
const id = computed(() => v.value?.instanceId ?? '')
const instanceName = (iid: string) => instances.items.find((i) => i.id === iid)?.name ?? iid
const phase = computed(() => (id.value ? games.state(id.value).phase : 'idle'))
const busy = computed(() => !!id.value && bisect.isBusy(id.value))
const showSuspects = ref(false)
watch(() => v.value?.round, () => (showSuspects.value = false))

function launch() {
  if (id.value) void games.launch(id.value)
}

function openPage(mod: BisectMod) {
  if (!mod.source) return
  bisect.minimized = true
  router.push(projectRoute(sourcePlatform(mod.source), mod.source.projectId, id.value))
}

function openContent() {
  if (!id.value) return
  bisect.minimized = true
  router.push({ path: `/instances/${id.value}`, query: { tab: 'content' } })
}
</script>

<template>
  <!-- Start bestätigen -->
  <BaseDialog v-if="bisect.confirming" :title="t('bisect.start.title')" @close="bisect.confirming = null">
    <div class="space-y-2.5 text-sm text-base-200">
      <p>{{ t('bisect.start.intro', { name: instanceName(bisect.confirming) }) }}</p>
      <ul class="list-disc space-y-1 pl-5 text-xs text-base-400">
        <li>{{ t('bisect.start.stepHalf') }}</li>
        <li>{{ t('bisect.start.stepAsk') }}</li>
        <li>{{ t('bisect.start.stepKeep') }}</li>
        <li>{{ t('bisect.start.stepRestore') }}</li>
      </ul>
    </div>
    <template #actions>
      <button class="btn btn-ghost" @click="bisect.confirming = null">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" data-testid="bisect-start" @click="bisect.start(bisect.confirming!)">{{ t('bisect.start.submit') }}</button>
    </template>
  </BaseDialog>

  <!-- Laufende Suche -->
  <section
    v-if="v"
    class="card fixed bottom-4 left-1/2 z-[55] w-[26rem] max-w-[calc(100vw-2rem)] -translate-x-1/2 bg-base-850 shadow-2xl ring-1 ring-redstone-600/40"
    role="region"
    :aria-label="t('bisect.title')"
    data-testid="bisect-panel"
  >
    <header class="flex items-center gap-2.5 border-b border-base-800 px-4 py-2.5">
      <span class="grid size-7 shrink-0 place-items-center rounded-md bg-redstone-900 text-redstone-300 ring-1 ring-redstone-600/40">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="11" cy="11" r="6" /><path d="m20 20-4.5-4.5M11 8v3m0 3v.01" /></svg>
      </span>
      <div class="min-w-0 flex-1">
        <p class="truncate text-sm font-semibold">{{ t('bisect.title') }}</p>
        <p class="truncate text-[11px] text-base-400">
          {{ instanceName(v.instanceId) }}
          <template v-if="v.phase === 'testing'"> · {{ t('bisect.round', { round: v.round, total: v.estimatedRounds }) }}</template>
        </p>
      </div>
      <button
        class="btn-icon size-7 bg-transparent"
        :aria-label="bisect.minimized ? t('bisect.expand') : t('bisect.minimize')"
        :title="bisect.minimized ? t('bisect.expand') : t('bisect.minimize')"
        @click="bisect.minimized = !bisect.minimized"
      >
        <svg viewBox="0 0 24 24" class="size-4 transition-transform" :class="{ 'rotate-180': bisect.minimized }" fill="none" stroke="currentColor" stroke-width="2.4"><path d="m6 9 6 6 6-6" /></svg>
      </button>
    </header>

    <div v-if="!bisect.minimized" class="space-y-3 px-4 py-3">
      <!-- Runde läuft -->
      <template v-if="v.phase === 'testing'">
        <div>
          <RedstoneWire :percent="bisectPercent(v)" :segments="20" :powered="phase === 'running'" />
          <p class="mt-1.5 text-xs text-base-400">{{ t('bisect.progress', { suspects: v.suspects.length, off: v.disabled }) }}</p>
        </div>
        <p class="text-sm text-base-200">
          {{ phase === 'idle' ? t('bisect.hintIdle') : phase === 'preparing' ? t('bisect.hintPreparing') : t('bisect.hintRunning') }}
        </p>
        <details class="text-xs" :open="showSuspects" @toggle="showSuspects = ($event.target as HTMLDetailsElement).open">
          <summary class="cursor-pointer select-none text-base-400 hover:text-base-50">{{ t('bisect.showSuspects', v.suspects.length) }}</summary>
          <ul class="mt-1.5 max-h-32 space-y-0.5 overflow-y-auto pr-1 text-base-200">
            <li v-for="m in v.suspects" :key="m.fileName" class="truncate" :title="m.fileName">{{ bisectModName(m) }}</li>
          </ul>
        </details>
        <div class="flex flex-wrap gap-2">
          <button v-if="phase === 'idle'" class="btn btn-primary px-3 py-1.5 text-xs" :disabled="busy" data-testid="bisect-launch" @click="launch">
            {{ t('bisect.launch') }}
          </button>
          <button class="btn btn-ghost px-3 py-1.5 text-xs text-redstone-300" :disabled="busy || phase === 'preparing'" data-testid="bisect-failed" @click="bisect.answer(v.instanceId, true)">
            {{ t('bisect.stillBroken') }}
          </button>
          <button class="btn btn-ghost px-3 py-1.5 text-xs text-ok" :disabled="busy || phase === 'preparing'" data-testid="bisect-ok" @click="bisect.answer(v.instanceId, false)">
            {{ t('bisect.worksFine') }}
          </button>
          <button
            class="ml-auto px-1 text-xs text-base-400 hover:text-base-50 disabled:opacity-50"
            :disabled="busy || phase !== 'idle'"
            :title="phase !== 'idle' ? t('bisect.stopFirst') : t('bisect.cancelTitle')"
            data-testid="bisect-cancel"
            @click="bisect.finish(v.instanceId)"
          >
            {{ t('common.actions.cancel') }}
          </button>
        </div>
      </template>

      <!-- Fund -->
      <template v-else-if="v.phase === 'found'">
        <p class="text-sm text-base-200">{{ v.result.length === 1 ? t('bisect.foundOne') : t('bisect.foundSet', v.result.length) }}</p>
        <ul class="space-y-1.5">
          <li v-for="m in v.result" :key="m.fileName" class="flex items-center gap-2.5 rounded-lg bg-base-900 py-1.5 pr-2 pl-1.5 ring-1 ring-base-700">
            <ModIcon :src="m.iconUrl" :name="bisectModName(m)" :size="28" />
            <div class="min-w-0 flex-1">
              <p class="truncate text-sm font-medium text-base-50">{{ bisectModName(m) }}</p>
              <p class="truncate text-[11px] text-base-400">{{ m.fileName }}</p>
            </div>
            <button v-if="m.source" class="shrink-0 text-xs text-redstone-300 hover:underline" @click="openPage(m)">{{ t('bisect.openPage') }}</button>
          </li>
        </ul>
        <p class="text-[11px] text-base-400">{{ t('bisect.restoredNote', { rounds: v.round }, v.round) }}</p>
        <div class="flex flex-wrap gap-2">
          <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="busy" data-testid="bisect-disable" @click="bisect.finish(v.instanceId, true)">
            {{ t('bisect.disableResult', v.result.length) }}
          </button>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="busy" data-testid="bisect-continue" @click="bisect.keepSearching(v.instanceId)">
            {{ t('bisect.keepSearching') }}
          </button>
          <button class="btn btn-ghost ml-auto px-3 py-1.5 text-xs" :disabled="busy" @click="bisect.finish(v.instanceId)">{{ t('bisect.done') }}</button>
        </div>
      </template>

      <!-- Nichts gefunden -->
      <template v-else>
        <p class="text-sm text-base-200">{{ t('bisect.notFound') }}</p>
        <div class="flex flex-wrap gap-2">
          <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="openContent">{{ t('bisect.openContent') }}</button>
          <button class="btn btn-primary ml-auto px-3 py-1.5 text-xs" :disabled="busy" @click="bisect.finish(v.instanceId)">{{ t('bisect.done') }}</button>
        </div>
      </template>
    </div>
  </section>
</template>
