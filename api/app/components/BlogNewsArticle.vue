<script setup lang="ts">
import { blogPostingLd, breadcrumbLd, type SeoLang } from '#shared/seo'

// News-Beitrag des Teams (§30): Titelbild (sonst Redstone-Banner), Autor, Datum, Markdown (nur gesäubert gerendert,
// Bilder nur aus dem eigenen Blog-Speicher). Fehlt die Seitensprache, steht der englische Text da (mit Hinweis).
const props = defineProps<{ slug: string }>()
const { lang, m, date } = useLang()
const { b, fill } = useBlogText()
const lp = useLocalePath()
const siteUrl = useSiteUrl()

const { data } = await useApiFetch<{ post: NewsPost }>(() => `/v1/site/news/${props.slug}`, { key: `news-${props.slug}` })
if (!data.value?.post) throw createError({ statusCode: 404, statusMessage: 'Not found', fatal: true })
const post = computed(() => data.value!.post)

const shown = computed(() => newsLang(post.value, lang.value))
const fallback = computed(() => shown.value !== lang.value)
const title = computed(() => newsText(post.value, 'title', lang.value))
const summary = computed(() => newsText(post.value, 'summary', lang.value))
const html = computed(() => renderMarkdown(newsText(post.value, 'markdown', lang.value), { blogImages: true }))
const kicker = computed(() => `${b.value.kind.news} · ${date(post.value.publishedAt)}`)

usePageSeo(() => {
  const p = post.value
  const path = `/blog/${p.slug}`
  const image = p.cover ? { url: p.cover.url, alt: title.value, width: p.cover.width, height: p.cover.height } : null
  return {
    path,
    title: fill(b.value.seo.title, { title: title.value }),
    description: summary.value || m.value.blog.lead,
    type: 'article' as const,
    publishedTime: p.publishedAt,
    image,
    // Nur übersetzte Sprachen bekommen eine eigene Adresse (sonst wäre es doppelter Inhalt).
    langs: p.langs as SeoLang[],
    jsonLd: [
      blogPostingLd({
        siteUrl,
        lang: shown.value,
        path,
        headline: title.value,
        description: summary.value || m.value.blog.lead,
        datePublished: p.publishedAt,
        dateModified: p.updatedAt,
        images: p.cover ? [p.cover.url] : [],
        authorName: p.author?.name ?? null,
      }),
      breadcrumbLd(siteUrl, shown.value, [
        { name: m.value.nav.home, path: '/' },
        { name: m.value.blog.title, path: '/blog' },
        { name: title.value, path },
      ]),
    ],
  }
})
</script>

<template>
  <article>
    <header class="post-banner relative" :class="{ 'has-cover': post.cover }">
      <template v-if="post.cover">
        <img :src="post.cover.url" alt="" class="absolute inset-0 size-full object-cover" decoding="async" fetchpriority="high" />
        <div class="cover-shade absolute inset-0" />
        <div class="cover-text">
          <p class="kicker">{{ kicker }}</p>
          <h1 class="display mt-2 text-4xl leading-tight cover-title drop-shadow sm:text-5xl">{{ title }}</h1>
        </div>
        <NuxtLink :to="lp('/blog')" class="back-link"><SiteIcon name="back" class="size-4" />{{ b.back }}</NuxtLink>
      </template>
      <UpdateBanner v-else :kicker="kicker" :title="title" accent="#ffb84d" size="lg" tag="h1">
        <NuxtLink :to="lp('/blog')" class="back-link"><SiteIcon name="back" class="size-4" />{{ b.back }}</NuxtLink>
      </UpdateBanner>
    </header>

    <div class="mx-auto max-w-3xl px-4 pt-8 sm:px-6">
      <div class="flex flex-wrap items-center gap-3 border-b border-base-800 pb-5 text-sm text-base-400">
        <template v-if="post.author">
          <PlayerHead :uuid="post.author.uuid" :name="post.author.name" :skin="post.author.skin" :size="28" :fetch="false" />
          <span class="text-base-200">{{ post.author.name }}</span>
        </template>
        <span v-else class="text-base-200">{{ b.team }}</span>
        <span aria-hidden="true">·</span>
        <time :datetime="post.publishedAt">{{ date(post.publishedAt) }}</time>
      </div>
      <p v-if="fallback" class="mt-5 flex items-center gap-2 text-xs text-base-400"><SiteIcon name="globe" class="size-3.5" />{{ b.onlyEnglish }}</p>
      <!-- eslint-disable-next-line vue/no-v-html -- eigener Beitrag, ohne rohes HTML gerendert (utils/markdown.ts) -->
      <div class="prose-md post-text mt-8" :lang="shown" v-html="html" />

      <div class="mt-14 flex flex-wrap items-center gap-3 border-t border-base-800 pt-8">
        <NuxtLink :to="lp('/download')" class="btn btn-primary"><SiteIcon name="download" class="size-4" />{{ m.nav.download }}</NuxtLink>
        <NuxtLink :to="lp('/blog')" class="btn btn-ghost">{{ b.back }}</NuxtLink>
      </div>
    </div>
  </article>
</template>

<style scoped>
/* Auf dem abgedunkelten Titelbild immer helle Schrift (auch im hellen Design). */
.cover-title {
  color: #f3f3f8;
}
.post-banner {
  height: 24rem;
  overflow: hidden;
}
.post-banner.has-cover {
  background: var(--color-base-950);
}
.cover-shade {
  background: linear-gradient(to top, rgb(12 11 14 / 0.95), rgb(12 11 14 / 0.35) 55%, rgb(12 11 14 / 0.15));
}
.cover-text {
  position: absolute;
  inset-inline: clamp(1.5rem, 6vw, 4rem);
  bottom: 2.25rem;
  max-width: 56rem;
}
.kicker {
  font-size: 0.8125rem;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--color-lamp-300);
}
.back-link {
  position: absolute;
  top: 1.75rem;
  left: clamp(1.5rem, 6vw, 4rem);
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.875rem;
  color: var(--color-base-200);
}
.back-link:hover {
  color: var(--color-base-50);
}
.post-text {
  font-size: 1.0625rem;
  line-height: 1.75;
}
.post-text :deep(img) {
  display: block;
  max-width: 100%;
  height: auto;
  margin-block: 1.5rem;
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-800);
}
</style>
