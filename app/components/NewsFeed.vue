<script setup lang="ts">
import { convertFileSrc } from '@tauri-apps/api/core'
import type { NewsItem, NewsSource } from '~/types'

// Neuigkeiten für die Startseite im Magazin-Stil: ein großer Aufmacher mit
// Bild, daneben eine kompakte Liste. Quellen: Minecraft-Patchnotes, Mojang-News,
// gerade beliebte Modrinth-Projekte und neue Launcher-Versionen. Geholt, geprüft
// und zwischengespeichert wird alles im Kern; Bilder kommen aus dessen Cache.
const props = withDefaults(defineProps<{ listLimit?: number }>(), { listLimit: 5 })

const toasts = useToasts()
const items = ref<NewsItem[]>([])
const images = ref<Record<string, string>>({})
const loading = ref(true)
const error = ref<string | null>(null)
const stale = ref(false)
const refreshing = ref(false)

const filters: (NewsSource | 'all')[] = ['all', 'patchNotes', 'mojang', 'modrinth', 'launcher']
const filter = ref<NewsSource | 'all'>('all')

function sourceLabel(source: NewsSource): string {
  switch (source) {
    case 'patchNotes':
      return t('news.sourceMinecraftUpdate')
    case 'mojang':
      return 'Minecraft'
    case 'modrinth':
      return 'Modrinth'
    case 'launcher':
      return 'TRS Launcher'
  }
}

// In „Alles“ zählt vom Launcher nur die neueste Version – ältere Releases
// würden sonst die Liste füllen. Unter „Launcher“ stehen alle.
const visible = computed(() => {
  if (filter.value !== 'all') return items.value.filter((i) => i.source === filter.value)
  let launcherShown = false
  return items.value.filter((i) => {
    if (i.source !== 'launcher') return true
    if (launcherShown) return false
    launcherShown = true
    return true
  })
})

/** Aufmacher: die neueste Meldung mit großem Bild (Modrinth hat nur Icons). */
const featured = computed<NewsItem | null>(() => {
  const withImage = visible.value.find((i) => i.imageUrl && i.source !== 'modrinth' && i.source !== 'launcher')
  return withImage ?? visible.value[0] ?? null
})
const list = computed(() => visible.value.filter((i) => i !== featured.value).slice(0, props.listLimit))

/** Schlagwort in der eingestellten Sprache (vom Kern erzeugte Tags kommen mit Code). */
function tagOf(item: NewsItem): string | null {
  if (item.tagInfo?.code === 'news.tagDownloads' && item.downloads != null) {
    return tKey('errors.news.tagDownloads', { count: formatCount(item.downloads) })
  }
  return item.tagInfo ? userErrorText(item.tagInfo) : (item.tag ?? null)
}

