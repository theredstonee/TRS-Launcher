<script setup lang="ts">
import { isSeoLang } from '#shared/seo'

const { lang, m } = useLang()
const route = useRoute()

// Seiten setzen ihren vollständigen Titel selbst (usePageSeo); ohne Titel gilt der der Website.
useHead({
  htmlAttrs: { lang },
  titleTemplate: (title) => title || m.value.meta.title,
  link: [{ rel: 'alternate', type: 'application/rss+xml', title: 'TRS Launcher', href: '/feed.xml' }],
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
