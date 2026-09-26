<script setup lang="ts">
import { breadcrumbLd } from '#shared/seo'

// Alle Funktionen von Launcher und TRS Client auf einer Seite, ein Abschnitt je Thema (mit Anker).
const { lang, m } = useLang()
const lp = useLocalePath()
const siteUrl = useSiteUrl()
const { data: releaseData } = await useRelease()
const { data: blogData } = await useBlog()

/** Maße der Screenshots in public/shots (gegen Layout-Sprünge). */
const SIZES: Record<string, [number, number]> = { 'library.png': [1280, 800] }
const size = (img: string) => SIZES[img] ?? [854, 480]

/** Abschnitte mit Bild wechseln die Seite (Bild links/rechts). */
const sections = computed(() => {
  let withImg = 0
  return m.value.features.sections.map((s) => ({ ...s, flip: s.img ? withImg++ % 2 === 1 : false }))
})

usePageSeo(() => ({
  path: '/features',
  title: m.value.seo.features.title,
  description: m.value.seo.features.description,
  image: { url: '/shots/redstone-overlay.png', width: 854, height: 480, alt: m.value.features.sections.find((s) => s.id === 'redstone')?.alt },
  jsonLd: [
    launcherLd(siteUrl, lang.value, m.value, releaseData.value?.release, blogData.value?.posts?.[0]),
    breadcrumbLd(siteUrl, lang.value, [
      { name: m.value.nav.home, path: '/' },
      { name: m.value.nav.features, path: '/features' },
    ]),
  ],
}))
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="max-w-3xl">
      <p class="text-xs font-semibold tracking-[0.2em] text-lamp-300 uppercase">{{ m.features.kicker }}</p>
      <h1 class="display mt-3 text-5xl leading-tight text-base-50">{{ m.features.title }}</h1>
      <p class="mt-4 text-lg leading-relaxed text-base-400">{{ m.features.lead }}</p>
    </header>

    <nav class="mt-8" :aria-label="m.features.toc">
      <ul class="flex flex-wrap gap-2">
        <li v-for="s in m.features.sections" :key="s.id">
          <a :href="`#${s.id}`" class="toc-chip">{{ s.short }}</a>
        </li>
      </ul>
    </nav>

    <div class="mt-14 flex flex-col gap-16">
      <section
        v-for="s in sections"
        :id="s.id"
        :key="s.id"
        class="feature-row scroll-mt-24"
        :class="{ 'has-img': s.img, flip: s.flip }"
        :aria-labelledby="`${s.id}-title`"
      >
        <div class="max-w-2xl">
          <h2 :id="`${s.id}-title`" class="heading text-3xl">{{ s.title }}</h2>
          <p class="mt-3 leading-relaxed text-base-300">{{ s.text }}</p>
          <ul class="mt-5 flex flex-col gap-2.5">
            <li v-for="p in s.points" :key="p" class="flex gap-3 text-sm leading-relaxed text-base-400">
              <span class="dot" aria-hidden="true" />
              <span>{{ p }}</span>
            </li>
          </ul>
          <NuxtLink v-if="s.link" :to="lp(s.link.to)" class="btn btn-ghost mt-6">
            {{ s.link.label }} <SiteIcon name="arrow" class="size-4" />
          </NuxtLink>
        </div>
        <figure v-if="s.img" class="shot">
          <img
            :src="`/shots/${s.img}`"
            :alt="s.alt"
            :width="size(s.img)[0]"
            :height="size(s.img)[1]"
            loading="lazy"
            decoding="async"
            class="size-full object-cover"
          />
        </figure>
      </section>
    </div>

    <section class="cta card mt-20 flex flex-wrap items-center justify-between gap-6 p-6 sm:p-8" aria-labelledby="cta-title">
      <div>
        <h2 id="cta-title" class="heading text-2xl">{{ m.features.ctaTitle }}</h2>
        <p class="mt-2 text-base-400">{{ m.features.ctaText }}</p>
      </div>
      <NuxtLink :to="lp('/download')" class="btn btn-primary h-12 px-5 text-base">
        <SiteIcon name="download" class="size-5" />{{ m.nav.download }}
      </NuxtLink>
    </section>
  </div>
</template>

<style scoped>
.toc-chip {
  display: inline-block;
  border-radius: 999px;
  padding: 0.35rem 0.8rem;
  font-size: 0.8rem;
  color: var(--color-base-300);
  background: var(--color-base-900);
  box-shadow: inset 0 0 0 1px var(--color-base-800);
  transition: color 0.15s, box-shadow 0.15s;
}
.toc-chip:hover {
  color: var(--color-base-50);
  box-shadow: inset 0 0 0 1px var(--color-redstone-500);
}
.feature-row {
  display: grid;
  gap: 2rem;
  align-items: center;
}
@media (min-width: 900px) {
  .feature-row.has-img {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr);
  }
  .feature-row.flip .shot {
    order: -1;
  }
}
.dot {
  flex-shrink: 0;
  width: 0.45rem;
  height: 0.45rem;
  margin-top: 0.5rem;
  background: var(--color-redstone-500);
  box-shadow: 0 0 8px -1px var(--color-redstone-500);
}
.shot {
  overflow: hidden;
  border-radius: 0.75rem;
  border: 1px solid var(--color-base-800);
  box-shadow: 0 24px 48px -28px rgb(0 0 0 / 0.8);
  aspect-ratio: 16 / 9;
  background: var(--color-base-900);
}
.cta {
  background:
    radial-gradient(80% 140% at 90% 50%, color-mix(in srgb, var(--color-redstone-900) 55%, transparent), transparent 70%),
    var(--color-base-900);
}
</style>
