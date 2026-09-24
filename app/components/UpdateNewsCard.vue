<script setup lang="ts">
import { getVersion } from '@tauri-apps/api/app'
import { isTauri } from '@tauri-apps/api/core'
import changelogText from '~~/CHANGELOG.md?raw'

// Update-News auf der Startseite: das neueste Update bis zur installierten Version als Karte –
// Update-Banner (feste Vorlage + Motiv), großer Update-Name, die Schlagzeilen und „Beitrag lesen“.

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
  <section v-if="entry && hidden !== entry.version" class="news card relative overflow-hidden" :aria-label="title">
    <div class="relative h-44">
      <UpdateBanner :kicker="updateKicker(entry)" :title="title" :accent="entry.banner?.accent" :motif="entry.banner?.motif" tag="h2" />
      <button
        type="button"
        class="btn-icon absolute top-5 right-3 size-8 bg-base-950/60 backdrop-blur"
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
    <UpdatePostDialog v-if="reading" :entry="entry" :title="title" @close="reading = false" />
  </section>
</template>

