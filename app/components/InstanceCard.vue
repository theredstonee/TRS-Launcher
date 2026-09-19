<script setup lang="ts">
import type { Instance } from '~/types'

const props = defineProps<{ instance: Instance }>()
const emit = defineEmits<{ delete: [instance: Instance] }>()

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
    <div class="flex items-start gap-3">
      <div class="flex size-11 shrink-0 items-center justify-center rounded-md bg-base-800 font-mono text-lg font-bold text-redstone-400">
        {{ instance.name.charAt(0).toUpperCase() }}
      </div>
      <div class="min-w-0 flex-1">
        <h3 class="truncate font-medium" :title="instance.name">{{ instance.name }}</h3>
        <p class="mt-0.5 truncate text-xs text-base-400">
          <span class="font-mono text-base-200">{{ instance.gameVersion }}</span> · {{ loader }}
        </p>
      </div>
    </div>

    <p class="mt-3 text-xs text-base-600">{{ formatRelative(instance.lastPlayed) }}</p>

    <div class="mt-4 flex items-center gap-2">
      <!-- Spielstart folgt mit der Launch-Pipeline (Downloads, Java, Auth). -->
      <button class="btn btn-primary flex-1" disabled title="Spielstart folgt in der nächsten Phase">
        Spielen
      </button>
      <button class="btn btn-ghost px-2.5" title="Ordner öffnen" aria-label="Ordner öffnen" @click="openFolder">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />
        </svg>
      </button>
      <button class="btn btn-ghost px-2.5 hover:text-redstone-300" title="Löschen" aria-label="Löschen" @click="emit('delete', instance)">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
        </svg>
      </button>
    </div>
  </article>
</template>
