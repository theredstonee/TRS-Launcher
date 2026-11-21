<script setup lang="ts">
// Öffentliche Partner-Seite (§44.3): aktive Partner nach Kategorie als runde Logos; ein Klick öffnet das Partner-Fenster
// und schreibt /partners/<slug> in den Verlauf (wie /creators). Direkt aufgerufen rendert der Server das Fenster mit.
// Unten das gemeinsame Code-Feld (§44.2).
import { breadcrumbLd } from '#shared/seo'

definePageMeta({
  key: 'partners',
  scrollToTop: (to, from) => !(to.path.startsWith('/partners') && from.path.startsWith('/partners')),
})

const { m, lang, fill } = useLang()
const { p } = usePartnerText()
const lp = useLocalePath()
const route = useRoute()
const router = useRouter()
const siteUrl = useSiteUrl()
const t = computed(() => p.value.pub)

const { data } = await useApiFetch<{ partners: PartnerCard[] }>('/v1/partners', { key: 'site-partners', default: () => ({ partners: [] }) })
const partners = computed(() => data.value?.partners ?? [])
const groups = computed(() =>
  PARTNER_CATEGORIES.map((c) => ({ category: c, list: partners.value.filter((x) => x.category === c) })).filter((g) => g.list.length),
)

const param = computed(() => (typeof route.params.slug === 'string' ? route.params.slug.toLowerCase() : ''))
const selected = computed(() => (param.value ? partners.value.find((x) => x.slug === param.value) ?? null : null))
if (param.value && !selected.value) throw createError({ statusCode: 404, statusMessage: 'Partner not found', fatal: true })
watch(param, (s) => {
  if (s && !selected.value) showError(createError({ statusCode: 404, statusMessage: 'Partner not found' }))
})

const pushed = ref(false)
watch(param, (s) => {
  if (!s) pushed.value = false
})
function open(x: PartnerCard, e?: MouseEvent) {
  if (e && (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey || e.button !== 0)) return
  e?.preventDefault()
  if (selected.value?.slug === x.slug) return
  const replace = !!selected.value
  pushed.value = pushed.value || !replace
  void (replace ? router.replace(lp(partnerPath(x.slug))) : router.push(lp(partnerPath(x.slug))))
}
function close() {
  if (pushed.value) router.back()
  else void router.push(lp('/partners'))
}

const redeemCode = ref('')
function redeemFrom(code: string) {
  redeemCode.value = code
  close()
  void nextTick(() => document.getElementById('redeem')?.scrollIntoView({ behavior: 'smooth', block: 'center' }))
}

function snippet(text: string): string {
  const s = text.replace(/\s+/g, ' ').trim()
  return s.length > 155 ? `${s.slice(0, 152).trimEnd()} …` : s
}
usePageSeo(() => {
  const x = selected.value
  const trail = [
    { name: m.value.nav.home, path: '/' },
    { name: p.value.nav, path: '/partners' },
    ...(x ? [{ name: x.name, path: partnerPath(x.slug) }] : []),
  ]
  if (!x) return { path: '/partners', title: t.value.seo.title, description: t.value.seo.description, jsonLd: [breadcrumbLd(siteUrl, lang.value, trail)] }
  const text = partnerText(x.text, lang.value)
  return {
    path: partnerPath(x.slug),
    title: fill(t.value.detailTitle, { name: x.name }),
    description: text ? snippet(text) : fill(t.value.detailDescription, { name: x.name, category: p.value.category[x.category] ?? x.category }),
    type: 'website' as const,
    jsonLd: [
      breadcrumbLd(siteUrl, lang.value, trail),
      { '@type': 'Organization', name: x.name, ...(x.links.length ? { sameAs: x.links.map((l) => l.url) } : {}) },
    ],
  }
})

const supportersLabel = (x: PartnerCard) => (x.supporters === 1 ? t.value.oneSupporter : fill(t.value.supporters, { n: compactCount(x.supporters, lang.value) }))
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="max-w-3xl">
      <p class="text-xs font-semibold tracking-[0.2em] text-redstone-400 uppercase">{{ t.eyebrow }}</p>
      <h1 class="display mt-2 text-5xl leading-tight text-base-50 sm:text-6xl">{{ t.title }}</h1>
      <p class="mt-4 text-lg text-base-400">{{ t.lead }}</p>
    </header>

    <p v-if="!groups.length" class="mt-12 rounded-lg border border-base-800 bg-base-900 px-4 py-5 text-base-400">{{ t.empty }}</p>

    <section v-for="g in groups" :key="g.category" class="cat-section mt-14" :aria-labelledby="`cat-${g.category}`">
      <h2 :id="`cat-${g.category}`" class="cat-title">
        <span>{{ p.categories[g.category] }}</span>
        <span class="text-xs font-normal tracking-normal text-base-400">{{ g.list.length }}</span>
      </h2>
      <ul class="partner-grid mt-7">
        <li v-for="x in g.list" :key="x.slug">
          <a :href="lp(partnerPath(x.slug))" class="partner-tile" :aria-label="fill(t.show, { name: x.name })" @click="open(x, $event)">
            <PartnerLogo :logo="x.logo" :name="x.name" :size="104" />
            <span class="tile-name mt-3 font-bold text-base-50">{{ x.name }}</span>
            <span class="mt-0.5 text-xs text-base-400">{{ supportersLabel(x) }}</span>
          </a>
        </li>
      </ul>
    </section>

    <div id="redeem" class="mt-20 grid gap-5 lg:grid-cols-[1.2fr_1fr]">
      <PartnerRedeem :initial="redeemCode" />
      <aside class="become card flex flex-col items-start gap-4 p-6">
        <h2 class="heading text-2xl text-base-50">{{ t.becomeTitle }}</h2>
        <p class="text-base-400">{{ t.becomeText }}</p>
        <a href="mailto:partner@trs-launcher.de" class="btn btn-ghost"><SiteIcon name="mail" class="size-4" />{{ t.becomeButton }}</a>
      </aside>
    </div>

    <PartnerModal v-if="selected" :partner="selected" @close="close" @redeem="redeemFrom" />
  </div>
</template>

<style scoped>
.cat-title {
  display: flex;
  align-items: baseline;
  gap: 0.6rem;
  font-size: 0.85rem;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--color-base-200);
}
.cat-title::after {
  content: '';
  flex: 1;
  height: 1px;
  align-self: center;
  margin-left: 0.4rem;
  background: var(--color-base-800);
}
.partner-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr));
  gap: 2rem 1rem;
}
.partner-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  border-radius: 0.9rem;
  padding: 0.5rem 0.25rem;
  outline-offset: 4px;
}
.partner-tile:hover :deep(.partner-logo),
.partner-tile:focus-visible :deep(.partner-logo) {
  transform: translateY(-3px);
  border-color: var(--color-redstone-500);
  box-shadow: 0 10px 28px -10px var(--color-redstone-500);
}
.tile-name {
  max-width: 100%;
  overflow-wrap: anywhere;
  line-height: 1.25;
}
.become {
  border-left: 3px solid var(--color-redstone-500);
}
@media (max-width: 420px) {
  .partner-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 1.5rem 0.5rem;
  }
}
</style>
