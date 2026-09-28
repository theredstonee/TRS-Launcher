<script setup lang="ts">
import { breadcrumbLd } from '#shared/seo'

// Blog: automatische Update-Beiträge (CHANGELOG.md) und News des Teams (§30) in einer Liste nach Datum, mit Filter
// „Alle / Updates / News“ (in der Adresse als ?type=updates|news, damit sich eine Auswahl teilen lässt).
const { lang, m } = useLang()
const { b } = useBlogText()
const siteUrl = useSiteUrl()
const route = useRoute()
const router = useRouter()
const { data, error } = await useBlog()

type Filter = 'all' | 'updates' | 'news'
type Item = { kind: 'update', key: string, at: number, post: BlogPostSummary } | { kind: 'news', key: string, at: number, post: NewsSummary }

const filter = computed<Filter>(() => (route.query.type === 'updates' || route.query.type === 'news' ? route.query.type : 'all'))
function setFilter(f: Filter) {
  const query = { ...route.query }
  if (f === 'all') delete query.type
  else query.type = f
  void router.replace({ query })
}

const updates = computed(() => data.value?.posts ?? [])
const items = computed<Item[]>(() => {
  const list: Item[] = [
    ...updates.value.map((p) => ({ kind: 'update' as const, key: `u-${p.version}`, at: p.date ? Date.parse(`${p.date}T12:00:00Z`) : 0, post: p })),
    ...(data.value?.news ?? []).map((p) => ({ kind: 'news' as const, key: `n-${p.slug}`, at: Date.parse(p.publishedAt) || 0, post: p })),
  ]
  return list.sort((x, y) => y.at - x.at)
})
const visible = computed(() => items.value.filter((i) => filter.value === 'all' || (filter.value === 'updates' ? i.kind === 'update' : i.kind === 'news')))

usePageSeo(() => {
  const shot = updates.value[0] ? postGallery(updates.value[0], lang.value)[0] : undefined
  return {
    path: '/blog',
    title: m.value.seo.blog.title,
    description: m.value.seo.blog.description,
    image: shot ? { url: shot.src, alt: shot.caption || m.value.seo.blog.title } : null,
    jsonLd: [
      breadcrumbLd(siteUrl, lang.value, [
        { name: m.value.nav.home, path: '/' },
        { name: m.value.blog.title, path: '/blog' },
      ]),
    ],
  }
})
useHead({ link: [{ rel: 'preconnect', href: 'https://raw.githubusercontent.com' }] })
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="flex flex-wrap items-end justify-between gap-4">
      <div class="max-w-2xl">
        <h1 class="display text-5xl leading-tight text-base-50">{{ m.blog.title }}</h1>
        <p class="mt-3 text-lg text-base-400">{{ m.blog.lead }}</p>
      </div>
      <a href="/feed.xml" class="btn btn-ghost">{{ m.blog.rss }}</a>
    </header>

    <div class="seg mt-8" role="group" :aria-label="b.filter.label" data-testid="blog-filter">
      <button
        v-for="f in (['all', 'updates', 'news'] as const)"
        :key="f"
        type="button"
        class="seg-btn"
        :class="{ on: filter === f }"
        :aria-pressed="filter === f"
        @click="setFilter(f)"
      >
        {{ b.filter[f] }}
      </button>
    </div>

    <p v-if="error" class="mt-10 text-lamp-300">{{ m.common.error }}</p>
    <p v-else-if="!visible.length" class="mt-10 text-base-400">{{ filter === 'news' ? b.emptyNews : m.blog.empty }}</p>
    <template v-else>
      <div class="mt-6">
        <BlogCard v-if="visible[0]!.kind === 'update'" :post="visible[0]!.post as BlogPostSummary" featured />
        <NewsCard v-else :post="visible[0]!.post as NewsSummary" featured />
      </div>
      <div v-if="visible.length > 1" class="mt-5 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        <template v-for="i in visible.slice(1)" :key="i.key">
          <BlogCard v-if="i.kind === 'update'" :post="i.post" />
          <NewsCard v-else :post="i.post" />
        </template>
      </div>
    </template>
  </div>
</template>

<style scoped>
.seg {
  display: inline-flex;
  gap: 0.25rem;
  padding: 0.25rem;
  border-radius: 0.625rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
}
.seg-btn {
  padding: 0.375rem 0.875rem;
  border-radius: 0.4rem;
  font-size: 0.875rem;
  color: var(--color-base-400);
  transition: background-color 0.15s, color 0.15s;
}
.seg-btn:hover {
  color: var(--color-base-50);
}
.seg-btn.on {
  background: var(--color-base-700);
  color: var(--color-base-50);
  box-shadow: inset 0 -2px 0 color-mix(in srgb, var(--color-redstone-500) 60%, transparent);
}
</style>
