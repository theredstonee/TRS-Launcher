<script setup lang="ts">
import { convertFileSrc } from '@tauri-apps/api/core'
import changelogText from '~~/CHANGELOG.md?raw'
import type { NewsItem, NewsSource } from '~/types'

// Neuigkeiten für die Startseite im Blog-Stil: jede Meldung als Karte mit Titelbild, die neueste
// groß als Aufmacher. Quellen: Minecraft-Patchnotes, Mojang-News, gerade beliebte Modrinth-Projekte
// und neue Launcher-Versionen. Geholt, geprüft und zwischengespeichert wird alles im Kern; Bilder
// kommen aus dessen Cache. Ohne Bild: Launcher-Versionen bekommen ihre Redstone-Szene als Banner,
// Modrinth-Projekte ihr Icon groß auf einem weichgezeichneten Hintergrund aus sich selbst.
const props = withDefaults(defineProps<{ listLimit?: number }>(), { listLimit: 6 })

const toasts = useToasts()
const items = ref<NewsItem[]>([])
const images = ref<Record<string, string>>({})
const loading = ref(true)
const error = ref<string | null>(null)
const stale = ref(false)
const refreshing = ref(false)

const filters: (NewsSource | 'all')[] = ['all', 'patchNotes', 'mojang', 'modrinth', 'launcher']
const filter = ref<NewsSource | 'all'>('all')

const changelog = parseChangelog(changelogText)
const german = computed(() => currentLocale.value === 'de')

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

/** Changelog-Beitrag zu einer Launcher-Version („TRS Launcher v0.4.3“ → Abschnitt 0.4.3). */
function launcherPost(item: NewsItem): ChangelogEntry | null {
  if (item.source !== 'launcher') return null
  const version = /v?(\d+\.\d+\.\d+(?:-[\w.]+)?)/.exec(item.title)?.[1]
  return version ? changelogFor(changelog, version) : null
}

/** Schlagwort in der eingestellten Sprache (vom Kern erzeugte Tags kommen mit Code). */
function tagOf(item: NewsItem): string | null {
  if (item.tagInfo?.code === 'news.tagDownloads' && item.downloads != null) {
    return tKey('errors.news.tagDownloads', { count: formatCount(item.downloads) })
  }
  return item.tagInfo ? userErrorText(item.tagInfo) : (item.tag ?? null)
}

function titleOf(item: NewsItem): string {
  // Launcher-Version mit Update-Namen: „Das Clip-Update“; sonst „TRS Launcher v0.3.0 ist da“.
  const post = launcherPost(item)
  if (post?.title) return german.value ? post.title.de : post.title.en
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
      // Ohne Bild bleibt die Karte eben beim Platzhalter.
    }
  }
}

onMounted(() => load())

function open(item: NewsItem) {
  const post = launcherPost(item)
  if (post) {
    reading.value = { entry: post, title: titleOf(item) }
    return
  }
  if (item.contentPath) {
    showPatchNotes(item)
    return
  }
  if (item.link) backend.openExternalUrl(item.link).catch((e) => toasts.error(e))
}

function actionLabel(item: NewsItem) {
  if (launcherPost(item)) return t('updateNews.read')
  return item.contentPath ? t('news.readPatchNotes') : item.link ? t('news.openInBrowser') : item.title
}

// --- Beiträge im Launcher ----------------------------------------------------------

const reading = ref<{ entry: ChangelogEntry; title: string } | null>(null)
const notes = ref<{ title: string; cover: string | null; body: string | null; error: string | null } | null>(null)

