<script setup lang="ts">
// Karte eines Update-Beitrags: eigene Redstone-Szene als Banner (je Version immer gleich), großer Update-Name.
const props = defineProps<{ post: BlogPostSummary, featured?: boolean }>()
const { lang, m, fill, date } = useLang()
const title = computed(() => postTitle(props.post, lang.value, fill(m.value.common.version, { version: props.post.version })))
const headlines = computed(() => postHeadlines(props.post, lang.value).slice(0, props.featured ? 5 : 3))
</script>

<template>
  <NuxtLink :to="`/blog/${post.version}`" class="card card-hover group flex flex-col overflow-hidden">
    <div class="relative overflow-hidden" :class="featured ? 'h-56 sm:h-64' : 'h-36'">
      <ClientOnly>
        <RedstoneScene fill :seed="versionSeed(post.version)" class="absolute inset-0" />
        <template #fallback><div class="deepslate absolute inset-0" /></template>
      </ClientOnly>
      <div class="banner-shade absolute inset-0" />
      <div class="absolute inset-x-5 bottom-4">
        <p class="text-xs font-semibold tracking-[0.18em] text-lamp-300 uppercase">
          v{{ post.version }}<span v-if="post.date" class="font-normal text-base-200"> · {{ date(post.date) }}</span>
        </p>
        <h3 class="display mt-1 leading-tight text-balance text-base-50 drop-shadow" :class="featured ? 'text-4xl' : 'text-2xl'">
          {{ title }}
        </h3>
      </div>
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
.banner-shade {
  background: linear-gradient(to top, rgb(12 11 14 / 0.92), rgb(12 11 14 / 0.35) 55%, rgb(12 11 14 / 0.05));
}
</style>
