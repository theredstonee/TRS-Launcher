<script setup lang="ts">
import type { Instance } from '~/types'

// Instanz-Kachel im Big-Picture-Modus: A startet das Spiel; läuft es schon (oder
// wird noch installiert), öffnet A die Optionen. Y öffnet immer die Optionen.
const props = defineProps<{ instance: Instance }>()

const games = useGamesStore()
const tasks = useTasksStore()
const store = useBigPictureStore()

const game = computed(() => games.state(props.instance.id))
const install = computed(() => (game.value.phase === 'idle' ? tasks.installingInstance(props.instance.id) : null))
const percent = computed(() => {
  const p = game.value.progress
  if (p) return Math.floor(overallPercent(p.stage, p.percent))
  return install.value?.percent ?? null
})
const busy = computed(() => game.value.phase !== 'idle' || !!install.value)

function select() {
  if (!busy.value) void games.launch(props.instance.id)
  else store.openLayer({ kind: 'instance', id: props.instance.id })
}
</script>

<template>
  <button
    type="button"
    class="bp-tile group"
    :class="{ 'tile-live': busy }"
    :data-instance="instance.id"
    :aria-label="busy ? instance.name : t('play.playNamed', { name: instance.name })"
    @click="select"
  >
    <InstanceBanner :instance="instance" shade="bottom" class="h-36 w-full" />
    <span class="flex items-center gap-4 px-4 pt-0 pb-4">
      <InstanceIcon :instance="instance" :size="64" class="-mt-10 shrink-0 ring-4 ring-base-900" />
      <span class="min-w-0 flex-1 pt-2">
        <span class="block truncate text-xl font-semibold text-base-50">{{ instance.name }}</span>
        <span class="block truncate text-base text-base-400">
          <span class="font-mono">{{ instance.gameVersion }}</span> {{ loaderLabels[instance.loader.kind] }} · {{ formatRelative(instance.lastPlayed, true) }}
        </span>
      </span>
    </span>
    <span v-if="busy" class="state badge bg-lamp-400 text-base-950">
      <span class="size-2 animate-lamp bg-base-950" />
      <template v-if="game.phase === 'running'">{{ t('common.status.running') }}</template>
      <template v-else-if="percent !== null">{{ t('tasks.percent', { percent }) }}</template>
      <template v-else>{{ t('play.startingShort') }}</template>
    </span>
    <span v-else class="play" aria-hidden="true">
      <svg viewBox="0 0 24 24" class="ml-0.5 size-6" fill="currentColor"><path :d="icons.play" /></svg>
    </span>
  </button>
</template>

<style scoped>
.state {
  position: absolute;
  top: 0.75rem;
  left: 0.75rem;
  font-size: 0.95rem;
  padding: 0.3rem 0.65rem;
}
.play {
  position: absolute;
  top: 0.75rem;
  right: 0.75rem;
  display: grid;
  place-items: center;
  width: 3rem;
  height: 3rem;
  border-radius: 0.75rem;
  background: var(--color-redstone-500);
  color: #fff;
  opacity: 0;
  transform: scale(0.85);
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
:global(.bp-page[data-input='nav']) .bp-tile:focus .play,
:global(.bp-page[data-input='pointer']) .bp-tile:hover .play {
  opacity: 1;
  transform: scale(1);
}
.tile-live {
  border-color: color-mix(in srgb, var(--color-lamp-400) 60%, var(--color-base-800));
}
</style>
