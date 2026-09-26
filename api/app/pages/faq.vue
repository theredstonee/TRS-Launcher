<script setup lang="ts">
import { breadcrumbLd, faqPageLd } from '#shared/seo'

const { lang, m } = useLang()
const siteUrl = useSiteUrl()

// Strukturierte Daten für Suchmaschinen: FAQPage mit genau den sichtbaren Fragen und Antworten.
usePageSeo(() => ({
  path: '/faq',
  title: m.value.seo.faq.title,
  description: m.value.seo.faq.description,
  jsonLd: [
    faqPageLd(siteUrl, lang.value, m.value.faq.items),
    breadcrumbLd(siteUrl, lang.value, [
      { name: m.value.nav.home, path: '/' },
      { name: m.value.faq.title, path: '/faq' },
    ]),
  ],
}))
</script>

<template>
  <div class="mx-auto max-w-3xl px-4 pt-14 sm:px-6">
    <header>
      <h1 class="display text-5xl leading-tight text-base-50">{{ m.faq.title }}</h1>
      <p class="mt-3 text-lg text-base-400">{{ m.faq.lead }}</p>
    </header>

    <div class="mt-10 divide-y divide-base-800 border-y border-base-800">
      <details v-for="(item, i) in m.faq.items" :key="item.q" class="faq group" :open="i === 0">
        <summary class="flex cursor-pointer list-none items-center gap-4 py-5 text-left">
          <span class="flex-1 font-semibold text-base-50">{{ item.q }}</span>
          <SiteIcon name="chevron" class="size-5 shrink-0 text-base-400 transition-transform group-open:rotate-180" />
        </summary>
        <p class="-mt-1 pb-5 leading-relaxed text-base-400">{{ item.a }}</p>
      </details>
    </div>

    <div class="mt-10 flex flex-wrap gap-3">
      <a :href="DISCORD_URL" class="btn btn-primary" rel="noopener" target="_blank"><SiteIcon name="discord" class="size-4" />{{ m.faq.discord }}</a>
      <a :href="WIKI_URL" class="btn btn-ghost" rel="noopener" target="_blank"><SiteIcon name="book" class="size-4" />{{ m.faq.wiki }}</a>
    </div>
  </div>
</template>

<style scoped>
.faq summary::-webkit-details-marker {
  display: none;
}
.faq[open] summary span {
  color: var(--color-redstone-300);
}
</style>
