<script setup lang="ts">
import { breadcrumbLd, faqPageLd } from '#shared/seo'
import { landingPath, landingTexts, shotSize, shotUrl, type LandingId } from '#shared/landing'

// Eine Themen-Seite (/minecraft-launcher, /redstone-launcher, /modpacks, /fps-boost-pvp-client).
// Texte: shared/landing-{en,de,es}.ts; Bilder: Screenshots der Updates auf GitHub (CSP img-src erlaubt).
const props = defineProps<{ page: LandingId }>()

const { lang, m } = useLang()
const lp = useLocalePath()
const siteUrl = useSiteUrl()
const { data: releaseData } = await useRelease()
const { data: blogData } = await useBlog()

const L = computed(() => landingTexts[lang.value])
const p = computed(() => L.value.pages[props.page])
const path = landingPath(props.page)

/** Abschnitte mit Bild wechseln die Seite (Bild links/rechts). */
const sections = computed(() => {
  let withShot = 0
  return p.value.sections.map((s) => ({ ...s, flip: s.shot ? withShot++ % 2 === 1 : false }))
})
const ogShot = computed(() => p.value.sections.find((s) => s.shot)?.shot ?? null)

usePageSeo(() => ({
  path,
  title: p.value.seo.title,
  description: p.value.seo.description,
  image: ogShot.value
    ? { url: shotUrl(ogShot.value.file), width: shotSize(ogShot.value.file)[0], height: shotSize(ogShot.value.file)[1], alt: ogShot.value.alt }
    : null,
  jsonLd: [
    launcherLd(siteUrl, lang.value, m.value, releaseData.value?.release, blogData.value?.posts?.[0]),
    faqPageLd(siteUrl, lang.value, p.value.faq, path),
    breadcrumbLd(siteUrl, lang.value, [
      { name: m.value.nav.home, path: '/' },
      { name: p.value.name, path },
    ]),
  ],
}))
useHead({ link: [{ rel: 'preconnect', href: 'https://raw.githubusercontent.com' }] })
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <nav class="text-xs text-base-400" aria-label="Breadcrumb">
      <ol class="flex flex-wrap items-center gap-1.5">
        <li><NuxtLink :to="lp('/')" class="hover:text-base-50">{{ m.nav.home }}</NuxtLink></li>
        <li aria-hidden="true">/</li>
        <li aria-current="page" class="text-base-200">{{ p.name }}</li>
      </ol>
    </nav>

    <header class="mt-6 max-w-3xl">
      <p class="text-xs font-semibold tracking-[0.2em] text-lamp-300 uppercase">{{ p.kicker }}</p>
      <h1 class="display mt-3 text-4xl leading-tight text-base-50 sm:text-5xl">{{ p.title }}</h1>
      <p class="mt-4 text-lg leading-relaxed text-base-300">{{ p.lead }}</p>
      <div class="mt-7 flex flex-wrap gap-2">
        <NuxtLink :to="lp('/download')" class="btn btn-primary pixel-corners h-12 px-5 text-base" style="--notch: 3px">
          <SiteIcon name="download" class="size-5" />{{ L.common.download }}
        </NuxtLink>
        <NuxtLink :to="lp('/features')" class="btn btn-ghost h-12 px-5 text-base">{{ L.common.features }}</NuxtLink>
      </div>
      <p class="mt-3 text-xs text-base-400">{{ L.common.note }}</p>
    </header>

    <nav class="mt-10" :aria-label="L.common.onThisPage">
      <ul class="flex flex-wrap gap-2">
        <li v-for="s in p.sections" :key="s.id">
          <a :href="`#${s.id}`" class="toc-chip">{{ s.title }}</a>
        </li>
        <li><a href="#faq" class="toc-chip">{{ L.common.faqTitle }}</a></li>
      </ul>
    </nav>

    <div class="mt-14 flex flex-col gap-16">
      <section
        v-for="s in sections"
        :id="s.id"
        :key="s.id"
        class="landing-row scroll-mt-24"
        :class="{ 'has-img': s.shot, flip: s.flip }"
        :aria-labelledby="`${s.id}-title`"
      >
        <div class="max-w-2xl">
          <h2 :id="`${s.id}-title`" class="heading text-3xl">{{ s.title }}</h2>
          <p v-for="(t, i) in s.text" :key="i" class="mt-3 leading-relaxed text-base-300">{{ t }}</p>
          <ul v-if="s.points?.length" class="mt-5 flex flex-col gap-2.5">
            <li v-for="pt in s.points" :key="pt" class="flex gap-3 text-sm leading-relaxed text-base-400">
              <span class="dot" aria-hidden="true" />
              <span>{{ pt }}</span>
            </li>
          </ul>
          <NuxtLink v-if="s.link" :to="lp(s.link.to)" class="btn btn-ghost mt-6">
            {{ s.link.label }} <SiteIcon name="arrow" class="size-4" />
          </NuxtLink>
        </div>
        <figure v-if="s.shot" class="m-0">
          <div class="shot">
            <img
              :src="shotUrl(s.shot.file)"
              :alt="s.shot.alt"
              :width="shotSize(s.shot.file)[0]"
              :height="shotSize(s.shot.file)[1]"
              loading="lazy"
              decoding="async"
              class="size-full object-cover"
            />
          </div>
          <figcaption class="mt-2 text-center text-sm text-base-400">{{ s.shot.caption }}</figcaption>
        </figure>
      </section>
    </div>

    <section id="faq" class="mt-20 max-w-3xl scroll-mt-24" aria-labelledby="faq-title">
      <h2 id="faq-title" class="heading text-3xl">{{ L.common.faqTitle }}</h2>
      <div class="mt-6 divide-y divide-base-800 border-y border-base-800">
        <details v-for="(item, i) in p.faq" :key="item.q" class="faq group" :open="i === 0">
          <summary class="flex cursor-pointer list-none items-center gap-4 py-5 text-left">
            <h3 class="flex-1 text-base font-semibold text-base-50">{{ item.q }}</h3>
            <SiteIcon name="chevron" class="size-5 shrink-0 text-base-400 transition-transform group-open:rotate-180" />
          </summary>
          <p class="-mt-1 pb-5 leading-relaxed text-base-400">{{ item.a }}</p>
        </details>
      </div>
      <NuxtLink :to="lp('/faq')" class="btn btn-ghost mt-6">
        {{ L.common.allQuestions }} <SiteIcon name="arrow" class="size-4" />
      </NuxtLink>
    </section>

    <LandingLinks class="mt-20" :exclude="page" heading-id="related-title" />

    <section class="cta card mt-16 flex flex-wrap items-center justify-between gap-6 p-6 sm:p-8" aria-labelledby="cta-title">
      <div>
        <h2 id="cta-title" class="heading text-2xl">{{ p.cta.title }}</h2>
        <p class="mt-2 text-base-400">{{ p.cta.text }}</p>
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
.landing-row {
  display: grid;
  gap: 2rem;
  align-items: center;
}
@media (min-width: 900px) {
  .landing-row.has-img {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr);
  }
  .landing-row.flip figure {
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
  aspect-ratio: 16 / 10;
  background: var(--color-base-900);
}
.faq summary::-webkit-details-marker {
  display: none;
}
.faq[open] summary h3 {
  color: var(--color-redstone-300);
}
.cta {
  background:
    radial-gradient(80% 140% at 90% 50%, color-mix(in srgb, var(--color-redstone-900) 55%, transparent), transparent 70%),
    var(--color-base-900);
}
</style>
