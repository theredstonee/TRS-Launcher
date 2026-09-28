<script setup lang="ts">
// Ein Issue als ganze Seite (§28) – Inhalt in IssueDetailView (dieselbe Ansicht wie im Fenster über Board/Liste).
// Serverseitig ohne persönliche Daten gerendert (für Suchmaschinen und Link-Vorschauen), 404 für unbekannte Issues.
import { setResponseStatus } from 'h3'
import { breadcrumbLd } from '#shared/seo'
import type { IssuePageView as IssuePageData } from '#shared/issues'

const route = useRoute()
const nr = computed(() => {
  const n = Number(String(route.params.nr ?? '').replace(/^#/, ''))
  return Number.isSafeInteger(n) && n > 0 ? n : 0
})
const { it, lang, fill } = useIssueText()
const { m } = useLang()
const siteUrl = useSiteUrl()

const { data } = await useAsyncData<IssuePageData | null>(
  () => `issue-page-${nr.value}`,
  () => (nr.value ? $fetch<IssuePageData>(`/v1/issues/${nr.value}`).catch(() => null) : Promise.resolve(null)),
)
const issue = computed(() => data.value?.issue ?? null)
if (!issue.value && import.meta.server) {
  const event = useRequestEvent()
  if (event) setResponseStatus(event, 404)
}

usePageSeo(() => ({
  path: `/issues/${nr.value}`,
  title: issue.value ? fill(it.value.seo.detail, { n: issue.value.number, title: issue.value.title }) : it.value.detail.notFound,
  description: issue.value
    ? `${it.value.types[issue.value.type]} · ${it.value.areas[issue.value.area]} · ${it.value.statuses[issue.value.status]} – ${issue.value.description.replace(/[#*_`>[\]()!]/g, '').slice(0, 140)}`
    : it.value.seo.list.description,
  noindex: !issue.value,
  jsonLd: issue.value
    ? [breadcrumbLd(siteUrl, lang.value, [
        { name: m.value.nav.home, path: '/' },
        { name: it.value.list.title, path: '/issues' },
        { name: `#${issue.value.number}`, path: `/issues/${issue.value.number}` },
      ])]
    : [],
}))
</script>

<template>
  <IssueWorkspace active="issue">
    <div class="mx-auto max-w-6xl px-4 pt-7 pb-10 sm:px-6">
      <IssueDetailView :key="nr" :nr="nr" :initial="data" />
    </div>
  </IssueWorkspace>
</template>
