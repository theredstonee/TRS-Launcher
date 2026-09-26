<script setup lang="ts">
import { reportReasonIds, type ReportReason, type ReportTarget } from '~/utils/chat'

// „Melden“: Grund + optionaler Hinweis. Das Team sieht dazu einen Ausschnitt
// des Verlaufs (wie in der Datenschutzerklärung beschrieben).
const props = defineProps<{ target: ReportTarget; label: string }>()
const emit = defineEmits<{ close: []; sent: [] }>()
const toasts = useToasts()

const reason = ref<ReportReason | null>(null)
const note = ref('')
const busy = ref(false)
const error = ref<string | null>(null)

const title = computed(() => t(`social.report.titles.${props.target.kind}`))

async function submit() {
  if (!reason.value) {
    error.value = t('social.report.pickReason')
    return
  }
  const trimmed = note.value.trim()
  if (trimmed.length > 500) {
    error.value = t('social.report.noteTooLong')
    return
  }
  busy.value = true
  error.value = null
  try {
    await backend.social.report(props.target, reason.value, trimmed || null)
    toasts.ok(t('social.report.thanks'))
    emit('sent')
    emit('close')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog :title="title" @close="emit('close')">
    <p class="mb-3 text-sm text-base-200">{{ t('social.report.intro', { name: label }) }}</p>
    <p class="label">{{ t('social.report.reason') }}</p>
    <div class="grid grid-cols-2 gap-2" role="radiogroup" :aria-label="t('social.report.reason')">
      <button
        v-for="key in reportReasonIds"
        :key="key"
        class="rounded-lg border px-3 py-2 text-left text-sm transition-colors"
        :class="reason === key ? 'border-redstone-500 bg-redstone-900/40 text-base-50' : 'border-base-700 text-base-200 hover:border-base-600'"
        role="radio"
        :aria-checked="reason === key"
        :data-testid="`report-reason-${key}`"
        @click="reason = key"
      >
        {{ t(`social.report.reasons.${key}`) }}
      </button>
    </div>
    <label class="label mt-3" for="social-report-note">{{ t('social.report.note') }}</label>
    <textarea id="social-report-note" v-model="note" class="field h-20 resize-none" maxlength="500" />
    <p class="mt-2 text-xs text-base-400">{{ t('social.report.evidence') }}</p>
    <p v-if="error" role="alert" class="mt-2 text-xs text-redstone-300">{{ error }}</p>
    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-danger" :disabled="busy" data-testid="report-submit" @click="submit">{{ t('social.report.submit') }}</button>
    </template>
  </BaseDialog>
</template>
