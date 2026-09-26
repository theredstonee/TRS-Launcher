<script setup lang="ts">
import { convertFileSrc } from '@tauri-apps/api/core'
import type { ImageEntry, Instance } from '~/types'

// Screenshots einer Instanz (Welten: WorldsPanel).
const props = defineProps<{ instance: Instance }>()
const emit = defineEmits<{ updated: [instance: Instance] }>()

const toasts = useToasts()
const instances = useInstancesStore()
const entries = ref<ImageEntry[]>([])
const loading = ref(true)
const toDelete = ref<ImageEntry | null>(null)

async function load() {
  loading.value = true
  try {
    entries.value = await backend.listScreenshots(props.instance.id)
  } catch (e) {
    toasts.error(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const src = (entry: ImageEntry) => (entry.path ? convertFileSrc(entry.path) : null)

function open(entry: ImageEntry) {
  backend.openScreenshot(props.instance.id, entry.name).catch((e) => toasts.error(e))
}

/** Screenshot als Banner der Instanz übernehmen (der Kern prüft den Dateinamen). */
async function useAsBanner(entry: ImageEntry) {
  try {
    emit('updated', await backend.setInstanceBannerFromScreenshot(props.instance.id, entry.name))
    instances.load()
    toasts.ok(t('instance.gallery.bannerSet'))
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

    <RedstoneEmpty
      v-else-if="!entries.length"
      :seed="0x44"
      :title="t('instance.gallery.noScreenshots.title')"
      :text="t('instance.gallery.noScreenshots.text')"
    />

    <ul v-else class="grid grid-cols-[repeat(auto-fill,minmax(14rem,1fr))] gap-3">
      <li v-for="e in entries" :key="e.name" class="group relative overflow-hidden rounded-lg border border-base-800 bg-base-900">
        <button class="block w-full" :title="t('instance.gallery.openScreenshot', { name: e.name })" @click="open(e)">
          <img v-if="src(e)" :src="src(e)!" alt="" loading="lazy" class="aspect-video w-full object-cover transition-transform duration-200 group-hover:scale-[1.03]" />
          <div v-else class="aspect-video bg-base-800" />
        </button>
        <div class="flex items-center justify-between gap-2 px-2.5 py-1.5 text-xs">
          <span class="min-w-0 flex-1 truncate text-base-400">{{ formatDate(e.date) }}</span>
          <button class="shrink-0 text-base-600 transition-colors hover:text-base-50" :aria-label="t('instance.gallery.useAsBannerLabel', { name: e.name })" :title="t('instance.gallery.useAsBannerTitle')" @click="useAsBanner(e)">
            <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.image" /></svg>
          </button>
          <button class="shrink-0 text-base-600 hover:text-redstone-300" :aria-label="t('instance.gallery.deleteScreenshot')" @click="toDelete = e">
            <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
          </button>
        </div>
      </li>
    </ul>


    <BaseDialog v-if="toDelete" :title="t('instance.gallery.deleteTitle')" @close="toDelete = null">
      <p class="text-sm text-base-200">{{ t('instance.gallery.deleteText') }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="toDelete = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmDelete">{{ t('common.actions.delete') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