async function showPatchNotes(item: NewsItem) {
  notes.value = { title: item.title, cover: images.value[item.id] ?? null, body: null, error: null }
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

    <div v-if="loading" class="space-y-3">
      <div class="skeleton aspect-[21/9] rounded-xl" />
      <div class="blog-grid">
        <div v-for="i in 3" :key="i" class="skeleton aspect-[4/3] rounded-xl" />
      </div>
    </div>

    <p v-else-if="error" role="alert" class="card px-4 py-6 text-center text-sm text-base-400">
      {{ error }}
      <button class="mt-2 block w-full text-xs text-redstone-300 hover:underline" @click="load(true)">{{ t('common.actions.retry') }}</button>
    </p>

    <p v-else-if="!featured" class="card px-4 py-6 text-center text-sm text-base-400">
      {{ t('news.empty') }}
    </p>

    <div v-else class="space-y-3">
      <!-- Aufmacher: großes Titelbild, Titel darauf. -->
      <article class="card card-hover group overflow-hidden">
        <button class="block w-full text-left" :title="actionLabel(featured)" @click="open(featured)">
          <div class="relative aspect-[21/9] max-h-80 w-full overflow-hidden bg-base-850">
            <NewsCover :item="featured" :image="images[featured.id]" :post="launcherPost(featured)" large />
            <div class="cover-shade absolute inset-0" />
            <span class="badge absolute top-3 left-3 bg-black/65 text-white backdrop-blur">{{ sourceLabel(featured.source) }}</span>
            <div class="absolute inset-x-5 bottom-4">
              <p class="flex items-center gap-2 text-xs text-base-200">
                <span v-if="tagOf(featured)" class="truncate">{{ tagOf(featured) }}</span>
                <span v-if="featured.date" class="shrink-0">· {{ formatRelative(featured.date) }}</span>
              </p>
              <h3 class="display mt-1 line-clamp-2 text-3xl leading-tight text-base-50 drop-shadow">{{ titleOf(featured) }}</h3>
            </div>
          </div>
          <div class="flex items-center gap-4 px-5 py-3">
            <p class="line-clamp-2 flex-1 text-sm leading-relaxed text-base-400">{{ featured.summary }}</p>
            <span class="shrink-0 text-xs font-medium text-redstone-300">{{ actionLabel(featured) }}</span>
          </div>
        </button>
      </article>

      <!-- Weitere Beiträge als Blog-Karten mit Titelbild. -->
      <ul v-if="list.length" class="blog-grid">
        <li v-for="item in list" :key="item.id" class="min-w-0">
          <button
            class="card card-hover group flex h-full w-full flex-col overflow-hidden text-left"
            :class="{ 'launcher-item': item.source === 'launcher' }"
            :title="actionLabel(item)"
            @click="open(item)"
          >
            <div class="relative aspect-[16/9] w-full shrink-0 overflow-hidden bg-base-850">
              <NewsCover :item="item" :image="images[item.id]" :post="launcherPost(item)" />
              <span class="badge absolute top-2 left-2 bg-black/65 text-[11px] text-white backdrop-blur">{{ sourceLabel(item.source) }}</span>
            </div>
            <div class="flex min-w-0 flex-1 flex-col gap-1 p-3">
              <h3 class="line-clamp-2 text-sm leading-snug font-semibold text-base-50">{{ titleOf(item) }}</h3>
              <p class="mt-auto flex items-center gap-2 pt-1 text-[11px] text-base-400">
                <span v-if="tagOf(item)" class="truncate">{{ tagOf(item) }}</span>
                <span v-if="item.date" class="ml-auto shrink-0">{{ formatRelative(item.date) }}</span>
              </p>
            </div>
          </button>
        </li>
      </ul>
    </div>

    <UpdatePostDialog
      v-if="reading"
      :entry="reading.entry"
      :title="reading.title"
      @close="reading = null"
    />

    <BaseDialog v-if="notes" :title="notes.title" wide @close="notes = null">
      <img v-if="notes.cover" :src="notes.cover" alt="" class="-mx-5 -mt-4 mb-4 aspect-[21/9] w-[calc(100%+2.5rem)] max-w-none object-cover" />
      <div class="max-h-[55vh] overflow-y-auto pr-1">
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
.blog-grid {
  display: grid;
  gap: 0.75rem;
  grid-template-columns: repeat(auto-fill, minmax(13.5rem, 1fr));
}
.cover-shade {
  background: linear-gradient(to top, rgb(12 11 14 / 0.9), rgb(12 11 14 / 0.25) 55%, transparent);
}
.launcher-item {
  border-color: color-mix(in srgb, var(--color-redstone-500) 35%, var(--color-base-800));
}
</style>
