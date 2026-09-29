<script setup lang="ts">
// Ersetzt die Docus-Variante: nur „Seite kopieren“ (Markdown) und „Als Markdown ansehen“ – keine Links zu
// KI-Diensten, kein MCP-Server.
import { useClipboard } from '@vueuse/core'
import { joinURL } from 'ufo'

const route = useRoute()
const appBaseURL = useRuntimeConfig().app.baseURL || '/'
const { copy, copied } = useClipboard()
const { t } = useDocusI18n()

const markdownPath = computed(() => joinURL(appBaseURL, 'raw', `${route.path.replace(/\/+$/, '')}.md`))

const items = computed(() => [
  [
    {
      label: t('docs.copy.link'),
      icon: 'i-lucide-link',
      onSelect() {
        copy(`${window.location.origin}${markdownPath.value}`)
      },
    },
    {
      label: t('docs.copy.view'),
      icon: 'i-lucide-file-text',
      target: '_blank',
      to: markdownPath.value,
      external: true,
    },
  ],
])

async function copyPage() {
  const page = await $fetch<string>(markdownPath.value, { baseURL: '/', responseType: 'text' })
  copy(page)
}
</script>

<template>
  <UFieldGroup size="sm">
    <UButton
      :label="t('docs.copy.page')"
      :icon="copied ? 'i-lucide-check' : 'i-lucide-copy'"
      color="neutral"
      variant="soft"
      :ui="{ leadingIcon: 'text-neutral size-3.5' }"
      @click="copyPage"
    />
    <UDropdownMenu size="sm" :items="items" :content="{ align: 'end', side: 'bottom', sideOffset: 8 }">
      <UButton icon="i-lucide-chevron-down" color="neutral" variant="soft" class="border-l border-muted" :aria-label="t('docs.copy.link')" />
    </UDropdownMenu>
  </UFieldGroup>
</template>
