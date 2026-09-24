<script setup lang="ts">
const { lang, m } = useLang()
const url = useRequestURL()

useHead({
  htmlAttrs: { lang },
  titleTemplate: (title) => (title ? `${title} · TRS Launcher` : m.value.meta.title),
  link: [{ rel: 'alternate', type: 'application/rss+xml', title: 'TRS Launcher', href: '/feed.xml' }],
})
useSeoMeta({
  description: () => m.value.meta.description,
  ogSiteName: 'TRS Launcher',
  ogType: 'website',
  ogImage: `${url.origin}/og.png`,
  twitterCard: 'summary_large_image',
})
</script>

<template>
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
</template>
