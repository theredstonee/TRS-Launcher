<script setup lang="ts">
import { blogPostingLd, breadcrumbLd } from '#shared/seo'

const route = useRoute()
const version = computed(() => String(route.params.version ?? ''))
const { lang, m, fill, date } = useLang()

if (!/^\d+\.\d+\.\d+(?:-[\w.]+)?$/.test(version.value)) {
  throw createError({ statusCode: 404, statusMessage: 'Not found', fatal: true })
}

const { data } = await useFetch<{ post: BlogPost }>(() => `/v1/site/blog/${version.value}`, { key: `post-${version.value}` })
if (!data.value?.post) throw createError({ statusCode: 404, statusMessage: 'Not found', fatal: true })
const post = computed(() => data.value!.post)

const title = computed(() => postTitle(post.value, lang.value, fill(m.value.common.version, { version: post.value.version })))
const kicker = computed(() => (post.value.date ? `v${post.value.version} · ${date(post.value.date)}` : `v${post.value.version}`))
/** Spanisch hat keinen eigenen Changelog-Text – dann Englisch. */
const html = computed(() => renderMarkdown(lang.value === 'de' ? post.value.markdown.de : post.value.markdown.en))
const shots = computed(() => postGallery(post.value, lang.value))

const lp = useLocalePath()
const siteUrl = useSiteUrl()

usePageSeo(() => {
  const p = post.value
  const path = `/blog/${p.version}`
  const headlines = postHeadlines(p, lang.value)
  const description = headlines.length ? fill(m.value.seo.post.description, { version: p.version, headlines: headlines.join(', ') }) : m.value.blog.lead
  const firstShot = shots.value[0]
  // Vorschaubild: erster Screenshot des Updates, sonst das Bild der Website. JSON-LD bekommt zusätzlich das Banner-Motiv.
  const image = firstShot ? { url: firstShot.src, alt: firstShot.caption || title.value } : null
  const images = [...shots.value.slice(0, 3).map((s) => s.src), ...(p.banner?.motif ? [p.banner.motif] : [])]
  return {
    path,
    title: p.title ? fill(m.value.seo.post.title, { title: title.value, version: p.version }) : fill(m.value.seo.post.untitled, { version: p.version }),
    description,
    type: 'article' as const,
    publishedTime: p.date,
    image,
    jsonLd: [
      blogPostingLd({ siteUrl, lang: lang.value, version: p.version, headline: title.value, description, datePublished: p.date, images }),
      breadcrumbLd(siteUrl, lang.value, [
        { name: m.value.nav.home, path: '/' },
        { name: m.value.blog.title, path: '/blog' },
        { name: title.value, path },
      ]),
    ],
  }
})
useHead({ link: [{ rel: 'preconnect', href: 'https://raw.githubusercontent.com' }] })
</script>

<template>
  <article>
    <header class="post-banner">
      <UpdateBanner :kicker="kicker" :title="title" :accent="post.banner?.accent" :motif="post.banner?.motif" size="lg" tag="h1">
        <NuxtLink :to="lp('/blog')" class="back-link">
          <SiteIcon name="back" class="size-4" />{{ m.blog.back }}
        </NuxtLink>
      </UpdateBanner>
    </header>

    <div class="mx-auto max-w-3xl px-4 pt-10 sm:px-6">
      <ShotGallery v-if="shots.length" :shots="shots" :accent="post.banner?.accent" class="mb-10" />
      <!-- eslint-disable-next-line vue/no-v-html -- eigener Changelog, ohne rohes HTML gerendert (utils/markdown.ts) -->
      <div class="prose-md post-text" v-html="html" />

      <div class="mt-14 flex flex-wrap items-center gap-3 border-t border-base-800 pt-8">
        <NuxtLink :to="lp('/download')" class="btn btn-primary"><SiteIcon name="download" class="size-4" />{{ m.nav.download }}</NuxtLink>
        <NuxtLink :to="lp('/blog')" class="btn btn-ghost">{{ m.blog.back }}</NuxtLink>
      </div>
    </div>
  </article>
</template>

<style scoped>
.post-banner {
  height: 24rem;
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
</style>
