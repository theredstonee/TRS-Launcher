<script setup lang="ts">
// Bestätigung vor folgenreichen Aktionen (Strafen, Sammelaktionen, Welt schließen).
withDefaults(defineProps<{ title: string, text?: string, confirmLabel?: string, danger?: boolean, busy?: boolean, error?: string }>(), {
  text: '',
  confirmLabel: '',
  danger: false,
  busy: false,
  error: '',
})
const emit = defineEmits<{ confirm: [], cancel: [] }>()
const { a } = useAdminText()
</script>

<template>
  <AdminDialog :title="title" size="sm" @close="emit('cancel')">
    <p v-if="text" class="text-sm text-base-200">{{ text }}</p>
    <slot />
    <p v-if="error" role="alert" class="mt-3 text-sm text-redstone-300">{{ error }}</p>
    <template #footer>
      <button type="button" class="btn btn-ghost" @click="emit('cancel')">{{ a.common.cancel }}</button>
      <button type="button" class="btn" :class="danger ? 'btn-danger' : 'btn-primary'" :disabled="busy" autofocus @click="emit('confirm')">
        {{ confirmLabel || a.common.confirm }}
      </button>
    </template>
  </AdminDialog>
</template>
