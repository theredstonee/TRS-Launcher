<script setup lang="ts">
// Der ganze Update-Beitrag: Banner aus der Redstone-Szene, Update-Name, Text und Screenshots.
// Text läuft über MarkdownView (DOMPurify); Bilder nur aus dem mitgelieferten Ordner /news/.
const props = defineProps<{ entry: ChangelogEntry; title: string; seed: number }>()
const emit = defineEmits<{ close: [] }>()

const german = computed(() => currentLocale.value === 'de')
const englishOnly = computed(() => !['de', 'en'].includes(currentLocale.value))
const blocks = computed(() => splitPost(german.value ? props.entry.de : props.entry.en))
</script>

<template>
  <BaseDialog :title="title" wide @close="emit('close')">
    <div class="relative -mx-5 -mt-4 mb-4 h-36 overflow-hidden">
      <RedstoneScene fill :seed="seed" class="absolute inset-0" />
      <div class="post-shade absolute inset-0" />
      <div class="absolute inset-x-5 bottom-3">
        <p class="text-xs font-semibold tracking-[0.18em] text-lamp-300 uppercase">
          {{ t('updateNews.kicker', { version: entry.version ?? '' }) }}
          <span v-if="entry.date" class="font-normal text-base-300"> · {{ formatShortDate(`${entry.date}T12:00:00`) }}</span>
        </p>
        <p class="display text-3xl leading-tight text-base-50 drop-shadow">{{ title }}</p>
      </div>
    </div>
    <p v-if="englishOnly" class="mb-3 text-xs text-base-400">{{ t('whatsNew.englishOnly') }}</p>
    <div class="-mr-2 max-h-[26rem] space-y-4 overflow-y-auto pr-2">
      <template v-for="(block, i) in blocks" :key="i">
        <MarkdownView v-if="block.kind === 'text'" :source="block.markdown" />
        <figure v-else class="space-y-1.5">
          <img :src="block.src" :alt="block.caption" loading="lazy" class="w-full rounded-lg border border-base-700 bg-base-900" />
          <figcaption v-if="block.caption" class="text-xs text-base-400">{{ block.caption }}</figcaption>
        </figure>
      </template>
    </div>
    <template #actions>
      <button type="button" class="btn btn-primary" @click="emit('close')">{{ t('whatsNew.close') }}</button>
    </template>
  </BaseDialog>
</template>

<style scoped>
.post-shade {
  background: linear-gradient(to top, rgb(12 11 14 / 0.9), rgb(12 11 14 / 0.2) 70%);
}
</style>
