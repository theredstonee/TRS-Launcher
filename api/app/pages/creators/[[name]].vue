<script setup lang="ts">
// Öffentliche Creator-Seite (§35.11): Creator nach Stufe (Top, Partner, Starter) als runde Köpfe; ein Klick öffnet das
// Creator-Fenster und schreibt /creators/<name> in den Verlauf (kein Neuladen, Zurück schließt es wieder). Direkt
// aufgerufen rendert der Server das Fenster schon mit (eigener Titel, Beschreibung, canonical). Unbekannt → 404,
// ein Creator-Code statt Name → Weiterleitung auf den Namen.
import { breadcrumbLd } from '#shared/seo'

definePageMeta({
  // Eine Instanz für /creators und /creators/<name>: Öffnen/Schließen tauscht nur das Fenster.
  key: 'creators',
  scrollToTop: (to, from) => !(to.path.startsWith('/creators') && from.path.startsWith('/creators')),
})

const { m, lang, fill } = useLang()
const lp = useLocalePath()
const route = useRoute()
const router = useRouter()
const siteUrl = useSiteUrl()
const t = computed(() => m.value.creators)

const { data } = await useApiFetch<{ creators: SiteCreator[] }>('/v1/site/creators', { key: 'site-creators', default: () => ({ creators: [] }) })
const creators = computed(() => data.value?.creators ?? [])
const groups = computed(() =>
  CREATOR_TIERS.map((tier) => ({ tier, list: creators.value.filter((c) => c.tier === tier) })).filter((g) => g.list.length),
)

const param = computed(() => (typeof route.params.name === 'string' ? route.params.name : ''))
const selected = computed(() => {
  const p = param.value.toLowerCase()
  return p ? creators.value.find((c) => c.name.toLowerCase() === p) ?? null : null
})

/** Name nicht in der Liste: vielleicht ein Creator-Code (→ Weiterleitung auf den Namen), sonst 404. */
async function resolveUnknown(p: string) {
  try {
    const r = await apiFetch<{ creator: SiteCreator }>(`/v1/site/creators/${encodeURIComponent(p)}`)
    return navigateTo(lp(creatorPath(r.creator.name)), { replace: true, redirectCode: 301 })
  } catch {
    throw createError({ statusCode: 404, statusMessage: 'Creator not found', fatal: true })
  }
}
if (param.value && !selected.value) await resolveUnknown(param.value)
watch(param, (p) => {
  if (p && !selected.value) void resolveUnknown(p).catch((e) => showError(e))
})

// Öffnen per Klick legt einen Verlaufseintrag an; Schließen geht dann zurück (wie der Zurück-Knopf).
const pushed = ref(false)
watch(param, (p) => {
  if (!p) pushed.value = false
})
function open(c: SiteCreator, e?: MouseEvent) {
  // Neuer Tab / Fenster: dem Browser überlassen.
  if (e && (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey || e.button !== 0)) return
  e?.preventDefault()
  if (selected.value?.uuid === c.uuid) return
  const replace = !!selected.value
  pushed.value = pushed.value || !replace
  void (replace ? router.replace(lp(creatorPath(c.name))) : router.push(lp(creatorPath(c.name))))
}
function close() {
  if (pushed.value) router.back()
  else void router.push(lp('/creators'))
}

function snippet(text: string): string {
  const s = text.replace(/\s+/g, ' ').trim()
  return s.length > 155 ? `${s.slice(0, 152).trimEnd()} …` : s
}
usePageSeo(() => {
  const c = selected.value
  const trail = [
    { name: m.value.nav.home, path: '/' },
    { name: m.value.nav.creators, path: '/creators' },
    ...(c ? [{ name: c.name, path: creatorPath(c.name) }] : []),
  ]
  if (!c) return { path: '/creators', title: t.value.seo.title, description: t.value.seo.description, jsonLd: [breadcrumbLd(siteUrl, lang.value, trail)] }
  const detail = fill(t.value.detail.description, { name: c.name, tier: t.value.tier[c.tier] ?? c.tier })
  return {
    path: creatorPath(c.name),
    title: fill(t.value.detail.title, { name: c.name }),
    description: c.description ? snippet(c.description) : detail,
    type: 'website' as const,
    jsonLd: [
      breadcrumbLd(siteUrl, lang.value, trail),
      {
        '@type': 'ProfilePage',
        mainEntity: { '@type': 'Person', name: c.name, description: detail, ...(c.links.length ? { sameAs: c.links.map((l) => l.url) } : {}) },
      },
    ],
  }
})

