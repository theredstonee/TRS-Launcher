<script setup lang="ts">
defineProps<{ title: string; wide?: boolean }>()
const emit = defineEmits<{ close: [] }>()

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-6" @mousedown.self="emit('close')">
    <section role="dialog" aria-modal="true" :aria-label="title" class="card w-full bg-base-850 shadow-2xl" :class="wide ? 'max-w-2xl' : 'max-w-md'">
      <header class="border-b border-base-800 px-5 py-3.5">
        <h2 class="font-semibold">{{ title }}</h2>
      </header>
      <div class="px-5 py-4">
        <slot />
      </div>
      <footer class="flex justify-end gap-2 border-t border-base-800 px-5 py-3">
        <slot name="actions" />
      </footer>
    </section>
  </div>
</template>
