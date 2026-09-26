<script setup lang="ts">
import { REACTIONS, REACTION_IDS, type ReactionId } from '~/utils/chat'

// Feste Reaktionen der API als kleines Auswahlfeld (Klick daneben/Esc schließt).
const emit = defineEmits<{ pick: [emoji: ReactionId]; close: [] }>()
const root = useTemplateRef<HTMLElement>('root')

function outside(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) emit('close')
}
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}
onMounted(() => {
  document.addEventListener('mousedown', outside)
  window.addEventListener('keydown', onKey)
  root.value?.querySelector('button')?.focus()
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', outside)
  window.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div ref="root" class="menu bottom-9 z-50 grid w-max min-w-0 grid-cols-5 gap-0.5 p-1.5" role="menu" :aria-label="t('social.message.react')">
    <button
      v-for="id in REACTION_IDS"
      :key="id"
      class="grid size-9 place-items-center rounded-md text-xl transition-transform hover:scale-110 hover:bg-base-700"
      role="menuitem"
      :aria-label="t(`social.reactions.${id}`)"
      :title="t(`social.reactions.${id}`)"
      @click="emit('pick', id)"
    >
      {{ REACTIONS[id] }}
    </button>
  </div>
</template>
