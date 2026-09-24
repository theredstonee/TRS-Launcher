<script setup lang="ts">
// Karte eines Update-Beitrags: Update-Banner (feste Vorlage + Motiv des Updates), darunter die Schlagzeilen.
const props = defineProps<{ post: BlogPostSummary, featured?: boolean }>()
const { lang, m, fill, date } = useLang()
const title = computed(() => postTitle(props.post, lang.value, fill(m.value.common.version, { version: props.post.version })))
const headlines = computed(() => postHeadlines(props.post, lang.value).slice(0, props.featured ? 5 : 3))
const kicker = computed(() => (props.post.date ? `v${props.post.version} · ${date(props.post.date)}` : `v${props.post.version}`))
</script>

<template>
  <NuxtLink :to="`/blog/${post.version}`" class="card card-hover group flex flex-col overflow-hidden">
    <div class="banner-box" :class="featured ? 'h-60 sm:h-72' : 'h-44'">
      <UpdateBanner
        :kicker="kicker"
        :title="title"
        :accent="post.banner?.accent"
        :motif="post.banner?.motif"
        :size="featured ? 'lg' : 'sm'"
        tag="h3"
      />
    </div>
    <div class="flex flex-1 flex-col gap-3 p-5">
      <ul v-if="headlines.length" class="flex flex-wrap gap-1.5">
        <li v-for="h in headlines" :key="h" class="chip">{{ h }}</li>
      </ul>
      <span class="mt-auto inline-flex items-center gap-1.5 text-sm font-medium text-redstone-300">
        {{ m.blog.read }}
        <SiteIcon name="arrow" class="size-4 transition-transform group-hover:translate-x-0.5" />
      </span>
    </div>
  </NuxtLink>
</template>

<style scoped>
.banner-box :deep(.ub-motif) {
  transition: scale 0.3s ease;
}
.group:hover .banner-box :deep(.ub-motif) {
  scale: 1.06;
}
</style>
