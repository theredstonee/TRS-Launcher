<script setup lang="ts">
import { isSeoLang } from '#shared/seo'

const { lang, m } = useLang()
const route = useRoute()
const { data: publicEvents } = await usePublicEvents()
/** Halloween-Look, sobald das Event global an ist (`GET /v1/events`). */
const halloween = computed(() => publicEvents.value?.events.some((e) => e.id === 'halloween' && e.active) ?? false)

// Seiten setzen ihren vollständigen Titel selbst (usePageSeo); ohne Titel gilt der der Website.
useHead({
  htmlAttrs: {
    lang,
    class: () => (halloween.value ? 'theme-halloween' : ''),
  },
  titleTemplate: (title) => title || m.value.meta.title,
  link: [{ rel: 'alternate', type: 'application/rss+xml', title: 'TRS Launcher', href: '/feed.xml' }],
  meta: () => (halloween.value ? [{ key: 'theme-color', name: 'theme-color', content: '#120a1c' }] : []),
})
useSeoMeta({
  description: () => m.value.meta.description,
  ogSiteName: 'TRS Launcher',
  ogType: 'website',
  ogImage: 'https://trs-launcher.theredstonee.de/og.png',
  twitterCard: 'summary_large_image',
})

// Sprache über die Adresse (?lang=) auch bei Navigation im Browser übernehmen.
watch(
  () => route.query.lang,
  (q) => {
    if (isSeoLang(q) && q !== lang.value) lang.value = q
  },
)
</script>

<template>
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
</template>
