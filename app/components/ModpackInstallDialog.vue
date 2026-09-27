<script setup lang="ts">
import type { PackPreview, Platform } from '~/types'

// Vor der Modpack-Installation: welches Pack, welche Version – und
// „mit oder ohne TRS Client“. Die Vorschau lädt das Pack schon (der Kern
// hebt die Datei für die Installation auf), damit die Vorauswahl weiß,
// was drinsteckt.
const props = defineProps<{
  request: { pack: { projectId: string; title: string; iconUrl: string | null }; platform: Platform }
}>()
const emit = defineEmits<{ close: [] }>()

const preview = ref<PackPreview | null>(null)
const loading = ref(true)
const failed = ref(false)
const choice = ref(true)
/** Hat der Nutzer schon gewählt? Dann überschreibt die Vorschau nichts mehr. */
const touched = ref(false)

const offer = computed(() => preview.value?.trsClient ?? null)
/** Vanilla-Packs (ohne Modloader) fragen nicht – wie bisher. */
const showChoice = computed(() => loading.value || failed.value || trsShowsChoice(offer.value))

// Vorschau setzt `choice` nur im Ladezustand (`loading` noch true) – alles
// andere ist eine Wahl des Nutzers, auch während noch geprüft wird.
let presetting = false
watch(choice, () => {
  if (!presetting) touched.value = true
})

onMounted(async () => {
  const { platform, pack } = props.request
  try {
    preview.value =
      platform === 'curseforge' ? await backend.curseforge.previewModpack(pack.projectId) : await backend.previewModpack(pack.projectId)
    presetting = true
    choice.value = trsAfterPreview(preview.value.trsClient, touched.value, choice.value)
    await nextTick()
    presetting = false
  } catch (e) {
    // Gesperrtes CurseForge-Pack o. Ä.: Die Installation meldet den Fehler samt Link.
    failed.value = true
    console.warn('Modpack-Vorschau fehlgeschlagen', errorMessage(e))
  } finally {
    loading.value = false
  }
})

/**
 * Sofort installierbar, auch während die Vorschau noch prüft: Hat der Nutzer
 * dann noch nicht gewählt, entscheidet der Kern selbst (Einstellung bzw.
 * Vorauswahl samt Konfliktprüfung) – dieselbe Logik wie nach der Vorschau.
 */
function install() {
  const { pack, platform } = props.request
  const early = loading.value && !touched.value
  installModpackTask(pack, platform, {
    versionId: preview.value?.versionId ?? null,
    trsClient: early ? null : trsRequest(offer.value, choice.value),
  })
  emit('close')
}
</script>

<template>
  <BaseDialog :title="t('trsChoice.install.title')" @close="emit('close')">
    <div class="mb-4 flex items-center gap-3">
      <ModIcon :src="request.pack.iconUrl" :name="request.pack.title" :size="48" />
      <div class="min-w-0">
        <p class="truncate font-medium">{{ preview?.name || request.pack.title }}</p>
        <p v-if="preview" class="truncate text-xs text-base-400">
          {{ t('trsChoice.install.details', { version: preview.gameVersion, loader: loaderLabels[preview.loader.kind] }) }}<template v-if="preview.modCount"> · {{ t('import.modCount', preview.modCount) }}</template>
        </p>
        <div v-else-if="loading" class="skeleton mt-1 h-3 w-40" />
      </div>
    </div>

    <template v-if="showChoice">
      <h3 class="mb-1.5 text-xs font-medium uppercase tracking-wide text-base-400">{{ t('trsChoice.title') }}</h3>
      <TrsClientChoice v-model="choice" :offer="offer" :loading="loading" :failed="failed" />
    </template>

    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button class="btn btn-primary" @click="install">{{ t('common.actions.install') }}</button>
    </template>
  </BaseDialog>
</template>
