<script setup lang="ts">
// Der ganze Update-Beitrag: Update-Banner (feste Vorlage + Motiv), Update-Name, Text und Screenshots.
// Text läuft über MarkdownView (DOMPurify); Bilder nur aus dem mitgelieferten Ordner /news/.
const props = defineProps<{ entry: ChangelogEntry; title: string }>()
const emit = defineEmits<{ close: [] }>()

const german = computed(() => currentLocale.value === 'de')
const englishOnly = computed(() => !['de', 'en'].includes(currentLocale.value))
/** Umbrüche aus der Datei (eingerückte Folgezeilen) zu einem Absatz zusammenziehen – MarkdownView bricht sonst dort um. */
const blocks = computed(() =>
  splitPost(german.value ? props.entry.de : props.entry.en).map((b) =>
    b.kind === 'text' ? { ...b, markdown: b.markdown.replace(/\n {2,}(?=\S)/g, ' ') } : b,
  ),
)
const kicker = computed(() => updateKicker(props.entry))
</script>

<template>
  <BaseDialog :title="title" wide @close="emit('close')">
    <div class="-mx-5 -mt-4 mb-4 h-44">
      <UpdateBanner :kicker="kicker" :title="title" :accent="entry.banner?.accent" :motif="entry.banner?.motif" tag="p" />
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