const fansLabel = (c: SiteCreator) => (c.fanCount === 1 ? t.value.oneFan : fill(t.value.fans, { n: compactCount(c.fanCount, lang.value) }))
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="max-w-3xl">
      <p class="text-xs font-semibold tracking-[0.2em] text-redstone-400 uppercase">{{ t.eyebrow }}</p>
      <h1 class="display mt-2 text-5xl leading-tight text-base-50 sm:text-6xl">{{ t.title }}</h1>
      <p class="mt-4 text-lg text-base-400">{{ t.lead }}</p>
    </header>

    <p v-if="!groups.length" class="mt-12 rounded-lg border border-base-800 bg-base-900 px-4 py-5 text-base-400">{{ t.empty }}</p>

    <section v-for="g in groups" :key="g.tier" class="tier-section mt-14" :data-tier="g.tier" :aria-labelledby="`tier-${g.tier}`">
      <h2 :id="`tier-${g.tier}`" class="tier-title">
        <span>{{ t.tiers[g.tier] }}</span>
        <span class="text-xs font-normal tracking-normal text-base-400">{{ g.list.length }}</span>
      </h2>
      <ul class="creator-grid mt-7">
        <li v-for="c in g.list" :key="c.uuid">
          <a :href="lp(creatorPath(c.name))" class="creator-tile" :aria-label="fill(t.show, { name: c.name })" @click="open(c, $event)">
            <span class="relative">
              <CreatorAvatar :uuid="c.uuid" :name="c.name" :skin="c.skin" :tier="c.tier" :size="g.tier === 'top' ? 88 : 72" />
              <!-- Fan-Symbol als kleines Abzeichen am Kopf (so passt auch ein langer Name unter den Kopf) -->
              <span v-if="c.symbol" class="tile-symbol"><CreatorSymbol :url="c.symbol.url" :frames="c.symbol.frames" :frame-ms="c.symbol.frameMs" :px="16" /></span>
            </span>
            <span class="tile-name mt-3 font-bold text-base-50">{{ c.name }}</span>
            <span class="mt-0.5 text-xs text-base-400">{{ fansLabel(c) }}</span>
          </a>
        </li>
      </ul>
    </section>

    <aside class="become card mt-20 flex flex-col items-start gap-5 p-6 sm:flex-row sm:items-center sm:p-8">
      <div class="min-w-0 flex-1">
        <h2 class="heading text-2xl text-base-50">{{ t.becomeTitle }}</h2>
        <p class="mt-2 max-w-xl text-base-400">{{ t.becomeText }}</p>
      </div>
      <NuxtLink :to="lp('/team/creator')" class="btn btn-primary"><SiteIcon name="star" class="size-4" />{{ t.become }}</NuxtLink>
    </aside>

    <CreatorModal v-if="selected" :creator="selected" @close="close" />
  </div>
</template>

<style scoped>
.tier-title {
  display: flex;
  align-items: baseline;
  gap: 0.6rem;
  font-size: 0.85rem;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--color-base-200);
}
.tier-title::after {
  content: '';
  flex: 1;
  height: 1px;
  align-self: center;
  margin-left: 0.4rem;
  background: var(--color-base-800);
}
.tier-section[data-tier='top'] .tier-title {
  color: var(--color-lamp-300);
}
.creator-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(7.5rem, 1fr));
  gap: 2rem 1rem;
}
.tier-section[data-tier='top'] .creator-grid {
  grid-template-columns: repeat(auto-fill, minmax(9rem, 1fr));
}
.creator-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  border-radius: 0.9rem;
  padding: 0.5rem 0.25rem;
  outline-offset: 4px;
}
.creator-tile:hover :deep(.creator-avatar),
.creator-tile:focus-visible :deep(.creator-avatar) {
  transform: translateY(-3px);
  box-shadow: 0 10px 28px -10px var(--ring);
}
.tile-name {
  max-width: 100%;
  overflow-wrap: anywhere;
  line-height: 1.25;
}
.tile-symbol {
  position: absolute;
  right: -2px;
  bottom: -2px;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 999px;
  border: 2px solid var(--color-base-950);
  background: var(--color-base-850);
}
@media (max-width: 420px) {
  .tile-name {
    font-size: 0.85rem;
  }
}
.become {
  border-left: 3px solid var(--color-redstone-500);
}
@media (max-width: 420px) {
  .creator-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 1.5rem 0.5rem;
  }
  .tier-section[data-tier='top'] .creator-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
