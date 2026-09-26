<script setup lang="ts">
// Rückfrage im Team-Bereich (Sammelaktionen, Welt schließen, Rolle entfernen …),
// optional mit Begründung. Enter bestätigt, Escape bricht ab.
const props = defineProps<{
  title: string
  text: string
  confirmLabel: string
  danger?: boolean
  busy?: boolean
  /** Begründung abfragen (`required` = Pflicht). */
  reason?: { label: string; required?: boolean; max?: number; placeholder?: string }
  error?: string | null
}>()
const emit = defineEmits<{ confirm: [reason: string]; close: [] }>()
const reason = ref('')
const missing = computed(() => !!props.reason?.required && !reason.value.trim())

function confirm() {
  if (props.busy || missing.value) return
  emit('confirm', reason.value.trim())
}
</script>

<template>
  <BaseDialog :title="title" @close="emit('close')">
    <p class="text-sm text-base-200">{{ text }}</p>
    <template v-if="props.reason">
      <label class="label mt-4" for="admin-confirm-reason">{{ props.reason.label }}{{ props.reason.required ? ' *' : '' }}</label>
      <input
        id="admin-confirm-reason"
        v-model="reason"
        class="field"
        :maxlength="props.reason.max ?? 500"
        :placeholder="props.reason.placeholder"
        data-testid="confirm-reason"
        @keydown.enter.prevent="confirm"
      />
    </template>
    <p v-if="error" role="alert" class="mt-3 text-xs text-redstone-300">{{ error }}</p>
    <template #actions>
      <button class="btn btn-ghost" :disabled="busy" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button :class="danger ? 'btn btn-danger' : 'btn btn-primary'" :disabled="busy || missing" data-testid="confirm-ok" @click="confirm">
        {{ busy ? t('team.common.working') : confirmLabel }}
      </button>
    </template>
  </BaseDialog>
</template>
