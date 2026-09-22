<script setup lang="ts">
import { convertFileSrc } from '@tauri-apps/api/core'
import type { ImageEntry, Instance } from '~/types'

// Screenshots und Welten einer Instanz.
const props = defineProps<{ instance: Instance; mode: 'screenshots' | 'worlds' }>()
const emit = defineEmits<{ updated: [instance: Instance] }>()

const toasts = useToasts()
const instances = useInstancesStore()
const entries = ref<ImageEntry[]>([])
const loading = ref(true)
const toDelete = ref<ImageEntry | null>(null)

async function load() {
  loading.value = true
  try {
    entries.value =
      props.mode === 'screenshots'
        ? await backend.listScreenshots(props.instance.id)
        : await backend.listWorlds(props.instance.id)
  } catch (e) {
    toasts.error(e)
  } finally {
    loading.value = false
  }
}
watch(() => props.mode, load, { immediate: true })

const src = (entry: ImageEntry) => (entry.path ? convertFileSrc(entry.path) : null)

function open(entry: ImageEntry) {
  if (props.mode === 'screenshots') backend.openScreenshot(props.instance.id, entry.name).catch((e) => toasts.error(e))
}

/** Screenshot als Banner der Instanz übernehmen (der Kern prüft den Dateinamen). */
async function useAsBanner(entry: ImageEntry) {
  try {
    emit('updated', await backend.setInstanceBannerFromScreenshot(props.instance.id, entry.name))
    instances.load()
    toasts.ok('Banner gesetzt')
  } catch (e) {
    toasts.error(e)
  }
}

async function confirmDelete() {
  const entry = toDelete.value
  toDelete.value = null
  if (!entry) return
  try {
    await backend.deleteScreenshot(props.instance.id, entry.name)
    entries.value = entries.value.filter((e) => e.name !== entry.name)
  } catch (e) {
    toasts.error(e)
  }
}
</script>

<template>
  <div class="min-h-0 flex-1 overflow-y-auto pr-1">
    <div v-if="loading" class="grid grid-cols-[repeat(auto-fill,minmax(14rem,1fr))] gap-3">
      <div v-for="i in 6" :key="i" class="skeleton aspect-video" />
    </div>

    <div v-else-if="!entries.length" class="card px-6 py-12 text-center">
      <h2 class="font-semibold">{{ mode === 'screenshots' ? 'Noch keine Screenshots' : 'Noch keine Welten' }}</h2>
      <p class="mx-auto mt-1 max-w-md text-sm text-base-400">
        {{ mode === 'screenshots' ? 'Drück im Spiel F2 – die Bilder landen hier.' : 'Einzelspieler-Welten dieser Instanz erscheinen hier.' }}
      </p>
    </div>

    <ul v-else-if="mode === 'screenshots'" class="grid grid-cols-[repeat(auto-fill,minmax(14rem,1fr))] gap-3">
      <li v-for="e in entries" :key="e.name" class="group relative overflow-hidden rounded-lg border border-base-800 bg-base-900">
        <button class="block w-full" :title="`${e.name} öffnen`" @click="open(e)">
          <img v-if="src(e)" :src="src(e)!" alt="" loading="lazy" class="aspect-video w-full object-cover transition-transform duration-200 group-hover:scale-[1.03]" />
          <div v-else class="aspect-video bg-base-800" />
        </button>
        <div class="flex items-center justify-between gap-2 px-2.5 py-1.5 text-xs">
          <span class="min-w-0 flex-1 truncate text-base-400">{{ formatDate(e.date) }}</span>
          <button class="shrink-0 text-base-600 transition-colors hover:text-base-50" :aria-label="`${e.name} als Banner verwenden`" title="Als Banner der Instanz verwenden" @click="useAsBanner(e)">
            <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.image" /></svg>
          </button>
          <button class="shrink-0 text-base-600 hover:text-redstone-300" aria-label="Screenshot löschen" @click="toDelete = e">
            <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
          </button>
        </div>
      </li>
    </ul>

    <ul v-else class="space-y-1.5">
      <li v-for="e in entries" :key="e.name" class="card flex items-center gap-3 px-3 py-2.5">
        <img v-if="src(e)" :src="src(e)!" alt="" class="size-12 shrink-0 rounded [image-rendering:pixelated]" />
        <div v-else class="display flex size-12 shrink-0 items-center justify-center rounded bg-base-800 text-base-600">
          {{ e.name.charAt(0).toUpperCase() }}
        </div>
        <div class="min-w-0">
          <p class="truncate text-sm font-medium">{{ e.name }}</p>
          <p class="text-xs text-base-400">Zuletzt gespielt {{ formatDate(e.date) }}</p>
        </div>
      </li>
    </ul>

    <BaseDialog v-if="toDelete" title="Screenshot löschen?" @close="toDelete = null">
      <p class="text-sm text-base-200">Der Screenshot wird von der Festplatte gelöscht.</p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">Abbrechen</button>
        <button class="btn btn-danger" @click="confirmDelete">Löschen</button>
      </template>
    </BaseDialog>
  </div>
</template>
