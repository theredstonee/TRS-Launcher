<script setup lang="ts">
import type { Collections } from '@nuxt/content'

// Übersicht eines Bereichs (`content/<lang>/<N.bereich>/index.md`): Karten zu allen Seiten des Bereichs – neue
// Seiten erscheinen hier automatisch (Reihenfolge = Nummer im Dateinamen). Einsatz im Markdown: `:section-pages`.

interface PageRow {
  path: string
  stem: string
  title: string
  description?: string
  navigation?: boolean | { icon?: string, title?: string }
}

const route = useRoute()
const { locale } = useDocusI18n()
const here = route.path.replace(/\/+$/, '')
const collection = computed(() => `docs_${locale.value}` as keyof Collections)

const { data: pages } = await useAsyncData(`section-pages:${here}`, async () => {
  const rows = (await queryCollection(collection.value)
    .where('path', 'LIKE', `${here}/%`)
    .select('path', 'stem', 'title', 'description', 'navigation')
    .all()) as unknown as PageRow[]
  return rows
    .filter((r) => r.navigation !== false && !r.path.endsWith('/.navigation'))
    .sort((a, b) => a.stem.localeCompare(b.stem, 'en', { numeric: true }))
    .map((r) => ({
      path: r.path,
      title: (typeof r.navigation === 'object' && r.navigation.title) || r.title,
      description: r.description,
      icon: (typeof r.navigation === 'object' && r.navigation.icon) || 'i-lucide-file-text',
    }))
})
</script>

<template>
  <UPageGrid v-if="pages?.length" class="not-prose my-6 lg:grid-cols-2 xl:grid-cols-2">
    <UPageCard
      v-for="p in pages"
      :key="p.path"
      :title="p.title"
      :description="p.description"
      :icon="p.icon"
      :to="p.path"
      spotlight
    />
  </UPageGrid>
</template>
