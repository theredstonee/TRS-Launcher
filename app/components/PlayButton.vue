<script setup lang="ts">
const props = withDefaults(defineProps<{ instanceId: string; large?: boolean }>(), { large: false })

const games = useGamesStore()
const game = computed(() => games.state(props.instanceId))

const percent = computed(() =>
  game.value.progress ? Math.floor(overallPercent(game.value.progress.stage, game.value.progress.percent)) : 0,
)
const stage = computed(() => (game.value.progress ? stageLabels[game.value.progress.stage] : ''))
const files = computed(() => {
  const p = game.value.progress
  return p && p.totalFiles > 1 ? `${p.doneFiles} / ${p.totalFiles}` : ''
})
</script>

<template>
  <div class="min-w-0 flex-1">
    <button
      v-if="game.phase === 'idle'"
      class="btn btn-primary w-full"
      :class="large ? 'h-12 text-base' : ''"
      @click="games.launch(instanceId)"
    >
      <svg viewBox="0 0 24 24" :class="large ? 'size-5' : 'size-4'" fill="currentColor"><path d="M7 4v16l13-8z" /></svg>
      Spielen
    </button>

    <div
      v-else-if="game.phase === 'preparing'"
      class="flex flex-col justify-center rounded-md bg-base-800 px-3"
      :class="large ? 'h-12' : 'h-9'"
      role="progressbar"
      :aria-valuenow="percent"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-label="stage"
    >
      <div class="flex items-baseline justify-between gap-2 text-xs font-medium">
        <span class="truncate">
          {{ stage }}<span v-if="large && files" class="ml-2 font-normal text-base-400">{{ files }}</span>
        </span>
        <span class="display tabular-nums text-redstone-300" :class="large ? 'text-base' : ''">{{ percent }} %</span>
      </div>
      <RedstoneWire :percent="percent" :segments="large ? 32 : 20" class="mt-1" />
    </div>

    <button
      v-else
      class="btn w-full bg-lamp-900 text-lamp-300 ring-1 ring-lamp-400/40 hover:bg-base-800"
      :class="large ? 'h-12 text-base' : ''"
      @click="games.stop(instanceId)"
    >
      <span class="size-2 animate-lamp rounded-full bg-lamp-400" />
      Läuft – stoppen
    </button>
  </div>
</template>
