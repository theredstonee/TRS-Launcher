<script setup lang="ts">
// Der ganze Update-Beitrag im Launcher: Update-Banner (feste Vorlage + Motiv), Screenshot-Galerie der
// Neuerungen, die Versionshinweise und „Auf Website ansehen“ (Blog-Beitrag im Browser).
// Mehrere Versionen (z. B. „Was ist neu“ nach einem Sprung über mehrere Updates) → Umschalter im Fuß.
// Text läuft über MarkdownView (DOMPurify); Bilder nur aus dem mitgelieferten Ordner /news/.
const props = withDefaults(
  defineProps<{
    entries: ChangelogEntry[]
    /** Überschrift des Dialogs; ohne: der Update-Name der gezeigten Version. */
    heading?: string | null
    /** Mit diesem Screenshot beginnen (Klick auf ein Vorschaubild der Karte). */
    startShot?: number
  }>(),
  { heading: null, startShot: 0 },
)
const emit = defineEmits<{ close: [] }>()
const toasts = useToasts()

const selected = ref(0)
const shotIndex = ref(props.startShot)
const entry = computed(() => props.entries[selected.value] ?? props.entries[0]!)
const lang = computed(() => postLang(currentLocale.value))
const englishOnly = computed(() => !['de', 'en'].includes(currentLocale.value))

const title = computed(() => postTitle(entry.value, currentLocale.value) ?? t('updateNews.version', { version: entry.value.version ?? '' }))
const kicker = computed(() => updateKicker(entry.value))
const content = computed(() => {
  const { markdown, shots } = postContent(entry.value, lang.value)
  // Umbrüche aus der Datei (eingerückte Folgezeilen) zu einem Absatz zusammenziehen – MarkdownView bricht sonst dort um.
  return { markdown: markdown.replace(/\n {2,}(?=\S)/g, ' '), shots }
})

const body = ref<HTMLElement | null>(null)
watch(selected, () => {
  shotIndex.value = 0
  body.value?.closest('.overflow-y-auto')?.scrollTo({ top: 0 })
})

function openWebsite() {
  const version = entry.value.version
  if (!version) return
  backend.openExternalUrl(blogPostUrl(version, currentLocale.value)).catch((e) => toasts.error(e))
}
</script>

<template>
  <BaseDialog :title="heading ?? title" huge @close="emit('close')">
    <div ref="body">
      <div class="-mx-5 -mt-4 mb-5 h-44">
        <UpdateBanner :kicker="kicker" :title="title" :accent="entry.banner?.accent" :motif="entry.banner?.motif" tag="p" />
      </div>
      <UpdateShotGallery v-if="content.shots.length" v-model="shotIndex" :shots="content.shots" :accent="entry.banner?.accent" class="mb-6" />
      <p v-if="englishOnly" class="mb-3 text-xs text-base-400">{{ t('whatsNew.englishOnly') }}</p>
      <MarkdownView :source="content.markdown" class="post-notes" />
    </div>
    <template #actions>
      <div v-if="entries.length > 1" class="mr-auto flex flex-wrap items-center gap-1" role="group" :aria-label="t('updateNews.versions')">
        <button
          v-for="(e, i) in entries"
          :key="e.version ?? i"
          type="button"
          class="badge cursor-pointer px-2.5 py-1 font-mono"
          :class="i === selected ? 'bg-redstone-500/20 text-redstone-300 ring-1 ring-redstone-500/50' : 'bg-base-800 text-base-300 hover:text-base-50'"
          :aria-pressed="i === selected"
          @click="selected = i"
        >
          v{{ e.version }}
        </button>
      </div>
      <button v-if="entry.version" type="button" class="btn btn-ghost" @click="openWebsite">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" />
        </svg>
        {{ t('updateNews.openWebsite') }}
      </button>
      <button type="button" class="btn btn-primary" @click="emit('close')">{{ t('whatsNew.close') }}</button>
    </template>
  </BaseDialog>
</template>

<style scoped>
.post-notes {
  max-width: 52rem;
}
</style>
