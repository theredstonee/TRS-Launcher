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
    <header class="post-banner relative isolate overflow-hidden">
      <ClientOnly>
        <RedstoneScene fill :seed="versionSeed(post.version)" class="absolute inset-0" />
        <template #fallback><div class="deepslate absolute inset-0" /></template>
      </ClientOnly>
      <div class="banner-shade absolute inset-0" />
      <div class="relative mx-auto flex h-full max-w-3xl flex-col justify-end px-4 pb-10 sm:px-6">
        <NuxtLink to="/blog" class="mb-auto mt-6 inline-flex w-fit items-center gap-1.5 text-sm text-base-200 hover:text-base-50">
          <SiteIcon name="back" class="size-4" />{{ m.blog.back }}
        </NuxtLink>
        <p class="text-xs font-semibold tracking-[0.2em] text-lamp-300 uppercase">
          v{{ post.version }}<span v-if="post.date" class="font-normal text-base-200"> · {{ date(post.date) }}</span>
        </p>
        <h1 class="display mt-2 text-5xl leading-[1.05] text-balance text-base-50 drop-shadow sm:text-6xl">{{ title }}</h1>
      </div>
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
  height: 22rem;
}
.banner-shade {
  background: linear-gradient(to top, var(--color-base-950), rgb(12 11 14 / 0.45) 55%, rgb(12 11 14 / 0.15));
}
.post-text {
  font-size: 1.0625rem;
  line-height: 1.75;
}
</style>
