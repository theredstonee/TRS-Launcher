<script setup lang="ts">
import en from '~/content/launcher-privacy.en.md?raw'
import de from '~/content/launcher-privacy.de.md?raw'
import es from '~/content/launcher-privacy.es.md?raw'
import { AUTHORITY, WEBSITE_PRIVACY } from '~/content/website-privacy'

const LAUNCHER = { en, de, es }
const { lang } = useLang()
const text = computed(() => WEBSITE_PRIVACY[lang.value])
const html = computed(() => ({
  site: renderMarkdown(text.value.body, { breaks: true }),
  launcher: renderMarkdown(`${text.value.launcher}\n\n${LAUNCHER[lang.value]}\n\n${AUTHORITY[lang.value]}`),
}))
const { m } = useLang()
usePageSeo(() => ({
  path: '/privacy',
  title: m.value.seo.privacy.title,
  description: m.value.seo.privacy.description,
}))
</script>

<template>
  <div class="mx-auto max-w-3xl px-4 pt-14 sm:px-6">
    <header>
      <h1 class="display text-5xl leading-tight text-base-50">{{ text.title }}</h1>
      <p class="mt-2 text-sm text-base-600">{{ text.updated }}</p>
      <p class="mt-5 text-lg leading-relaxed text-base-200">{{ text.intro }}</p>
    </header>
    <!-- eslint-disable vue/no-v-html -- eigene Texte, ohne rohes HTML gerendert (utils/markdown.ts) -->
    <div class="prose-md legal mt-10" v-html="html.site" />
    <div class="prose-md legal mt-4" v-html="html.launcher" />
    <!-- eslint-enable vue/no-v-html -->
  </div>
</template>

<style scoped>
.legal {
  font-size: 0.975rem;
  line-height: 1.7;
}
.legal :deep(h2) {
  margin-top: 2.75rem;
  padding-top: 1.25rem;
  border-top: 1px solid var(--color-base-800);
  font-family: var(--font-display);
  font-size: 1.75rem;
  color: var(--color-base-50);
}
.legal :deep(table) {
  display: block;
  overflow-x: auto;
}
</style>
