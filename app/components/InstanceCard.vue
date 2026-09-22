<script setup lang="ts">
import type { Instance } from '~/types'

const props = defineProps<{ instance: Instance }>()
const emit = defineEmits<{ delete: [instance: Instance] }>()

const games = useGamesStore()
const game = computed(() => games.state(props.instance.id))

const loader = computed(() => {
  const { kind, version } = props.instance.loader
  return version ? `${loaderLabels[kind]} ${version}` : loaderLabels[kind]
})

async function openFolder() {
  try {
    await backend.openInstanceDir(props.instance.id)
  } catch {
    // Fehler steht im Log; ein fehlgeschlagenes "Ordner öffnen" braucht keinen Dialog.
  }
}
</script>

<template>
  <article
    class="card card-hover group relative flex flex-col overflow-hidden"
    :class="{ 'border-lamp-400/40 shadow-[0_0_24px_-8px_var(--color-lamp-400)]': game.phase === 'running' }"
  >
    <!-- Farbstreifen des Modloaders als leiser Akzent -->
    <span class="absolute inset-x-0 top-0 h-px opacity-60" :style="{ background: `linear-gradient(90deg, ${loaderColors[instance.loader.kind]}, transparent 70%)` }" />

    <NuxtLink :to="`/instances/${instance.id}`" class="flex items-center gap-3.5 p-4 pb-3 outline-none focus-visible:ring-2 focus-visible:ring-redstone-500 focus-visible:ring-inset">
      <InstanceIcon :instance="instance" :size="56" />
      <div class="min-w-0 flex-1">
        <h3 class="truncate font-semibold text-base-50 transition-colors group-hover:text-redstone-300" :title="instance.name">{{ instance.name }}</h3>
        <p class="mt-1 flex items-center gap-1.5 truncate text-xs text-base-400">
          <span class="size-1.5 shrink-0 rounded-full" :style="{ background: loaderColors[instance.loader.kind] }" />
          <span class="font-mono text-base-200">{{ instance.gameVersion }}</span>
          <span class="truncate">{{ loader }}</span>
        </p>
      </div>
    </NuxtLink>

    <p class="flex justify-between gap-2 px-4 text-xs text-base-600">
      <span class="truncate">{{ game.phase === 'running' ? 'Läuft gerade' : formatRelative(instance.lastPlayed) }}</span>
      <span v-if="instance.totalPlaySeconds >= 60" class="shrink-0 tabular-nums">{{ formatPlayTime(instance.totalPlaySeconds) }}</span>
    </p>

    <p v-if="game.error" role="alert" class="mx-4 mt-2 text-xs text-redstone-300">{{ game.error }}</p>
    <p v-else-if="game.lastExit?.crashed" role="alert" class="mx-4 mt-2 text-xs text-warn">
      {{ game.lastExit.diagnosis?.message ?? `Das Spiel wurde unerwartet beendet (Code ${game.lastExit.exitCode ?? '?'}).` }}
      <NuxtLink :to="`/instances/${instance.id}`" class="underline">Details</NuxtLink>
    </p>

    <div class="mt-auto flex items-center gap-2 p-4 pt-3">
      <PlayButton :instance-id="instance.id" />
      <button class="btn-icon" title="Ordner öffnen" aria-label="Ordner öffnen" @click="openFolder">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />
        </svg>
      </button>
      <button
        class="btn-icon hover:text-redstone-300"
        title="Löschen"
        aria-label="Löschen"
        :disabled="game.phase !== 'idle'"
        @click="emit('delete', instance)"
      >
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
        </svg>
      </button>
    </div>
  </article>
</template>
