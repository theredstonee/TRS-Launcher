<script setup lang="ts">
// Schaltungs-Bibliothek (§25): alle veröffentlichten Schaltungen mit isometrischer Vorschau, Filter nach Kategorie
// und Suche. Serverseitig gerendert (SEO), die Vorschau zeichnet erst im Browser.
import { breadcrumbLd, localizedUrl } from '#shared/seo'
import { CIRCUIT_CATEGORIES } from '#shared/circuits'
import { circuitText } from '~/utils/circuit-i18n'
import type { SiteCircuit } from '~/utils/circuits/types'

const { c, lang, fill } = useCircuitText()
const { m } = useLang()
const lp = useLocalePath()
const route = useRoute()
const router = useRouter()
const siteUrl = useSiteUrl()

const { data, error } = await useFetch<{ circuits: SiteCircuit[] }>('/v1/site/circuits', { key: 'site-circuits', default: () => ({ circuits: [] }) })
const all = computed(() => data.value?.circuits ?? [])

const category = ref<string>(typeof route.query.category === 'string' && (CIRCUIT_CATEGORIES as readonly string[]).includes(route.query.category) ? route.query.category : '')
const q = ref('')
watch(category, (v) => {
  void router.replace({ query: { ...route.query, category: v || undefined } })
})

const name = (s: SiteCircuit) => circuitText(s.circuit.texts, lang.value, 'name') || s.circuit.id
const desc = (s: SiteCircuit) => circuitText(s.circuit.texts, lang.value, 'desc')
const list = computed(() => {
  const needle = q.value.trim().toLowerCase()
  return all.value.filter((s) => {
    if (category.value && s.circuit.category !== category.value) return false
    if (!needle) return true
    return [name(s), desc(s), s.circuit.id.replace(/_/g, ' '), s.circuit.author?.name ?? '', c.value.categories[s.circuit.category] ?? '',
      ...s.materials.map((x) => c.value.blocks[x.key] ?? x.key)].some((t) => t.toLowerCase().includes(needle))
  })
})
const counts = computed(() => {
  const out: Record<string, number> = {}
  for (const s of all.value) out[s.circuit.category] = (out[s.circuit.category] ?? 0) + 1
  return out
})
const versionLabel = (s: SiteCircuit) =>
  s.maxVersion ? fill(c.value.common.range, { from: s.minVersion, to: s.maxVersion }) : fill(c.value.common.since, { v: s.minVersion })

