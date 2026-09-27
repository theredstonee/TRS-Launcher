<script setup lang="ts">
// Öffentliche Team-Seite (§24.3): offene Stellen + Team-Mitglieder je öffentlicher Rolle (Farbe, Kopf).
import { breadcrumbLd } from '#shared/seo'

const { t, lang } = useTeamText()
const { m } = useLang()
const lp = useLocalePath()
const siteUrl = useSiteUrl()

const { data } = await useFetch<{ team: PublicTeam, jobs: JobView[] }>('/v1/site/team', {
  key: 'site-team',
  default: () => ({ team: { roles: [] }, jobs: [] }),
})

usePageSeo(() => ({
  path: '/team',
  title: t.value.seo.team.title,
  description: t.value.seo.team.description,
  jsonLd: [breadcrumbLd(siteUrl, lang.value, [{ name: m.value.nav.home, path: '/' }, { name: m.value.nav.team, path: '/team' }])],
}))

const jobs = computed(() => data.value?.jobs ?? [])
const roles = computed(() => data.value?.team.roles ?? [])
const roleName = (r: TeamRole) => r.name ?? t.value.adm.roleNames[r.id] ?? r.id
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="max-w-3xl">
      <p class="text-xs tracking-[0.18em] text-redstone-300 uppercase">{{ t.team.kicker }}</p>
      <h1 class="display mt-2 text-5xl leading-tight text-base-50">{{ t.team.title }}</h1>
      <p class="mt-3 text-lg text-base-400">{{ t.team.lead }}</p>
    </header>

    <section class="mt-12" aria-labelledby="jobs-title">
      <h2 id="jobs-title" class="section-title flex items-center gap-2"><SiteIcon name="briefcase" class="size-5 text-redstone-400" />{{ t.team.jobsTitle }}</h2>
      <p v-if="!jobs.length" class="mt-4 rounded-lg border border-base-800 bg-base-900 px-4 py-5 text-base-400">{{ t.team.noJobs }}</p>
      <ul v-else class="mt-4 grid gap-4 md:grid-cols-2">
        <li v-for="j in jobs" :key="j.id">
          <NuxtLink :to="lp(`/team/${j.id}`)" class="card card-hover job-card flex h-full flex-col p-6" :style="{ '--role': j.role?.color ?? 'var(--color-redstone-500)' }">
            <div class="flex items-start gap-3">
              <h3 class="heading flex-1 text-xl text-base-50">{{ inLang(j.texts, lang)?.title }}</h3>
              <RoleBadge v-if="j.role" :role="j.role" small />
            </div>
            <p class="mt-2 flex-1 text-sm text-base-300">{{ inLang(j.texts, lang)?.summary }}</p>
            <span class="mt-4 inline-flex items-center gap-1.5 text-sm font-semibold text-redstone-300">{{ t.team.apply }}<SiteIcon name="arrow" class="size-4" /></span>
          </NuxtLink>
        </li>
      </ul>
    </section>

    <section class="mt-16" aria-labelledby="team-title">
      <h2 id="team-title" class="section-title flex items-center gap-2"><SiteIcon name="users" class="size-5 text-redstone-400" />{{ t.team.membersTitle }}</h2>
      <p v-if="!roles.length" class="mt-4 text-base-400">{{ t.team.noMembers }}</p>
      <div v-else class="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <section v-for="r in roles" :key="r.id" class="card role-group p-5" :style="{ '--role': r.color }">
          <h3 class="flex items-center gap-2 text-sm font-semibold tracking-wide uppercase" :style="{ color: r.color }">
            <span class="size-2 rounded-full" :style="{ background: r.color, boxShadow: `0 0 10px ${r.color}` }" />{{ roleName(r) }}
            <span class="ml-auto text-xs text-base-500">{{ r.members.length }}</span>
          </h3>
          <ul class="mt-4 space-y-2.5">
            <li v-for="p in r.members" :key="p.uuid" class="flex items-center gap-3">
              <PlayerHead :uuid="p.uuid" :name="p.name" :skin="p.skin" :size="36" />
              <span class="min-w-0 truncate font-semibold text-base-100">{{ p.name }}</span>
            </li>
          </ul>
        </section>
      </div>
    </section>
  </div>
</template>

<style scoped>
.job-card {
  border-left: 3px solid var(--role);
}
.role-group {
  border-top: 3px solid var(--role);
  background-image: linear-gradient(180deg, color-mix(in srgb, var(--role) 8%, transparent), transparent 55%);
}
</style>
