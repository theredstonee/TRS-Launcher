<script setup lang="ts">
// Karte eines News-Beitrags (§30): Titelbild (sonst das Redstone-Banner mit Titel), Kurztext, Autor, Datum.
// Gleiche Maße wie BlogCard, damit Updates und News in einem Raster gemischt stehen.
const props = defineProps<{ post: NewsSummary, featured?: boolean }>()
const { lang, date } = useLang()
const { b, fill } = useBlogText()
const lp = useLocalePath()
const title = computed(() => newsText(props.post, 'title', lang.value))
const summary = computed(() => newsText(props.post, 'summary', lang.value))
const kicker = computed(() => `${b.value.kind.news} · ${date(props.post.publishedAt)}`)
</script>

<template>
  <NuxtLink :to="lp(`/blog/${post.slug}`)" class="card card-hover group flex flex-col overflow-hidden" data-kind="news">
    <div class="banner-box relative" :class="featured ? 'h-60 sm:h-72' : 'h-44'">
      <template v-if="post.cover">
        <img :src="featured ? post.cover.url : post.cover.thumbUrl" alt="" class="cover size-full object-cover" loading="lazy" decoding="async" />
        <div class="cover-shade absolute inset-0" />
        <div class="absolute inset-x-5 bottom-4">
          <p class="kicker">{{ kicker }}</p>
          <component :is="featured ? 'h2' : 'h3'" class="display mt-1 line-clamp-2 leading-tight cover-title drop-shadow" :class="featured ? 'text-4xl' : 'text-2xl'">{{ title }}</component>
        </div>
      </template>
      <UpdateBanner v-else :kicker="kicker" :title="title" accent="#ffb84d" :size="featured ? 'lg' : 'sm'" :tag="featured ? 'h2' : 'h3'" />
      <span class="badge news-badge absolute top-3 right-3">{{ b.kind.news }}</span>
    </div>
    <div class="flex flex-1 flex-col gap-3 p-5">
      <p v-if="summary" class="text-sm leading-relaxed text-base-400" :class="featured ? 'line-clamp-3' : 'line-clamp-2'">{{ summary }}</p>
      <div class="mt-auto flex items-center gap-2">
        <template v-if="post.author">
          <PlayerHead :uuid="post.author.uuid" :name="post.author.name" :skin="post.author.skin" :size="22" :fetch="false" />
          <span class="truncate text-xs text-base-400">{{ fill(b.by, { name: post.author.name }) }}</span>
        </template>
        <span class="ml-auto inline-flex shrink-0 items-center gap-1.5 text-sm font-medium text-redstone-300">
          {{ b.read }}
          <SiteIcon name="arrow" class="size-4 transition-transform group-hover:translate-x-0.5" />
        </span>
      </div>
    </div>
  </NuxtLink>
</template>

<style scoped>
/* Auf dem abgedunkelten Titelbild immer helle Schrift (auch im hellen Design). */
.cover-title {
  color: #f3f3f8;
}
.cover {
  transition: scale 0.3s ease;
}
.group:hover .cover,
.group:hover .banner-box :deep(.ub-motif) {
  scale: 1.04;
}
.cover-shade {
  background: linear-gradient(to top, rgb(12 11 14 / 0.92), rgb(12 11 14 / 0.3) 55%, transparent);
}
.kicker {
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-lamp-300);
}
.news-badge {
  background: color-mix(in srgb, var(--color-lamp-900) 85%, transparent);
  color: var(--color-lamp-300);
  border: 1px solid color-mix(in srgb, var(--color-lamp-400) 40%, transparent);
  backdrop-filter: blur(4px);
}
</style>
