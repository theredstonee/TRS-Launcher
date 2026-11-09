<script setup lang="ts">
import type { MyApplication } from '~/utils/applications'

const { t, lang } = useTeamText()
const { account, load, api } = useAccount()
const route = useRoute()
const lp = useLocalePath()
const id = computed(() => String(route.params.id))
const application = ref<MyApplication | null>(null)
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    if (!await load()) return
    const result = await api<{ applications: MyApplication[] }>('/v1/me/applications')
    application.value = result.applications.find(item => item.id === id.value) ?? null
    if (!application.value) error.value = t.value.myApps.notFound
  } catch (cause) {
    error.value = apiMessage(cause)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="mx-auto max-w-3xl px-4 pt-12 sm:px-6">
    <NuxtLink :to="lp('/applications')" class="inline-flex items-center gap-2 text-sm text-base-400 hover:text-base-50">← {{ t.myApps.title }}</NuxtLink>
    <div v-if="loading" class="skeleton mt-5 h-80 rounded-xl" />
    <p v-else-if="error" role="alert" class="mt-5 text-sm text-redstone-300">{{ error }}</p>
    <template v-else-if="application">
      <header class="mt-5">
        <h1 class="display text-4xl text-base-50">{{ inLang(application.job.title, lang) ?? application.job.id }}</h1>
        <span class="tone mt-3 inline-flex" :class="applicationTone(application.status)">{{ t.myApps.status[application.status] }}</span>
      </header>
      <section class="card mt-6 p-5">
        <div v-if="application.response" class="mb-5 rounded-md border border-base-800 bg-base-950 p-4">
          <h2 class="text-xs font-semibold tracking-wide text-base-400 uppercase">{{ t.myApps.response }}</h2>
          <p class="mt-1 whitespace-pre-line text-sm text-base-100">{{ application.response }}</p>
        </div>
        <ApplicationChat :application-id="application.id" :can-send="application.status !== 'withdrawn'" />
      </section>
    </template>
  </div>
</template>
