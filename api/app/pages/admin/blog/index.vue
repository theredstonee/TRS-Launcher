<script setup lang="ts">
// Blog (§30.3): alle News-Beiträge – Entwürfe zuerst, dann geplante und veröffentlichte nach Datum. Neue Beiträge und
// Änderungen im Editor (/admin/blog/<id>). Die Update-Beiträge kommen automatisch aus CHANGELOG.md und fehlen hier.
const { a, when } = useAdminText()
const { b, fill, lang } = useBlogText()
const { api, can } = useAdmin()

const posts = ref<AdminBlogListItem[]>([])
const loading = ref(true)
const error = ref('')
const canWrite = computed(() => can('blog.write'))

onMounted(async () => {
  try {
    posts.value = (await api<{ posts: AdminBlogListItem[] }>('/v1/admin/blog')).posts
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
})

function titleOf(p: AdminBlogListItem): string {
  return p.titles[lang.value as BlogLang] || p.titles.en || p.titles.de || p.titles.es || b.value.adm.untitled
}
const tone = (s: BlogState) => (s === 'published' ? 'tone-ok' : s === 'scheduled' ? 'tone-info' : 'tone-muted')
function dateLine(p: AdminBlogListItem): string {
  if (p.state === 'scheduled' && p.publishAt) return fill(b.value.adm.scheduledFor, { date: when(p.publishAt) })
  if (p.state === 'published' && p.publishAt) return fill(b.value.adm.publishedAt, { date: when(p.publishAt) })
  return fill(b.value.adm.updated, { date: when(p.updatedAt), name: p.updatedBy.name ?? '—' })
}
</script>

<template>
  <div class="adm-page">
    <header class="flex flex-wrap items-end gap-3">
      <div class="min-w-0 flex-1">
        <h1 class="adm-title">{{ b.adm.title }}</h1>
        <p class="adm-lead">{{ b.adm.lead }}</p>
      </div>
      <NuxtLink v-if="canWrite" to="/admin/blog/new" class="btn btn-primary" data-testid="blog-new"><SiteIcon name="plus" class="size-4" />{{ b.adm.new }}</NuxtLink>
    </header>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <div v-if="loading" class="skeleton mt-6 h-40 rounded-xl" />
    <div v-else-if="!posts.length" class="adm-empty mt-6"><SiteIcon name="book" class="size-6" />{{ b.adm.empty }}</div>
    <ul v-else class="mt-6 space-y-2">
      <li v-for="p in posts" :key="p.id">
        <article class="adm-row items-center">
          <NuxtLink :to="`/admin/blog/${p.id}`" class="thumb" :aria-label="b.adm.edit">
            <img v-if="p.cover" :src="p.cover.thumbUrl" alt="" class="size-full object-cover" loading="lazy" />
            <SiteIcon v-else name="book" class="size-5 text-base-400" />
          </NuxtLink>
          <div class="min-w-0 flex-1">
            <p class="flex flex-wrap items-center gap-2">
              <NuxtLink :to="`/admin/blog/${p.id}`" class="font-semibold text-base-50 hover:underline">{{ titleOf(p) }}</NuxtLink>
              <span class="tone" :class="tone(p.state)">{{ b.adm.states[p.state] }}</span>
              <span v-for="l in BLOG_LANGS" :key="l" class="font-mono text-[10px] uppercase" :class="p.langs.includes(l) ? 'text-base-200' : 'text-base-600 line-through'">{{ l }}</span>
            </p>
            <p class="mt-0.5 flex flex-wrap gap-x-3 text-xs text-base-400">
              <span class="font-mono">{{ p.path }}</span>
              <span>{{ dateLine(p) }}</span>
              <span v-if="p.author" class="inline-flex items-center gap-1"><PlayerHead :uuid="p.author.uuid" :name="p.author.name" :skin="p.author.skin" :size="14" :fetch="false" />{{ p.author.name }}</span>
            </p>
          </div>
          <div class="flex flex-wrap gap-1.5">
            <a v-if="p.state === 'published'" :href="p.path" target="_blank" rel="noopener" class="btn btn-ghost px-2.5 py-1 text-xs"><SiteIcon name="external" class="size-3.5" />{{ b.adm.view }}</a>
            <NuxtLink :to="`/admin/blog/${p.id}`" class="btn btn-ghost px-2.5 py-1 text-xs">{{ b.adm.edit }}</NuxtLink>
          </div>
        </article>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.thumb {
  display: grid;
  place-items: center;
  width: 5.5rem;
  aspect-ratio: 16 / 9;
  flex-shrink: 0;
  overflow: hidden;
  border-radius: 0.375rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-950);
}
</style>
