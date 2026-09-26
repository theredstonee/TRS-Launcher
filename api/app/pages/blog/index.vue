<script setup lang="ts">
import { breadcrumbLd } from '#shared/seo'

const { lang, m } = useLang()
const siteUrl = useSiteUrl()
const { data, error } = await useBlog()
const posts = computed(() => data.value?.posts ?? [])
usePageSeo(() => {
  const shot = posts.value[0] ? postGallery(posts.value[0], lang.value)[0] : undefined
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

    <p v-if="error" class="mt-10 text-lamp-300">{{ m.common.error }}</p>
    <p v-else-if="!posts.length" class="mt-10 text-base-400">{{ m.blog.empty }}</p>
    <template v-else>
      <div class="mt-10">
        <BlogCard :post="posts[0]!" featured />
      </div>
      <div v-if="posts.length > 1" class="mt-5 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        <BlogCard v-for="p in posts.slice(1)" :key="p.version" :post="p" />
      </div>
    </template>
  </div>
</template>
