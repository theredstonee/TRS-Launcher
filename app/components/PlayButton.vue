<script setup lang="ts">
// Spielen-Knopf. In groß (Startseite, Instanzseite) ist er eine Redstone-Lampe:
// aus, solange nichts läuft, lädt sich beim Start mit dem Fortschritt auf und
// leuchtet, sobald das Spiel läuft.
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
  <!-- Groß: Redstone-Lampe -->
  <div v-if="large" class="lamp-wrap min-w-0 flex-1" :class="`lamp-${game.phase}`" :style="{ '--charge': percent / 100 }">
    <button
      v-if="game.phase === 'idle'"
      class="lamp pixel-corners"
      @click="games.launch(instanceId)"
    >
      <span class="lamp-light" />
      <span class="lamp-glass" />
      <span class="relative flex items-center gap-2.5">
        <svg viewBox="0 0 24 24" class="size-5" fill="currentColor"><path d="M7 4v16l13-8z" /></svg>
        <span class="display text-xl">Spielen</span>
      </span>
    </button>

    <div
      v-else-if="game.phase === 'preparing'"
      class="lamp pixel-corners cursor-progress"
      role="progressbar"
      :aria-valuenow="percent"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-label="stage"
    >
      <span class="lamp-light" />
      <span class="lamp-glass" />
      <span class="relative flex w-full items-baseline justify-between gap-3 px-4">
        <span class="min-w-0 truncate text-left text-xs font-medium">
          {{ stage }}<span v-if="files" class="ml-1.5 font-normal opacity-70">{{ files }}</span>
        </span>
        <span class="display shrink-0 text-xl tabular-nums">{{ percent }} %</span>
      </span>
    </div>

    <button v-else class="lamp pixel-corners" title="Spiel beenden" @click="games.stop(instanceId)">
      <span class="lamp-light" />
      <span class="lamp-glass" />
      <span class="relative flex items-center gap-2.5">
        <span class="display text-xl">Läuft</span>
        <span class="text-xs font-medium opacity-75">Stoppen</span>
      </span>
    </button>
  </div>

  <!-- Klein: schlichter Knopf (Listen, Kacheln) -->
  <div v-else class="min-w-0 flex-1">
    <button v-if="game.phase === 'idle'" class="btn btn-primary w-full" @click="games.launch(instanceId)">
      <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><path d="M7 4v16l13-8z" /></svg>
      Spielen
    </button>

    <div
      v-else-if="game.phase === 'preparing'"
      class="flex h-9 flex-col justify-center rounded-md bg-base-800 px-3"
      role="progressbar"
      :aria-valuenow="percent"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-label="stage"
    >
      <div class="flex items-baseline justify-between gap-2 text-xs font-medium">
        <span class="truncate">{{ stage }}</span>
        <span class="display tabular-nums text-redstone-300">{{ percent }} %</span>
      </div>
      <RedstoneWire :percent="percent" :segments="20" class="mt-1" />
    </div>

    <button v-else class="btn w-full bg-lamp-900 text-lamp-300 ring-1 ring-lamp-400/40 hover:bg-base-800" @click="games.stop(instanceId)">
      <span class="size-2 animate-lamp rounded-full bg-lamp-400" />
      Läuft – stoppen
    </button>
  </div>
</template>

<style scoped>
/*
 * Die Lampe: Rahmen in der Akzentfarbe (Redstone), innen das Glas einer
 * Redstone-Lampe mit Gitter. Das Leuchten sitzt als drop-shadow auf der Hülle,
 * weil die Pixel-Ecken (clip-path) einen box-shadow abschneiden würden.
 */
.lamp-wrap {
  --lamp-off: color-mix(in srgb, var(--color-lamp-400) 16%, var(--color-base-850));
  --lamp-line: color-mix(in srgb, var(--color-lamp-400) 30%, var(--color-base-700));
  transition: filter 0.3s ease;
}
.lamp {
  position: relative;
  display: flex;
  width: 100%;
  height: 3.5rem;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  color: var(--color-base-50);
  background: var(--lamp-off);
  box-shadow:
    inset 0 0 0 3px var(--color-redstone-500),
    inset 0 0 0 5px color-mix(in srgb, var(--color-redstone-900) 70%, black);
  transition: color 0.2s ease, background-color 0.3s ease, transform 0.1s ease;
}
button.lamp:active {
  transform: translateY(1px);
}
.lamp:focus-visible {
  outline-offset: -8px;
  outline-color: var(--color-base-50);
}
/* Glas der Lampe: Gitterlinien wie die Minecraft-Textur; darunter das Licht. */
.lamp-glass {
  position: absolute;
  inset: 5px;
  background:
    repeating-linear-gradient(90deg, var(--lamp-line) 0 2px, transparent 2px 18px),
    repeating-linear-gradient(0deg, var(--lamp-line) 0 2px, transparent 2px 18px);
  opacity: 0.55;
  transition: opacity 0.3s ease;
}
.lamp-light {
  position: absolute;
  inset: 5px;
  background: radial-gradient(90% 140% at 50% 50%, var(--color-lamp-300), var(--color-lamp-400) 60%, color-mix(in srgb, var(--color-lamp-400) 70%, var(--color-redstone-500)));
  opacity: 0;
  transition: opacity 0.35s ease;
}

.lamp-idle {
  filter: drop-shadow(0 0 10px color-mix(in srgb, var(--color-redstone-500) 35%, transparent));
}
.lamp-idle:hover {
  filter: drop-shadow(0 0 16px color-mix(in srgb, var(--color-redstone-400) 55%, transparent));
}
.lamp-idle:hover .lamp-light {
  opacity: 0.16;
}

/* Beim Start lädt sich die Lampe mit dem Fortschritt auf und flackert leicht. */
.lamp-preparing .lamp-light {
  opacity: calc(0.12 + var(--charge) * 0.5);
  animation: flicker 1.6s steps(4, end) infinite;
}
.lamp-preparing {
  filter: drop-shadow(0 0 calc(8px + var(--charge) * 14px) color-mix(in srgb, var(--color-lamp-400) 45%, transparent));
}

/* Läuft: Lampe an. */
.lamp-running .lamp {
  color: #2b1a02;
  box-shadow:
    inset 0 0 0 3px var(--color-lamp-300),
    inset 0 0 0 5px color-mix(in srgb, var(--color-lamp-400) 60%, #6b3a00);
}
.lamp-running .lamp-glass {
  opacity: 1;
  background:
    repeating-linear-gradient(90deg, rgb(255 255 255 / 0.4) 0 2px, transparent 2px 18px),
    repeating-linear-gradient(0deg, rgb(255 255 255 / 0.4) 0 2px, transparent 2px 18px);
}
.lamp-running .lamp-light {
  opacity: 1;
}
.lamp-running {
  filter: drop-shadow(0 0 22px color-mix(in srgb, var(--color-lamp-400) 70%, transparent));
}

@keyframes flicker {
  0%,
  100% {
    filter: brightness(1);
  }
  50% {
    filter: brightness(1.18);
  }
}
</style>
