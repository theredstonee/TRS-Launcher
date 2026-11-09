<script setup lang="ts">
import type { AdminApplicationDetail } from '~/utils/applications'

const { t, lang } = useTeamText()
const { api, load, can } = useAdmin()
const route = useRoute()
const id = computed(() => String(route.params.id))
const application = ref<AdminApplicationDetail | null>(null)
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    if (!await load()) return
    application.value = (await api<{ application: AdminApplicationDetail }>(`/v1/admin/applications/${id.value}`)).application
  } catch (cause) {
    error.value = apiMessage(cause)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="adm-page">
    <NuxtLink :to="`/admin/applications/${id}`" class="inline-flex items-center gap-2 text-sm text-base-400 hover:text-base-50">← {{ t.adm.apps.title }}</NuxtLink>
    <div v-if="loading" class="skeleton mt-5 h-80 rounded-xl" />
    <p v-else-if="error" role="alert" class="mt-5 text-sm text-redstone-300">{{ error }}</p>
    <template v-else-if="application">
      <header class="mt-5">
        <h1 class="adm-title">{{ t.myApps.chat.title }}</h1>
        <p class="adm-lead">{{ application.applicant.name }} · {{ inLang(application.job.title, lang) ?? application.job.id }}</p>
      </header>
      <section class="card mt-5 p-5">
        <ApplicationChat :application-id="application.id" :can-review="can('applications.review')" :can-send="application.status !== 'withdrawn' && (can('applications.review') || can('applications.decide'))" />
      </section>
    </template>
  </div>
</template>
