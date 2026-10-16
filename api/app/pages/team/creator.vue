<script setup lang="ts">
// Fester Einstieg für „Creator werden“ aus dem Launcher (§35): leitet auf die offene Creator-Stelle (§24.3, purpose
// creator) weiter. Gibt es gerade keine, steht hier ein kurzer Hinweis mit Link zur Team-Seite.
const { lang } = useTeamText()
const lp = useLocalePath()

const { data } = await useApiFetch<{ team: PublicTeam, jobs: JobView[] }>('/v1/site/team', {
  key: 'site-team',
  default: () => ({ team: { groups: [] }, jobs: [] }),
})
const job = computed(() => data.value?.jobs.find((j) => j.purpose === 'creator' && j.status === 'open' && j.id !== 'creator') ?? null)
if (job.value) await navigateTo(lp(`/team/${job.value.id}`), { replace: true, redirectCode: 302 })

const TEXT = {
  en: { title: 'Become a creator', none: 'There is no open creator application right now. Check back soon!', back: 'To the team page' },
  de: { title: 'Creator werden', none: 'Gerade gibt es keine offene Creator-Bewerbung. Schau bald wieder vorbei!', back: 'Zur Team-Seite' },
  es: { title: 'Hazte creador', none: 'Ahora mismo no hay ninguna solicitud de creador abierta. ¡Vuelve pronto!', back: 'A la página del equipo' },
} as const
const tx = computed(() => TEXT[lang.value as keyof typeof TEXT] ?? TEXT.en)

usePageSeo(() => ({ path: '/team/creator', title: tx.value.title, description: tx.value.none, noindex: true }))
</script>

<template>
  <div class="mx-auto max-w-3xl px-4 pt-14 sm:px-6">
    <div class="flex items-center gap-3">
      <h1 class="display text-5xl leading-tight text-base-50">{{ tx.title }}</h1>
      <HalloweenHunt id="web-creator" />
    </div>
    <p class="mt-4 text-lg text-base-400">{{ tx.none }}</p>
    <NuxtLink :to="lp('/team')" class="btn btn-ghost mt-6">{{ tx.back }}</NuxtLink>
  </div>
</template>
