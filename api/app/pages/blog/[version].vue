<script setup lang="ts">
// Ein Beitrag unter /blog/<…>: Versionen (`0.6.5`) sind Update-Beiträge aus CHANGELOG.md, alles andere (Slug mit
// mindestens einem Buchstaben, ohne Punkte) sind News-Beiträge des Teams (§30). Beide Formen überschneiden sich nie.
const route = useRoute()
const id = computed(() => String(route.params.version ?? ''))
const isVersion = computed(() => /^\d+\.\d+\.\d+(?:-[\w.]+)?$/.test(id.value))
if (!isVersion.value && !(NEWS_SLUG.test(id.value) && id.value.length >= 3 && id.value.length <= 80)) {
  throw createError({ statusCode: 404, statusMessage: 'Not found', fatal: true })
}
</script>

<template>
  <BlogUpdateArticle v-if="isVersion" :key="id" :version="id" />
  <BlogNewsArticle v-else :key="id" :slug="id" />
</template>
