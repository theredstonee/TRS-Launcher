<script setup lang="ts">
// Bilder einer Nachricht groß: Pfeiltasten blättern, Esc schließt. Bilder
// kommen über `trschat:` aus dem Kern (ohne Token in der URL).
const props = defineProps<{ urls: string[]; start: number; canReport?: boolean }>()
const emit = defineEmits<{ close: []; report: [index: number] }>()
const index = ref(Math.min(Math.max(0, props.start), Math.max(0, props.urls.length - 1)))

function step(delta: number) {
  const n = props.urls.length
  if (n) index.value = (index.value + delta + n) % n
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
  else if (e.key === 'ArrowRight') step(1)
  else if (e.key === 'ArrowLeft') step(-1)
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[70] flex flex-col bg-black/90" role="dialog" aria-modal="true" :aria-label="t('social.chat.imageViewer')" @mousedown.self="emit('close')">
      <div class="flex items-center gap-2 px-4 py-3 text-sm text-white/80">
        <span class="flex-1">{{ index + 1 }} / {{ urls.length }}</span>
        <button v-if="canReport" class="btn btn-ghost px-3 py-1.5 text-xs" @click="emit('report', index)">
          <SocialIcon name="flag" class="size-3.5" />{{ t('social.message.reportImage') }}
        </button>
        <button class="btn-icon" :aria-label="t('common.actions.close')" @click="emit('close')"><SocialIcon name="close" class="size-4" /></button>
      </div>
      <div class="relative flex min-h-0 flex-1 items-center justify-center px-14 pb-6" @mousedown.self="emit('close')">
        <img :src="urls[index]" alt="" class="max-h-full max-w-full rounded-md object-contain shadow-2xl" draggable="false" />
        <template v-if="urls.length > 1">
          <button class="btn-icon absolute left-3 size-10 rounded-full" :aria-label="t('social.chat.previousImage')" @click="step(-1)">‹</button>
          <button class="btn-icon absolute right-3 size-10 rounded-full" :aria-label="t('social.chat.nextImage')" @click="step(1)">›</button>
        </template>
      </div>
    </div>
  </Teleport>
</template>
