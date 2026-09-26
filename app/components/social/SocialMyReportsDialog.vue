<script setup lang="ts">
import type { MyReport } from '~/utils/chat'

// Eigene Meldungen mit Status (offen → in Prüfung → erledigt). Welche Maßnahme
// getroffen wurde, erfahren Melder nicht – nur ob etwas getan wurde.
const emit = defineEmits<{ close: [] }>()
const chat = useChatStore()
const loading = ref(!chat.reports)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    await chat.loadReports()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
})

function statusText(r: MyReport): string {
  if (r.status === 'resolved') return r.outcome === 'actioned' ? t('social.reports.outcome.actioned') : t('social.reports.outcome.dismissed')
  return r.status === 'in_review' ? t('social.reports.status.inReview') : t('social.reports.status.open')
}
function statusClass(r: MyReport): string {
  if (r.status === 'open') return 'bg-lamp-900 text-lamp-300'
  if (r.status === 'in_review') return 'bg-base-700 text-base-100'
  return r.outcome === 'actioned' ? 'bg-ok/15 text-ok' : 'bg-base-800 text-base-400'
}
</script>

<template>
  <BaseDialog :title="t('social.reports.title')" wide @close="emit('close')">
    <p class="mb-3 text-sm text-base-400">{{ t('social.reports.intro') }}</p>
    <div v-if="loading" class="space-y-2"><div v-for="i in 3" :key="i" class="skeleton h-12" /></div>
    <p v-else-if="error" role="alert" class="text-sm text-redstone-300">{{ error }}</p>
    <p v-else-if="!chat.reports?.length" class="py-6 text-center text-sm text-base-400">{{ t('social.reports.empty') }}</p>
    <ul v-else class="max-h-96 space-y-1.5 overflow-y-auto" data-testid="my-reports">
      <li v-for="r in chat.reports" :key="r.id" class="flex items-center gap-3 rounded-lg bg-base-850 px-3 py-2">
        <SocialIcon name="flag" class="size-4 shrink-0 text-base-400" />
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm text-base-50">{{ t(`social.report.titles.${r.kind}`) }} · {{ t(`social.report.reasons.${r.reason}`) }}</p>
          <p class="text-xs text-base-400">{{ dateTime(r.createdAt) }}</p>
        </div>
        <span class="badge" :class="statusClass(r)">{{ statusText(r) }}</span>
      </li>
    </ul>
    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>
</template>
