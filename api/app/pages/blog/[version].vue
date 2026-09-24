<script setup lang="ts">
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
const blocks = computed(() => (lang.value === 'de' ? post.value.blocks.de : post.value.blocks.en))
const rendered = computed(() =>
  blocks.value.map((b) => (b.kind === 'text' ? { kind: 'text' as const, html: renderMarkdown(b.markdown) } : b)),
)

useHead({ title })
useSeoMeta({
  description: () => postHeadlines(post.value, lang.value).join(' · ') || m.value.blog.lead,
  ogType: 'article',
})
</script>

<template>
  <article>
    <header class="post-banner">
      <UpdateBanner :kicker="kicker" :title="title" :accent="post.banner?.accent" :motif="post.banner?.motif" size="lg" tag="h1">
        <NuxtLink to="/blog" class="back-link">
          <SiteIcon name="back" class="size-4" />{{ m.blog.back }}
        </NuxtLink>
      </UpdateBanner>
    </header>

    <div class="mx-auto max-w-3xl px-4 pt-10 sm:px-6">
      <template v-for="(b, i) in rendered" :key="i">
        <!-- eslint-disable-next-line vue/no-v-html -- eigener Changelog, ohne rohes HTML gerendert (utils/markdown.ts) -->
        <div v-if="b.kind === 'text'" class="prose-md post-text" v-html="b.html" />
        <figure v-else class="my-8">
          <img :src="b.src" :alt="b.caption" loading="lazy" class="w-full rounded-xl border border-base-800" />
          <figcaption v-if="b.caption" class="mt-2 text-center text-sm text-base-400">{{ b.caption }}</figcaption>
        </figure>
      </template>

      <div class="mt-14 flex flex-wrap items-center gap-3 border-t border-base-800 pt-8">
        <NuxtLink to="/download" class="btn btn-primary"><SiteIcon name="download" class="size-4" />{{ m.nav.download }}</NuxtLink>
        <NuxtLink to="/blog" class="btn btn-ghost">{{ m.blog.back }}</NuxtLink>
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