usePageSeo(() => ({
  path: '/circuits',
  title: c.value.seo.list.title,
  description: c.value.seo.list.description,
  jsonLd: [
    breadcrumbLd(siteUrl, lang.value, [
      { name: m.value.nav.home, path: '/' },
      { name: c.value.list.title, path: '/circuits' },
    ]),
    {
      '@type': 'ItemList',
      name: c.value.list.title,
      numberOfItems: all.value.length,
      itemListElement: all.value.slice(0, 50).map((s, i) => ({
        '@type': 'ListItem',
        position: i + 1,
        name: name(s),
        url: localizedUrl(siteUrl, `/circuits/${s.circuit.id}`, lang.value),
      })),
    },
  ],
}))
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="flex flex-wrap items-end justify-between gap-6">
      <div class="max-w-2xl">
        <h1 class="display text-5xl leading-tight text-base-50">{{ c.list.title }}</h1>
        <p class="mt-3 text-lg text-base-400">{{ c.list.lead }}</p>
      </div>
      <div class="flex flex-wrap gap-2">
        <NuxtLink :to="lp('/circuits/mine')" class="btn btn-ghost"><SiteIcon name="inbox" class="size-4" />{{ c.list.mine }}</NuxtLink>
        <NuxtLink :to="lp('/circuits/submit')" class="btn btn-primary"><SiteIcon name="plus" class="size-4" />{{ c.list.submitCta }}</NuxtLink>
      </div>
    </header>

    <div class="mt-8 flex flex-wrap items-center gap-2">
      <div class="relative w-full sm:w-72">
        <SiteIcon name="search" class="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-base-400" />
        <input v-model="q" type="search" class="field pl-9" maxlength="64" :placeholder="c.list.search" :aria-label="c.list.search" />
      </div>
      <div class="flex flex-wrap gap-1.5" role="group" :aria-label="c.detail.category">
        <button type="button" class="chip cat" :aria-pressed="category === ''" @click="category = ''">{{ c.list.all }} <span class="text-base-400 tabular-nums">{{ all.length }}</span></button>
        <button
          v-for="cat in CIRCUIT_CATEGORIES"
          v-show="counts[cat]"
          :key="cat"
          type="button"
          class="chip cat"
          :aria-pressed="category === cat"
          @click="category = category === cat ? '' : cat"
        >
          {{ c.categories[cat] }} <span class="text-base-400 tabular-nums">{{ counts[cat] ?? 0 }}</span>
        </button>
      </div>
    </div>

    <p v-if="error" class="mt-10 text-lamp-300">{{ m.common.error }}</p>
    <p v-else-if="!all.length" class="mt-10 text-base-400">{{ c.list.empty }}</p>
    <p v-else-if="!list.length" class="mt-10 text-base-400">{{ c.list.noResults }}</p>
    <ul v-else class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <li v-for="s in list" :key="s.circuit.id">
        <NuxtLink :to="lp(`/circuits/${s.circuit.id}`)" class="card card-hover circuit-card block h-full overflow-hidden">
          <div class="stage">
            <ClientOnly>
              <CircuitIso :circuit="s.circuit" :height="190" :label="fill(c.common.preview, { name: name(s) })" />
              <template #fallback><div class="h-[190px]" /></template>
            </ClientOnly>
            <span class="badge absolute top-3 left-3 bg-base-900/80 text-base-200">{{ c.categories[s.circuit.category] }}</span>
          </div>
          <div class="p-4">
            <h2 class="heading truncate text-lg text-base-50">{{ name(s) }}</h2>
            <p class="mt-1 line-clamp-2 text-sm text-base-400">{{ desc(s) }}</p>
            <p class="mt-3 flex flex-wrap items-center gap-1.5 text-xs">
              <span class="diff" :data-d="s.circuit.difficulty">{{ c.difficulty[s.circuit.difficulty] }}</span>
              <span class="chip py-0.5 tabular-nums">{{ fill(c.common.blocks, { n: s.blockCount }) }}</span>
              <span class="chip py-0.5 tabular-nums">{{ versionLabel(s) }}</span>
              <span v-if="s.circuit.author" class="ml-auto truncate text-base-400">{{ fill(c.common.by, { name: s.circuit.author.name }) }}</span>
            </p>
          </div>
        </NuxtLink>
      </li>
    </ul>

    <section class="card mt-12 grid gap-4 p-6 md:grid-cols-[auto_1fr] md:items-center">
      <SiteIcon name="client" class="size-10 text-redstone-400" />
      <div>
        <h2 class="heading text-xl text-base-50">{{ c.list.clientTitle }}</h2>
        <p class="mt-1 text-sm text-base-400">{{ c.list.clientText }}</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.stage {
  position: relative;
  background-color: var(--color-base-950);
  background-image: var(--deepslate);
  background-size: 48px 48px;
  border-bottom: 1px solid var(--color-base-800);
}
.cat[aria-pressed='true'] {
  border-color: var(--color-redstone-500);
  color: var(--color-base-50);
  background: color-mix(in srgb, var(--color-redstone-500) 14%, transparent);
}
.diff {
  border-radius: 999px;
  padding: 0.1rem 0.55rem;
  font-weight: 600;
  background: color-mix(in srgb, var(--color-ok) 16%, transparent);
  color: var(--color-ok);
}
.diff[data-d='2'] {
  background: color-mix(in srgb, var(--color-lamp-400) 16%, transparent);
  color: var(--color-lamp-300);
}
.diff[data-d='3'] {
  background: color-mix(in srgb, var(--color-redstone-500) 18%, transparent);
  color: var(--color-redstone-300);
}
.circuit-card:hover .stage {
  background-color: var(--color-base-900);
}
</style>
