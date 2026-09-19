<script setup lang="ts">
const props = defineProps<{ instanceId: string }>()

const games = useGamesStore()
const game = computed(() => games.state(props.instanceId))

const percent = computed(() =>
  game.value.progress ? Math.floor(overallPercent(game.value.progress.stage, game.value.progress.percent)) : 0,
)
const stage = computed(() => (game.value.progress ? stageLabels[game.value.progress.stage] : ''))
</script>

<template>
  <div class="min-w-0 flex-1">
    <button v-if="game.phase === 'idle'" class="btn btn-primary w-full" @click="games.launch(instanceId)">
      <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><path d="M7 4v16l13-8z" /></svg>
      Spielen
    </button>

    <div
      v-else-if="game.phase === 'preparing'"
      class="relative h-9 overflow-hidden rounded-md bg-base-800"
      role="progressbar"
      :aria-valuenow="percent"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-label="stage"
    >
      <div class="absolute inset-y-0 left-0 bg-redstone-600 transition-[width] duration-150" :style="{ width: `${percent}%` }" />
      <div class="relative flex h-full items-center justify-between gap-2 px-3 text-xs font-medium">
        <span class="truncate">{{ stage }}</span>
        <span class="font-mono tabular-nums">{{ percent }} %</span>
      </div>
    </div>

    <button v-else class="btn btn-ghost w-full ring-1 ring-ok/40" @click="games.stop(instanceId)">
      <span class="size-2 animate-pulse rounded-full bg-ok" />
      Läuft – Stoppen
    </button>
  </div>
</template>
