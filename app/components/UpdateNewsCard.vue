<script setup lang="ts">
import { getVersion } from '@tauri-apps/api/app'
import { isTauri } from '@tauri-apps/api/core'
import changelogText from '~~/CHANGELOG.md?raw'

// Update-News auf der Startseite: das neueste Update bis zur installierten Version als Karte –
// Update-Banner (feste Vorlage + Motiv), großer Update-Name, die Schlagzeilen, kleine Vorschaubilder der
// Screenshots und „Beitrag lesen“. Klick auf Banner, Vorschaubild oder Knopf öffnet den Beitrag im Dialog.

const HIDDEN_KEY = 'trs.updateNews.hidden'
/** So viele Vorschaubilder passen neben den Knopf; der Rest steht als „+n“ daneben. */
const THUMBS = 3

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
/** Offener Beitrag: mit welchem Screenshot er beginnt, sonst `null`. */
const reading = ref<number | null>(null)

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
const lang = computed(() => postLang(currentLocale.value))
const title = computed(() => {
  const e = entry.value
  if (!e) return ''
  return postTitle(e, currentLocale.value) ?? t('updateNews.version', { version: e.version ?? '' })
})
const content = computed(() => (entry.value ? postContent(entry.value, lang.value) : { markdown: '', shots: [] }))
/** Die fett gesetzten Schlagzeilen der Punkte („**TRS-Umhänge laden wieder.**“) als kleine Chips. */
const headlines = computed(() =>
  [...content.value.markdown.matchAll(/^- \*\*(.+?)\*\*/gm)].map((m) => m[1]!.replace(/[.!:]$/, '')).slice(0, 4),
)
const thumbs = computed(() => content.value.shots.slice(0, THUMBS))
const moreShots = computed(() => Math.max(0, content.value.shots.length - THUMBS))

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
      <button type="button" class="absolute inset-0 cursor-pointer" :aria-label="t('updateNews.read')" @click="reading = 0" />
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
      <div class="ml-auto flex items-center gap-2">
        <ul v-if="thumbs.length" class="flex items-center gap-1.5" :aria-label="t('updateNews.screenshots')">
          <li v-for="(shot, i) in thumbs" :key="shot.src">
            <button
              type="button"
              class="thumb"
              :title="shot.caption || t('updateNews.imageOf', { n: i + 1, total: content.shots.length })"
              :aria-label="shot.caption || t('updateNews.imageOf', { n: i + 1, total: content.shots.length })"
              @click="reading = i"
            >
              <img :src="shot.src" alt="" loading="lazy" decoding="async" class="size-full object-cover" />
            </button>
          </li>
          <li v-if="moreShots">
            <button type="button" class="badge bg-base-800 text-base-300 hover:text-base-50" :title="t('updateNews.more', { n: moreShots })" @click="reading = THUMBS">
              +{{ moreShots }}
            </button>
          </li>
        </ul>
        <button type="button" class="btn btn-primary" @click="reading = 0">{{ t('updateNews.read') }}</button>
      </div>
    </div>
    <UpdatePostDialog v-if="reading !== null" :entries="[entry]" :start-shot="reading" @close="reading = null" />
  </section>
</template>

<style scoped>
.thumb {
  display: block;
  width: 4rem;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: 0.375rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-950);
  transition: border-color 0.15s ease, transform 0.15s ease;
}
.thumb:hover,
.thumb:focus-visible {
  border-color: var(--color-redstone-400);
  transform: translateY(-1px);
}
</style>
