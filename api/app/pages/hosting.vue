<script setup lang="ts">
import { breadcrumbLd } from '#shared/seo'
import { CRAFTINGHOSTING_URL } from '~/utils/hosting-i18n'

// „Hosting“: kostenloser Minecraft-Server je TRS-Konto beim Hosting-Partner CraftingHosting (§43). Unten die
// Verwaltung des eigenen Servers mit der Website-Sitzung (HostingManager).
const { m } = useLang()
const { h, lang } = useHostingText()
const lp = useLocalePath()
const siteUrl = useSiteUrl()

usePageSeo(() => ({
  path: '/hosting',
  title: h.value.seo.title,
  description: h.value.seo.description,
  jsonLd: [
    breadcrumbLd(siteUrl, lang.value, [
      { name: m.value.nav.home, path: '/' },
      { name: m.value.nav.hosting, path: '/hosting' },
    ]),
  ],
}))

const specs = computed(() => [
  { icon: 'chart', value: '4 GB', label: h.value.free.ram },
  { icon: 'bolt', value: '200 %', label: h.value.free.cpu },
  { icon: 'server', value: '10 GB', label: h.value.free.disk },
])
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="max-w-3xl">
      <p class="text-xs font-semibold tracking-[0.2em] text-lamp-300 uppercase">{{ h.kicker }}</p>
      <h1 class="display mt-3 text-5xl leading-tight text-base-50">{{ h.title }}</h1>
      <p class="mt-4 text-lg leading-relaxed text-base-400">{{ h.lead }}</p>
      <a :href="CRAFTINGHOSTING_URL" class="partner mt-5" rel="noopener" target="_blank">
        <span class="partner-star" aria-hidden="true">★</span>
        <span>{{ h.partner }}: <strong class="text-base-50">CraftingHosting</strong></span>
        <SiteIcon name="external" class="size-3.5 text-base-400" />
      </a>
      <div class="mt-7 flex flex-wrap gap-3">
        <NuxtLink :to="lp('/download')" class="btn btn-primary pixel-corners h-12 px-5 text-base" style="--notch: 3px">
          <SiteIcon name="download" class="size-5" />{{ h.download }}
        </NuxtLink>
        <a href="#my-server" class="btn btn-ghost h-12 px-5"><SiteIcon name="server" class="size-4" />{{ h.manageLink }}</a>
      </div>
    </header>

    <!-- Kostenlos -->
    <section class="mt-16" aria-labelledby="free-title">
      <h2 id="free-title" class="heading text-3xl">{{ h.free.title }}</h2>
      <ul class="mt-6 grid gap-4 sm:grid-cols-3" data-fx="stagger">
        <li v-for="s in specs" :key="s.label" class="card flex items-center gap-4 p-5">
          <span class="icon-tile shrink-0"><SiteIcon :name="s.icon" class="size-5" /></span>
          <p><span class="spec">{{ s.value }}</span> <span class="text-base-400">{{ s.label }}</span></p>
        </li>
      </ul>
      <p class="mt-4 text-base-300"><span class="text-base-400">{{ h.free.typesLabel }}:</span> {{ h.free.types }}</p>
    </section>

    <!-- So funktioniert es + Partner -->
    <div class="mt-16 grid gap-10 lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
      <section class="min-w-0" aria-labelledby="how-title">
        <h2 id="how-title" class="heading text-3xl">{{ h.how.title }}</h2>
        <ol class="mt-5 grid gap-4">
          <li v-for="(step, i) in h.how.steps" :key="step" class="flex gap-4">
            <span class="step-no" aria-hidden="true">{{ i + 1 }}</span>
            <p class="pt-1 text-sm leading-relaxed text-base-300">{{ step }}</p>
          </li>
        </ol>
      </section>
      <section class="partner-box card min-w-0 p-6" aria-labelledby="partner-title" data-fx="rise">
        <div class="flex items-center gap-3">
          <span class="icon-tile shrink-0"><SiteIcon name="server" class="size-5" /></span>
          <h2 id="partner-title" class="heading text-2xl">{{ h.partnerBox.title }}</h2>
        </div>
        <p class="mt-4 text-sm leading-relaxed text-base-300">{{ h.partnerBox.text }}</p>
        <a :href="CRAFTINGHOSTING_URL" class="btn btn-ghost mt-5" rel="noopener" target="_blank">
          <SiteIcon name="external" class="size-4" />{{ h.partnerBox.link }}
        </a>
      </section>
    </div>

    <!-- Funktionen -->
    <section class="mt-16" aria-labelledby="features-title">
      <h2 id="features-title" class="heading text-3xl">{{ h.features.title }}</h2>
      <ul class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3" data-fx="stagger">
        <li v-for="f in h.features.items" :key="f.title" class="card p-5">
          <span class="icon-tile"><SiteIcon :name="f.icon" class="size-5" /></span>
          <h3 class="mt-4 font-semibold text-base-50">{{ f.title }}</h3>
          <p class="mt-1.5 text-sm leading-relaxed text-base-400">{{ f.text }}</p>
        </li>
      </ul>
    </section>

    <HostingManager />

    <section class="cta card mt-10 flex flex-wrap items-center justify-between gap-6 p-6 sm:p-8" aria-labelledby="cta-title">
      <div>
        <h2 id="cta-title" class="heading text-2xl">{{ h.cta.title }}</h2>
        <p class="mt-2 text-base-400">{{ h.cta.text }}</p>
      </div>
      <NuxtLink :to="lp('/download')" class="btn btn-primary h-12 px-5"><SiteIcon name="download" class="size-4" />{{ h.download }}</NuxtLink>
    </section>
  </div>
</template>

<style scoped>
.icon-tile {
  display: grid;
  place-items: center;
  width: 2.5rem;
  height: 2.5rem;
  color: var(--color-base-50);
  background: var(--color-base-800);
  box-shadow: inset 0 -3px 0 var(--color-redstone-600);
}
.spec {
  font-family: var(--font-display);
  font-size: 1.75rem;
  line-height: 1;
  color: var(--color-base-50);
}
.partner {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.8rem;
  border-radius: 999px;
  font-size: 0.875rem;
  color: var(--color-base-200);
  background: var(--color-base-900);
  border: 1px solid color-mix(in srgb, var(--color-lamp-400) 40%, var(--color-base-800));
}
.partner:hover {
  border-color: var(--color-lamp-400);
}
.partner:focus-visible {
  outline: 2px solid var(--color-lamp-400);
  outline-offset: 2px;
}
.partner-star {
  color: var(--color-lamp-400);
}
.step-no {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 2rem;
  height: 2rem;
  font-weight: 700;
  color: var(--color-redstone-300);
  background: color-mix(in srgb, var(--color-redstone-900) 60%, var(--color-base-900));
  box-shadow: inset 0 0 0 1px var(--color-redstone-600);
}
.partner-box {
  border-color: color-mix(in srgb, var(--color-lamp-400) 35%, var(--color-base-800));
  background:
    radial-gradient(70% 120% at 100% 0%, color-mix(in srgb, var(--color-lamp-900) 35%, transparent), transparent 70%),
    var(--color-base-900);
}
.cta {
  background:
    radial-gradient(80% 140% at 90% 50%, color-mix(in srgb, var(--color-redstone-900) 55%, transparent), transparent 70%),
    var(--color-base-900);
}
</style>
