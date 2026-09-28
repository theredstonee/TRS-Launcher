<script setup lang="ts">
import type { NewsItem } from '~/types'

// News-Beitrag des TRS-Teams (Website-Blog, API §30) im Launcher: Titelbild (aus dem Bild-Cache des Kerns), Titel,
// Autor, Datum und Text. Der Markdown ist im Kern gesäubert (Bilder nur von der eigenen Website) und läuft hier
// zusätzlich über den DOMPurify-Weg (MarkdownView). „Auf Website ansehen“ öffnet den Beitrag im Browser.
const props = defineProps<{ item: NewsItem; cover: string | null }>()
const emit = defineEmits<{ close: [] }>()
const toasts = useToasts()

const lang = computed(() => {
  const l = currentLocale.value.split('-')[0]!
  return props.item.texts?.[l] ? l : 'en'
})
const texts = computed(() => props.item.texts?.[lang.value] ?? null)
const fallback = computed(() => lang.value !== currentLocale.value.split('-')[0])
const title = computed(() => texts.value?.title ?? props.item.title)

function openWebsite() {
  const link = props.item.link
  if (!link) return
  const l = currentLocale.value.split('-')[0]!
  const url = ['de', 'es'].includes(l) ? `${link}?lang=${l}` : link
  backend.openExternalUrl(url).catch((e) => toasts.error(e))
}
</script>

<template>
  <BaseDialog :title="title" wide @close="emit('close')">
    <div class="-mx-5 -mt-4 mb-4">
      <img v-if="cover" :src="cover" alt="" class="aspect-[21/9] w-full object-cover" />
      <div v-else class="news-fallback aspect-[21/9] w-full" />
    </div>
    <p class="mb-3 flex flex-wrap items-center gap-2 text-xs text-base-400">
      <span class="badge bg-base-800 text-base-200">{{ t('news.sourceTrs') }}</span>
      <span>{{ item.author ?? t('news.trsTeam') }}</span>
      <span v-if="item.date">· {{ formatRelative(item.date) }}</span>
    </p>
    <p v-if="fallback" class="mb-3 text-xs text-base-400">{{ t('news.onlyEnglish') }}</p>
    <div class="max-h-[50vh] overflow-y-auto pr-1" data-testid="trs-news-body">
      <MarkdownView :source="texts?.markdown ?? item.summary" />
    </div>
    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
      <button v-if="item.link" class="btn btn-primary" @click="openWebsite">{{ t('updateNews.openWebsite') }}</button>
    </template>
  </BaseDialog>
</template>

<style scoped>
.news-fallback {
  background:
    linear-gradient(90deg, transparent 46%, color-mix(in srgb, var(--color-lamp-400) 55%, transparent) 46% 54%, transparent 54%) center / 100% 8px no-repeat,
    var(--deepslate) 0 0 / 48px 48px,
    var(--color-base-850);
}
</style>
