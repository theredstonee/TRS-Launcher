<script setup lang="ts">
// Links aus Nachrichten öffnen erst nach Bestätigung (die volle Adresse ist
// sichtbar) – und nur über den Kern (nur HTTPS, im Standardbrowser).
const props = defineProps<{ href: string }>()
const emit = defineEmits<{ close: [] }>()
const toasts = useToasts()

const host = computed(() => {
  try {
    return new URL(props.href).hostname
  } catch {
    return ''
  }
})

async function open() {
  try {
    await backend.openExternalUrl(props.href)
  } catch (e) {
    toasts.error(e)
  }
  emit('close')
}
</script>

<template>
  <BaseDialog :title="t('social.links.title')" @close="emit('close')">
    <p class="text-sm text-base-200">{{ t('social.links.text', { host }) }}</p>
    <p class="mt-3 rounded-md bg-base-950 px-3 py-2 font-mono text-xs break-all text-base-100">{{ href }}</p>
    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" data-testid="link-open" @click="open"><SocialIcon name="external" class="size-4" />{{ t('social.links.open') }}</button>
    </template>
  </BaseDialog>
</template>
