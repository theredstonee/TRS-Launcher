<script setup lang="ts">
import { isSeoLang } from '#shared/seo'

const { lang, m } = useLang()
const route = useRoute()
await usePublicEvents()
/**
 * Halloween: Event global an (`GET /v1/events`) → Meldungen der Kürbis-Suche; Look (Klasse, Overlay) nur, wenn er nicht
 * vom Team gesperrt und nicht selbst abgeschaltet ist (§32.6, erst im Browser bekannt – kurzes Aufblitzen nach SSR ok).
 */
const hw = useHalloween()
const halloween = hw.look
const { load: loadAccount } = useAccount()
onMounted(() => {
  hw.loadPrefs()
  if (hw.on.value) void loadAccount()
})

// Seiten setzen ihren vollständigen Titel selbst (usePageSeo); ohne Titel gilt der der Website.
useHead({
  htmlAttrs: {
    lang,
    class: () => (halloween.value ? 'theme-halloween' : ''),
  },
  titleTemplate: (title) => title || m.value.meta.title,
  link: [{ rel: 'alternate', type: 'application/rss+xml', title: 'TRS Launcher', href: '/feed.xml' }],
  meta: () => (halloween.value ? [{ key: 'theme-color', name: 'theme-color', content: '#07060c' }] : []),
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
  <HalloweenOverlay v-if="halloween" />
  <HalloweenToast v-if="hw.on.value" />
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
</template>
