<script setup lang="ts">
import type { PendingApplicationConfirmation } from '~/utils/applications'

const { t, lang, fill, date } = useTeamText()
const { account, load, api } = useAccount()
const lp = useLocalePath()
const pending = ref<PendingApplicationConfirmation[]>([])
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    if (await load()) {
      pending.value = (await api<{ pendingConfirmations: PendingApplicationConfirmation[] }>('/v1/me/applications')).pendingConfirmations
    }
  } catch (cause) {
    error.value = apiMessage(cause)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 pt-14 sm:px-6">
    <header>
      <h1 class="display text-4xl leading-tight text-base-50">{{ t.myApps.confirmPendingPageTitle }}</h1>
      <p class="mt-3 text-lg text-base-300">{{ t.myApps.confirmPendingPageText }}</p>
    </header>
    <div v-if="loading" class="skeleton mt-8 h-32 rounded-xl" />
    <p v-else-if="error" role="alert" class="mt-6 text-sm text-redstone-300">{{ error }}</p>
    <section v-else-if="pending.length" class="mt-8 space-y-3" aria-live="polite">
      <article v-for="entry in pending" :key="entry.id" class="card border-l-4 border-l-lamp-400 p-5">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <h2 class="font-semibold text-base-50">{{ inLang(entry.job.title, lang) ?? entry.job.id }}</h2>
          <span class="tone tone-warn">{{ t.myApps.pendingBadge }}</span>
        </div>
        <p class="mt-2 text-sm text-base-300">{{ t.myApps.pendingEmail.replace('{email}', entry.email) }}</p>
        <p class="mt-1 text-sm text-base-400">{{ t.myApps.confirmPendingPageHelp }}</p>
        <p class="mt-2 text-xs text-base-500">{{ fill(t.myApps.pendingExpires, { date: date(entry.expiresAt) }) }}</p>
        <a v-if="entry.confirmationUrl" :href="entry.confirmationUrl" class="btn btn-primary mt-4">{{ t.myApps.continueConfirmation }}</a>
      </article>
    </section>
    <p v-else class="card mt-8 p-5 text-sm text-base-300">{{ t.myApps.confirmPendingPageHelp }}</p>
    <NuxtLink :to="lp('/applications')" class="btn btn-ghost mt-5">{{ t.myApps.title }}</NuxtLink>
  </div>
</template>
