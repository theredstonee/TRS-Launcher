<script setup lang="ts">
// „Was ist neu“ nach einem Update – Text aus CHANGELOG.md (Englisch + Deutsch).
const whatsNew = useWhatsNewStore()

const german = computed(() => currentLocale.value === 'de')
/** Für alle Sprachen außer Deutsch und Englisch: Hinweis, dass es die Notizen nur auf Englisch gibt. */
const englishOnly = computed(() => !['de', 'en'].includes(currentLocale.value))

function close() {
  whatsNew.open = false
}
</script>

<template>
  <BaseDialog :title="t('whatsNew.title', { version: whatsNew.version })" wide @close="close">
    <p v-if="englishOnly" class="mb-3 text-xs text-base-400">{{ t('whatsNew.englishOnly') }}</p>
    <ol class="-mr-2 max-h-[28rem] space-y-5 overflow-y-auto pr-2">
      <li v-for="entry in whatsNew.entries" :key="entry.version ?? 'next'" class="relative border-l-2 border-base-700 pl-4">
        <span class="absolute top-1 -left-[5px] size-2 rounded-full bg-redstone-500" />
        <p v-if="whatsNew.entries.length > 1" class="text-sm font-medium">
          {{ t('whatsNew.version', { version: entry.version ?? '' }) }}
          <span v-if="entry.date" class="ml-2 text-xs font-normal text-base-400">{{ formatShortDate(`${entry.date}T12:00:00`) }}</span>
        </p>
        <MarkdownView :source="german ? entry.de : entry.en" class="mt-1.5" />
      </li>
    </ol>
    <div class="mt-5 flex justify-end">
      <button type="button" class="btn btn-primary" @click="close">{{ t('whatsNew.close') }}</button>
    </div>
  </BaseDialog>
</template>
