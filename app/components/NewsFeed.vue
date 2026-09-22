<script setup lang="ts">
import { convertFileSrc } from '@tauri-apps/api/core'
import type { NewsItem, NewsSource } from '~/types'

// Neuigkeiten für die Startseite: Minecraft-Patchnotes, Mojang-News, gerade
// beliebte Modrinth-Projekte und neue Launcher-Versionen. Geholt, geprüft und
// zwischengespeichert wird alles im Kern; Bilder kommen aus dessen Cache.
const props = withDefaults(defineProps<{ limit?: number }>(), { limit: 12 })

const toasts = useToasts()
const items = ref<NewsItem[]>([])
const images = ref<Record<string, string>>({})
const loading = ref(true)
const error = ref<string | null>(null)
const stale = ref(false)
const refreshing = ref(false)

const filters: { key: NewsSource | 'all'; label: string }[] = [
  { key: 'all', label: 'Alles' },
  { key: 'patchNotes', label: 'Updates' },
  { key: 'mojang', label: 'Minecraft' },
  { key: 'modrinth', label: 'Mods' },
  { key: 'launcher', label: 'Launcher' },
]
const filter = ref<NewsSource | 'all'>('all')

const shown = computed(() =>
  items.value.filter((i) => filter.value === 'all' || i.source === filter.value).slice(0, props.limit),
)

const sourceLabels: Record<NewsSource, string> = {
  patchNotes: 'Minecraft-Update',
  mojang: 'Minecraft',
  modrinth: 'Modrinth',
  launcher: 'TRS Launcher',
}

async function load(force = false) {
  if (force) refreshing.value = true
  try {
    const feed = await backend.getNews(force)
    // Neueste zuerst, dabei Quellen mischen.
    items.value = [...feed.items].sort((a, b) => (b.date ?? '').localeCompare(a.date ?? ''))
    stale.value = feed.stale
    error.value = null
    void loadImages()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

/** Bilder einzeln nachladen – der Kern legt sie in seinen Cache und gibt sie frei. */
async function loadImages() {
  const pending = items.value.filter((i) => i.imageUrl && !images.value[i.id]).slice(0, 24)
  for (const item of pending) {
    try {
      const path = await backend.newsImage(item.imageUrl!)
      if (path) images.value = { ...images.value, [item.id]: convertFileSrc(path) }
    } catch {
      // Ohne Bild bleibt die Kachel eben leer.
    }
  }
}

onMounted(() => load())

function open(item: NewsItem) {
  if (item.contentPath) {
    showPatchNotes(item)
    return
  }
  if (item.link) backend.openExternalUrl(item.link).catch((e) => toasts.error(e))
}

// --- Patchnotes im Launcher ------------------------------------------------------

const notes = ref<{ title: string; body: string | null; error: string | null } | null>(null)

async function showPatchNotes(item: NewsItem) {
  notes.value = { title: item.title, body: null, error: null }
  try {
    const body = await backend.patchNotesBody(item.contentPath!)
    if (notes.value) notes.value.body = body
  } catch (e) {
    if (notes.value) notes.value.error = errorMessage(e)
  }
}
</script>

<template>
  <section class="min-w-0">
    <header class="mb-2.5 flex items-center justify-between gap-3">
      <h2 class="section-title">Neuigkeiten</h2>
      <div class="flex items-center gap-1.5">
        <span v-if="stale" class="badge bg-base-800 text-base-400" title="Zuletzt gespeicherter Stand – gerade offline">Offline</span>
        <div class="flex items-center rounded-lg bg-base-850 p-0.5 text-xs">
          <button
            v-for="f in filters"
            :key="f.key"
            class="seg rounded-md px-2.5 py-1"
            :class="{ 'seg-on': filter === f.key }"
            @click="filter = f.key"
          >
            {{ f.label }}
          </button>
        </div>
        <button class="btn-icon size-8" title="Neu laden" aria-label="Neuigkeiten neu laden" :disabled="refreshing" @click="load(true)">
          <svg viewBox="0 0 24 24" class="size-4" :class="{ 'animate-spin': refreshing }" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5" />
          </svg>
        </button>
      </div>
    </header>

    <div v-if="loading" class="grid grid-cols-[repeat(auto-fill,minmax(16rem,1fr))] gap-3">
      <div v-for="i in 3" :key="i" class="skeleton h-40" />
    </div>

    <p v-else-if="error" role="alert" class="card px-4 py-6 text-center text-sm text-base-400">
      {{ error }}
      <button class="mt-2 block w-full text-xs text-redstone-300 hover:underline" @click="load(true)">Erneut versuchen</button>
    </p>

    <p v-else-if="!shown.length" class="card px-4 py-6 text-center text-sm text-base-400">
      Für diesen Bereich gibt es gerade nichts Neues.
    </p>

    <ul v-else class="grid grid-cols-[repeat(auto-fill,minmax(16rem,1fr))] items-start gap-3">
      <li v-for="item in shown" :key="item.id" class="card card-hover overflow-hidden">
        <button class="flex h-full w-full flex-col text-left" :title="item.contentPath ? 'Patchnotes lesen' : item.link ? 'Im Browser öffnen' : item.title" @click="open(item)">
          <img
            v-if="images[item.id] && item.source !== 'modrinth'"
            :src="images[item.id]"
            alt=""
            loading="lazy"
            class="aspect-[16/7] w-full object-cover"
          />
          <div class="flex min-w-0 flex-1 flex-col gap-1.5 p-3.5">
            <div class="flex items-center gap-2 text-[11px] text-base-400">
              <span class="badge" :class="item.source === 'launcher' ? 'bg-redstone-900 text-redstone-300' : 'bg-base-800 text-base-200'">
                {{ sourceLabels[item.source] }}
              </span>
              <span v-if="item.tag" class="truncate">{{ item.tag }}</span>
              <span v-if="item.date" class="ml-auto shrink-0">{{ formatRelative(item.date) }}</span>
            </div>
            <h3 class="flex items-center gap-2 text-sm font-semibold text-base-50">
              <img
                v-if="images[item.id] && item.source === 'modrinth'"
                :src="images[item.id]"
                alt=""
                loading="lazy"
                class="size-7 shrink-0 rounded [image-rendering:pixelated]"
              />
              <span class="line-clamp-2">{{ item.title }}</span>
            </h3>
            <p class="line-clamp-3 text-xs leading-relaxed text-base-400">{{ item.summary }}</p>
            <span v-if="item.contentPath || item.link" class="mt-auto pt-1.5 text-[11px] font-medium text-redstone-300">
              {{ item.contentPath ? 'Patchnotes lesen' : 'Öffnen' }}
            </span>
          </div>
        </button>
      </li>
    </ul>

    <BaseDialog v-if="notes" :title="notes.title" wide @close="notes = null">
      <div class="max-h-[60vh] overflow-y-auto pr-1">
        <p v-if="notes.error" role="alert" class="text-sm text-redstone-300">{{ notes.error }}</p>
        <div v-else-if="notes.body === null" class="space-y-2">
          <div class="skeleton h-4 w-3/4" />
          <div class="skeleton h-4 w-full" />
          <div class="skeleton h-4 w-5/6" />
        </div>
        <MarkdownView v-else :source="notes.body" />
      </div>
      <template #actions>
        <button class="btn btn-ghost" @click="notes = null">Schließen</button>
      </template>
    </BaseDialog>
  </section>
</template>
