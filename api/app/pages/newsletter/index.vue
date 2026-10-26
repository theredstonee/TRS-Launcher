<script setup lang="ts">
import { breadcrumbLd } from '#shared/seo'

// Newsletter-Seite (§40.2): worum es geht, Anmeldung (Double-Opt-in), Hinweis auf den Launcher und den Datenschutz.
const { n } = useNewsletterText()
const { m, lang } = useLang()
const lp = useLocalePath()
const siteUrl = useSiteUrl()

usePageSeo(() => ({
  path: '/newsletter',
  title: n.value.page.seoTitle,
  description: n.value.page.seoDescription,
  jsonLd: [breadcrumbLd(siteUrl, lang.value, [{ name: m.value.nav.home, path: '/' }, { name: n.value.page.kicker, path: '/newsletter' }])],
}))
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 pt-14 sm:px-6">
    <header class="max-w-2xl">
      <p class="text-xs font-semibold tracking-[0.2em] text-redstone-400 uppercase">{{ n.page.kicker }}</p>
      <h1 class="display mt-2 text-5xl leading-tight text-base-50">{{ n.page.title }}</h1>
      <p class="mt-4 text-lg leading-relaxed text-base-300">{{ n.page.lead }}</p>
    </header>

    <section class="card nl-card mt-10 p-6 sm:p-8">
      <NewsletterForm variant="page" />
    </section>

    <ul class="mt-10 grid gap-4 sm:grid-cols-3" data-fx="stagger">
      <li v-for="p in n.page.points" :key="p.title" class="card p-5">
        <p class="font-semibold text-base-50">{{ p.title }}</p>
        <p class="mt-1.5 text-sm leading-relaxed text-base-400">{{ p.text }}</p>
      </li>
    </ul>

    <div class="mt-10 grid gap-3 text-sm text-base-400">
      <p>{{ n.page.often }}</p>
      <p>{{ n.page.launcher }}</p>
      <p>{{ n.page.privacyNote }} <NuxtLink :to="lp('/privacy')" class="underline hover:text-base-200">{{ m.footer.privacy }}</NuxtLink>.</p>
    </div>
  </div>
</template>

<style scoped>
.nl-card {
  background-image: radial-gradient(80% 140% at 100% 0%, color-mix(in srgb, var(--color-redstone-600) 18%, transparent), transparent 60%);
}
</style>
