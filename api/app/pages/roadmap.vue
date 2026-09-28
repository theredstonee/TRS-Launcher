<script setup lang="ts">
// Roadmap (§28): dasselbe wie die Issue-Liste, standardmäßig als Board mit sechs Spalten.
import { breadcrumbLd } from '#shared/seo'

const { it, lang } = useIssueText()
const { m } = useLang()
const siteUrl = useSiteUrl()

usePageSeo(() => ({
  path: '/roadmap',
  title: it.value.seo.roadmap.title,
  description: it.value.seo.roadmap.description,
  jsonLd: [breadcrumbLd(siteUrl, lang.value, [{ name: m.value.nav.home, path: '/' }, { name: it.value.roadmap.title, path: '/roadmap' }])],
}))
</script>

<template>
  <IssueWorkspace active="roadmap">
    <div class="px-4 pt-8 pb-6 sm:px-6 lg:px-8">
      <IssueExplorer default-view="board" :title="it.roadmap.title" :lead="it.roadmap.lead" />
    </div>
  </IssueWorkspace>
</template>
