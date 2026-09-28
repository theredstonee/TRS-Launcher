<script setup lang="ts">
// Öffentliche Team-Seite (§26.1): die im Admin eingetragenen Mitglieder je Rolle (Owner groß oben, dann nach Rang)
// mit 3D-Skin, Titel, Discord und Links; darunter die offenen Stellen (§24.3).
import { breadcrumbLd } from '#shared/seo'

const { t, lang } = useTeamText()
const { m } = useLang()
const lp = useLocalePath()
const siteUrl = useSiteUrl()

const { data } = await useApiFetch<{ team: PublicTeam, jobs: JobView[] }>('/v1/site/team', {
  key: 'site-team',
  default: () => ({ team: { groups: [] }, jobs: [] }),
})

usePageSeo(() => ({
  path: '/team',
  title: t.value.seo.team.title,
  description: t.value.seo.team.description,
  jsonLd: [breadcrumbLd(siteUrl, lang.value, [{ name: m.value.nav.home, path: '/' }, { name: m.value.nav.team, path: '/team' }])],
}))

const jobs = computed(() => data.value?.jobs ?? [])
const groups = computed(() => data.value?.team.groups ?? [])
/** Owner-Gruppe steht groß über allen anderen. */
const lead = computed(() => groups.value.find((g) => g.id === 'owner') ?? null)
const rest = computed(() => groups.value.filter((g) => g.id !== 'owner'))
const roleName = (r: TeamRole) => r.name ?? t.value.adm.roleNames[r.id] ?? r.id
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pt-14 sm:px-6">
    <header class="max-w-3xl">
      <p class="text-xs tracking-[0.18em] text-redstone-300 uppercase">{{ t.team.kicker }}</p>
      <h1 class="display mt-2 text-5xl leading-tight text-base-50">{{ t.team.title }}</h1>
      <p class="mt-3 text-lg text-base-400">{{ t.team.lead }}</p>
      <a v-if="jobs.length" href="#jobs-title" class="mt-5 inline-flex items-center gap-1.5 text-sm font-semibold text-redstone-400 hover:text-redstone-300">
        <SiteIcon name="briefcase" class="size-4" />{{ t.team.jobsTitle }} · {{ jobs.length }}
      </a>
    </header>

    <section class="mt-12" aria-labelledby="team-title">
      <h2 id="team-title" class="sr-only">{{ t.team.membersTitle }}</h2>
      <p v-if="!groups.length" class="rounded-lg border border-base-800 bg-base-900 px-4 py-5 text-base-400">{{ t.team.noMembers }}</p>

      <div v-if="lead" class="lead-group" :style="{ '--role': lead.color }">
        <h3 class="group-title justify-center">
          <SiteIcon name="crown" class="size-4" />{{ roleName(lead) }}
        </h3>
        <ul class="mt-5 flex flex-wrap justify-center gap-5">
          <li v-for="p in lead.members" :key="p.uuid" class="w-full max-w-xs">
            <TeamMemberCard :member="p" :role="lead" big />
          </li>
        </ul>
      </div>

      <section v-for="g in rest" :key="g.id" class="mt-14" :style="{ '--role': g.color }" :aria-labelledby="`group-${g.id}`">
        <h3 :id="`group-${g.id}`" class="group-title">
          <span class="dot" aria-hidden="true" />{{ roleName(g) }}
          <span class="ml-1 text-xs font-normal text-base-400">{{ g.members.length }}</span>
        </h3>
        <ul class="mt-5 grid grid-cols-1 gap-4 min-[420px]:grid-cols-2 md:grid-cols-3 lg:grid-cols-4">
          <li v-for="p in g.members" :key="p.uuid">
            <TeamMemberCard :member="p" :role="g" />
          </li>
        </ul>
      </section>
    </section>

    <section class="mt-20" aria-labelledby="jobs-title">
      <h2 id="jobs-title" class="section-title flex scroll-mt-24 items-center gap-2"><SiteIcon name="briefcase" class="size-5 text-redstone-400" />{{ t.team.jobsTitle }}</h2>
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
  </div>
</template>

<style scoped>
.job-card {
  border-left: 3px solid var(--role);
}
.group-title {
  display: flex;
  align-items: center;
  gap: 0.55rem;
  font-size: 0.85rem;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: color-mix(in srgb, var(--role) 70%, white);
}
.group-title .dot {
  width: 0.55rem;
  height: 0.55rem;
  border-radius: 999px;
  background: var(--role);
  box-shadow: 0 0 10px var(--role);
}
.group-title::after {
  content: '';
  flex: 1;
  height: 1px;
  margin-left: 0.5rem;
  background: linear-gradient(90deg, color-mix(in srgb, var(--role) 45%, transparent), transparent);
}
.lead-group .group-title::after {
  display: none;
}
.lead-group {
  position: relative;
  padding: 1.75rem 1rem 0.5rem;
  border-radius: 1.25rem;
  background: radial-gradient(70% 90% at 50% 0%, color-mix(in srgb, var(--role) 12%, transparent), transparent 70%);
}
</style>