function titleOf(item: NewsItem): string {
  // „TRS Launcher v0.3.0“ → „TRS Launcher v0.3.0 ist da“
  if (item.source === 'launcher' && /v?\d+\.\d+/.test(item.title)) return t('news.launcherReleased', { title: item.title })
  return item.title
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
  // Sichtbare zuerst, damit der Aufmacher nicht warten muss.
  const order = [...visible.value, ...items.value]
  const pending = order.filter((i, n) => i.imageUrl && !images.value[i.id] && order.indexOf(i) === n).slice(0, 24)
  for (const item of pending) {
    try {
      const path = await backend.newsImage(item.imageUrl!)
      if (path) images.value = { ...images.value, [item.id]: convertFileSrc(path) }
    } catch {
      // Ohne Bild bleibt die Kachel eben beim Platzhalter.
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

function actionLabel(item: NewsItem) {
  return item.contentPath ? t('news.readPatchNotes') : item.link ? t('news.openInBrowser') : item.title
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
  <section class="min-w-0" aria-labelledby="news-heading">
    <header class="mb-3 flex flex-wrap items-center justify-between gap-3">
      <h2 id="news-heading" class="heading">{{ t('news.title') }}</h2>
      <div class="flex items-center gap-1.5">
        <span v-if="stale" class="badge bg-base-800 text-base-400" :title="t('news.offlineHint')">{{ t('common.status.offline') }}</span>
        <div class="flex items-center rounded-lg border border-base-800 bg-base-900 p-0.5 text-xs" role="group" :aria-label="t('news.sourceLabel')">
          <button
            v-for="f in filters"
            :key="f"
            class="seg rounded-md px-2.5 py-1"
            :class="{ 'seg-on': filter === f }"
            :aria-pressed="filter === f"
            @click="filter = f"
          >
            {{ t(`news.filters.${f}`) }}
          </button>
        </div>
        <button class="btn-icon size-8" :title="t('news.reload')" :aria-label="t('news.reloadLabel')" :disabled="refreshing" @click="load(true)">
          <svg viewBox="0 0 24 24" class="size-4" :class="{ 'animate-spin': refreshing }" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5" />
          </svg>
        </button>
      </div>
    </header>

    <div v-if="loading" class="magazine">
      <div class="skeleton h-[22rem] rounded-xl" />
      <div class="flex flex-col gap-2">
        <div v-for="i in listLimit" :key="i" class="skeleton flex-1 rounded-lg" />
      </div>
    </div>

    <p v-else-if="error" role="alert" class="card px-4 py-6 text-center text-sm text-base-400">
      {{ error }}
      <button class="mt-2 block w-full text-xs text-redstone-300 hover:underline" @click="load(true)">{{ t('common.actions.retry') }}</button>
    </p>

    <p v-else-if="!featured" class="card px-4 py-6 text-center text-sm text-base-400">
      {{ t('news.empty') }}
    </p>

    <div v-else class="magazine" :class="{ 'magazine-solo': !list.length }">
      <!-- Aufmacher -->
      <article class="card card-hover group overflow-hidden">
        <button class="flex h-full w-full flex-col text-left" :title="actionLabel(featured)" @click="open(featured)">
          <div class="featured-image relative w-full shrink-0 overflow-hidden bg-base-850">
            <img
              v-if="images[featured.id] && featured.source !== 'modrinth'"
              :src="images[featured.id]"
              alt=""
              class="size-full object-cover transition-transform duration-500 group-hover:scale-[1.02]"
            />
            <div v-else class="featured-fallback size-full" />
            <span class="badge absolute top-3 left-3 bg-black/65 text-white backdrop-blur">{{ sourceLabel(featured.source) }}</span>
          </div>
          <div class="flex min-w-0 flex-1 flex-col gap-1.5 p-4">
            <p class="flex items-center gap-2 text-xs text-base-400">
              <span v-if="tagOf(featured)" class="truncate">{{ tagOf(featured) }}</span>
              <span v-if="featured.date" class="ml-auto shrink-0">{{ formatRelative(featured.date) }}</span>
            </p>
            <h3 class="line-clamp-2 text-lg leading-snug font-semibold text-base-50">{{ titleOf(featured) }}</h3>
            <p class="line-clamp-2 text-sm leading-relaxed text-base-400">{{ featured.summary }}</p>
            <span v-if="featured.contentPath || featured.link" class="mt-auto pt-1 text-xs font-medium text-redstone-300">
              {{ featured.contentPath ? t('news.readPatchNotes') : t('news.openInBrowser') }}
            </span>
          </div>
        </button>
      </article>

      <!-- Kompakte Liste daneben: gleich hohe Zeilen, füllt die Höhe des Aufmachers. -->
      <ul v-if="list.length" class="flex min-w-0 flex-col gap-2">
        <li v-for="item in list" :key="item.id" class="flex min-h-[4.25rem] flex-1">
          <button
            class="card card-hover flex w-full min-w-0 items-center gap-3 p-2.5 text-left"
            :class="{ 'launcher-item': item.source === 'launcher' }"
            :title="actionLabel(item)"
            @click="open(item)"
          >
            <span class="thumb shrink-0 overflow-hidden rounded-md bg-base-800">
              <img
                v-if="images[item.id]"
                :src="images[item.id]"
                alt=""
                loading="lazy"
                class="size-full"
                :class="item.source === 'modrinth' ? 'object-contain [image-rendering:pixelated]' : 'object-cover'"
              />
              <span v-else-if="item.source === 'launcher'" class="grid size-full place-items-center">
                <img src="/icon.png" alt="" class="size-7 [image-rendering:pixelated]" />
              </span>
              <span v-else class="thumb-fallback block size-full" />
            </span>
            <span class="min-w-0 flex-1">
              <span class="line-clamp-2 text-sm leading-snug font-medium text-base-50">{{ titleOf(item) }}</span>
              <span class="mt-0.5 flex items-center gap-2 text-[11px] text-base-400">
                <span :class="item.source === 'launcher' ? 'text-redstone-300' : ''">{{ sourceLabel(item.source) }}</span>
                <span v-if="item.date" class="ml-auto shrink-0">{{ formatRelative(item.date) }}</span>
              </span>
            </span>
          </button>
        </li>
      </ul>
    </div>

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
        <button class="btn btn-ghost" @click="notes = null">{{ t('common.actions.close') }}</button>
      </template>
    </BaseDialog>
  </section>
</template>

<style scoped>
.magazine {
  display: grid;
  gap: 0.75rem;
  grid-template-columns: minmax(0, 1fr);
}
/* Nebeneinander, sobald die Spalte breit genug ist (Container, nicht Fenster). */
@container (min-width: 44rem) {
  .magazine {
    grid-template-columns: minmax(0, 1.45fr) minmax(0, 1fr);
  }
  .magazine-solo {
    grid-template-columns: minmax(0, 1fr);
  }
}
.featured-image {
  height: clamp(11rem, 19vw, 17rem);
}
.thumb {
  width: 3.25rem;
  height: 3.25rem;
}
/* Platzhalter ohne Bild: Deepslate mit einem Staubfaden. */
.thumb-fallback,
.featured-fallback {
  background:
    linear-gradient(90deg, transparent 46%, color-mix(in srgb, var(--color-redstone-500) 55%, transparent) 46% 54%, transparent 54%) center / 100% 6px no-repeat,
    var(--deepslate) 0 0 / 24px 24px,
    var(--color-base-850);
}
.featured-fallback {
  background-size: 100% 8px, 48px 48px, auto;
}
.launcher-item {
  border-color: color-mix(in srgb, var(--color-redstone-500) 35%, var(--color-base-800));
}
</style>
