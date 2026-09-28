<script setup lang="ts">
// Issue-Tracker (§28): Workspace-Leiste + Explorer, standardmäßig als Liste (Umschalter Board | Liste);
// ein Klick auf ein Issue führt direkt auf dessen ganze Seite.
import { breadcrumbLd } from '#shared/seo'

const { it, lang } = useIssueText()
const { m } = useLang()
const siteUrl = useSiteUrl()

usePageSeo(() => ({
  path: '/issues',
  title: it.value.seo.list.title,
  description: it.value.seo.list.description,
  jsonLd: [breadcrumbLd(siteUrl, lang.value, [{ name: m.value.nav.home, path: '/' }, { name: it.value.list.title, path: '/issues' }])],
}))
</script>

<template>
  <IssueWorkspace active="issues">
    <div class="px-4 pt-8 pb-6 sm:px-6 lg:px-8">
      <IssueExplorer default-view="list" open-as="page" :title="it.list.title" :lead="it.list.lead" />
    </div>
  </IssueWorkspace>
</template>
