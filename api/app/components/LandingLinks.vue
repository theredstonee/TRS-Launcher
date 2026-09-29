<script setup lang="ts">
import { LANDING_IDS, landingPath, landingTexts, type LandingId } from '#shared/landing'

// Karten zu den Themen-Seiten (interne Links für Leser und Suchmaschinen) – auf Start, Funktionen,
// Download, FAQ und den Themen-Seiten selbst (dort ohne die eigene).
const props = withDefaults(defineProps<{ exclude?: LandingId | null, title?: string | null, headingId?: string, narrow?: boolean }>(), {
  exclude: null,
  narrow: false,
  title: null,
  headingId: 'topics-title',
})

const { lang } = useLang()
const lp = useLocalePath()
const texts = computed(() => landingTexts[lang.value])
const cards = computed(() =>
  LANDING_IDS.filter((id) => id !== props.exclude).map((id) => ({
    id,
    to: lp(landingPath(id)),
    name: texts.value.pages[id].name,
    teaser: texts.value.pages[id].teaser,
  })),
)
</script>

<template>
  <section :aria-labelledby="title === '' ? undefined : headingId">
    <h2 v-if="title !== ''" :id="headingId" class="heading text-2xl">{{ title ?? texts.common.related }}</h2>
    <ul class="mt-6 grid gap-4" :class="narrow ? 'sm:grid-cols-2' : cards.length > 3 ? 'sm:grid-cols-2 lg:grid-cols-4' : 'sm:grid-cols-3'">
      <li v-for="c in cards" :key="c.id">
        <NuxtLink :to="c.to" class="card card-hover flex h-full flex-col p-5">
          <span class="font-semibold text-base-50">{{ c.name }}</span>
          <span class="mt-2 flex-1 text-sm leading-relaxed text-base-400">{{ c.teaser }}</span>
          <span class="mt-4 inline-flex items-center gap-1.5 text-sm text-redstone-300">
            {{ texts.common.learnMore }} <SiteIcon name="arrow" class="size-4" />
          </span>
        </NuxtLink>
      </li>
    </ul>
  </section>
</template>

