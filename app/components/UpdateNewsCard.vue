<script setup lang="ts">
import { getVersion } from '@tauri-apps/api/app'
import { isTauri } from '@tauri-apps/api/core'
import changelogText from '~~/CHANGELOG.md?raw'

// Update-News auf der Startseite: das neueste Update bis zur installierten Version als Karte –
// Banner aus unserer eigenen Redstone-Szene, großer Update-Name, die Schlagzeilen und „Beitrag lesen“.

const HIDDEN_KEY = 'trs.updateNews.hidden'

function readHidden(): string | null {
  try {
    return localStorage.getItem(HIDDEN_KEY)
  } catch {
    return null
  }
}

const entries = parseChangelog(changelogText)
const installed = ref<string | null>(null)
const hidden = ref(readHidden())
const reading = ref(false)

onMounted(async () => {
  if (!isTauri()) return
  try {
    installed.value = await getVersion()
  } catch {
    // ohne Version: einfach den neuesten Beitrag zeigen
  }
})

/** Neuester veröffentlichter Abschnitt, der nicht neuer als die installierte Version ist. */
const entry = computed(() => {
  const own = installed.value
  return entries.find((e) => e.version !== null && (!own || compareVersions(e.version, own) <= 0)) ?? null
})
const german = computed(() => currentLocale.value === 'de')
const title = computed(() => {
  const e = entry.value
  if (!e) return ''
  if (e.title) return german.value ? e.title.de : e.title.en
  return t('updateNews.version', { version: e.version ?? '' })
})
/** Die fett gesetzten Schlagzeilen der Punkte („**TRS-Umhänge laden wieder.**“) als kleine Chips. */
const headlines = computed(() => {
  const text = entry.value ? (german.value ? entry.value.de : entry.value.en) : ''
  return [...text.matchAll(/^- \*\*(.+?)\*\*/gm)].map((m) => m[1]!.replace(/[.!:]$/, '')).slice(0, 4)
})
/** Jede Version bekommt ihre eigene, aber immer gleiche Schaltung. */
const seed = computed(() => versionSeed(entry.value?.version ?? ''))

function hide() {
  const v = entry.value?.version
  if (!v) return
  hidden.value = v
  try {
    localStorage.setItem(HIDDEN_KEY, v)
  } catch {
    // egal – dann eben nur für diese Sitzung
  }
}
</script>

<template>
  <section v-if="entry && hidden !== entry.version" class="news card relative overflow-hidden" aria-labelledby="update-news-title">
    <div class="relative h-44">
      <RedstoneScene fill :seed="seed" class="absolute inset-0" />
      <div class="banner-shade absolute inset-0" />
      <div class="absolute inset-x-6 bottom-4">
        <p class="text-xs font-semibold tracking-[0.18em] text-lamp-300 uppercase">
          {{ t('updateNews.kicker', { version: entry.version ?? '' }) }}
          <span v-if="entry.date" class="font-normal text-base-300"> · {{ formatShortDate(`${entry.date}T12:00:00`) }}</span>
        </p>
        <h2 id="update-news-title" class="display mt-1 text-4xl leading-tight text-base-50 drop-shadow">{{ title }}</h2>
      </div>
      <button
        type="button"
        class="btn-icon absolute top-3 right-3 size-8 bg-base-950/60 backdrop-blur"
        :title="t('updateNews.hide')"
        :aria-label="t('updateNews.hide')"
        @click="hide"
      >
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M6 6l12 12M18 6 6 18" /></svg>
      </button>
    </div>
    <div class="flex flex-wrap items-center gap-2 p-4">
      <span v-for="h in headlines" :key="h" class="badge bg-base-800 text-base-100">{{ h }}</span>
      <button type="button" class="btn btn-primary ml-auto" @click="reading = true">{{ t('updateNews.read') }}</button>
    </div>
    <UpdatePostDialog v-if="reading" :entry="entry" :title="title" :seed="seed" @close="reading = false" />
  </section>
</template>

<style scoped>
.banner-shade {
  background: linear-gradient(to top, rgb(12 11 14 / 0.92), rgb(12 11 14 / 0.35) 55%, rgb(12 11 14 / 0.05));
}
</style>
