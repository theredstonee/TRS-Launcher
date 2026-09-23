<script setup lang="ts">
import type { GalleryImage } from '~/types'

// Galerie eines Modrinth-Projekts mit Vollbild-Ansicht (Pfeiltasten, Esc).
const props = defineProps<{ images: GalleryImage[] }>()

const current = ref<number | null>(null)
const image = computed(() => (current.value === null ? null : (props.images[current.value] ?? null)))

function step(delta: number) {
  if (current.value === null || !props.images.length) return
  current.value = (current.value + delta + props.images.length) % props.images.length
}

function onKey(e: KeyboardEvent) {
  if (current.value === null) return
  if (e.key === 'Escape') current.value = null
  else if (e.key === 'ArrowRight') step(1)
  else if (e.key === 'ArrowLeft') step(-1)
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div>
    <p v-if="!images.length" class="card px-4 py-10 text-center text-sm text-base-400">{{ t('project.gallery.empty') }}</p>
    <ul v-else class="grid grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-3">
      <li v-for="(img, i) in images" :key="img.url">
        <button class="group card-hover card block w-full overflow-hidden text-left" @click="current = i">
          <div class="aspect-video overflow-hidden bg-base-850">
            <img :src="img.url" alt="" loading="lazy" referrerpolicy="no-referrer" class="size-full object-cover transition-transform duration-300 group-hover:scale-[1.03]" />
          </div>
          <div v-if="img.title || img.description" class="px-3 py-2">
            <p v-if="img.title" class="truncate text-sm font-medium">{{ img.title }}</p>
            <p v-if="img.description" class="line-clamp-2 text-xs text-base-400">{{ img.description }}</p>
          </div>
        </button>
      </li>
    </ul>

    <Teleport to="body">
      <Transition name="fade">
        <div
          v-if="image"
          class="fixed inset-0 z-[60] flex flex-col items-center justify-center gap-3 bg-black/90 p-10"
          role="dialog"
          aria-modal="true"
          :aria-label="image.title ?? t('project.gallery.image')"
          @mousedown.self="current = null"
        >
          <img :src="image.url" alt="" referrerpolicy="no-referrer" class="max-h-[80vh] max-w-full rounded-lg object-contain shadow-2xl" />
          <div class="max-w-2xl text-center">
            <p v-if="image.title" class="font-medium">{{ image.title }}</p>
            <p v-if="image.description" class="mt-0.5 text-sm text-base-400">{{ image.description }}</p>
            <p class="mt-1 text-xs text-base-600">{{ (current ?? 0) + 1 }} / {{ images.length }}</p>
          </div>
          <button class="btn-icon absolute top-4 right-4" :aria-label="t('common.actions.close')" @click="current = null">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M6 6l12 12M18 6 6 18" /></svg>
          </button>
          <template v-if="images.length > 1">
            <button class="btn-icon absolute top-1/2 left-4 size-11 -translate-y-1/2" :aria-label="t('project.gallery.prev')" @click="step(-1)">
              <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M15 5l-7 7 7 7" /></svg>
            </button>
            <button class="btn-icon absolute top-1/2 right-4 size-11 -translate-y-1/2" :aria-label="t('project.gallery.next')" @click="step(1)">
              <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.2"><path d="m9 5 7 7-7 7" /></svg>
            </button>
          </template>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
