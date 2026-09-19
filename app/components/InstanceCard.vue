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
  <article class="card group flex flex-col p-4 transition-colors hover:border-base-700">
    <NuxtLink :to="`/instances/${instance.id}`" class="flex items-start gap-3 rounded-md outline-none focus-visible:ring-2 focus-visible:ring-redstone-500">
      <div class="flex size-11 shrink-0 items-center justify-center rounded-md bg-base-800 font-mono text-lg font-bold text-redstone-400">
        {{ instance.name.charAt(0).toUpperCase() }}
      </div>
      <div class="min-w-0 flex-1">
        <h3 class="truncate font-medium group-hover:text-redstone-300" :title="instance.name">{{ instance.name }}</h3>
        <p class="mt-0.5 truncate text-xs text-base-400">
          <span class="font-mono text-base-200">{{ instance.gameVersion }}</span> · {{ loader }}
        </p>
      </div>
    </NuxtLink>

    <p class="mt-3 flex justify-between gap-2 text-xs text-base-600">
      <span class="truncate">{{ formatRelative(instance.lastPlayed) }}</span>
      <span v-if="instance.totalPlaySeconds >= 60" class="shrink-0">{{ formatPlayTime(instance.totalPlaySeconds) }}</span>
    </p>

    <p v-if="game.error" role="alert" class="mt-2 text-xs text-redstone-300">{{ game.error }}</p>
    <p v-else-if="game.lastExit?.crashed" role="alert" class="mt-2 text-xs text-warn">
      Das Spiel wurde unerwartet beendet (Code {{ game.lastExit.exitCode ?? '?' }}).
      <NuxtLink :to="`/instances/${instance.id}`" class="underline">Logs ansehen</NuxtLink>
    </p>

    <div class="mt-4 flex items-center gap-2">
      <PlayButton :instance-id="instance.id" />
      <button class="btn btn-ghost px-2.5" title="Ordner öffnen" aria-label="Ordner öffnen" @click="openFolder">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />
        </svg>
      </button>
      <button
        class="btn btn-ghost px-2.5 hover:text-redstone-300"
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
