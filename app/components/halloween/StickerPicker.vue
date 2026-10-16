<script setup lang="ts">
// Sticker-Auswahl im Chat (Halloween 2026): eigene Sticker zum Senden, fremde ausgegraut
// mit Hinweis, wie man sie bekommt (Süßes oder Saures).
const emit = defineEmits<{ pick: [id: string]; close: [] }>()
const halloween = useHalloweenStore()
const loading = ref(true)
onMounted(async () => {
  await halloween.loadStickers(true)
  loading.value = false
})
const list = computed(() => halloween.stickers ?? [])
const owned = computed(() => list.value.filter((s) => s.owned))

const root = useTemplateRef<HTMLElement>('root')
function onDoc(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) emit('close')
}
onMounted(() => setTimeout(() => document.addEventListener('mousedown', onDoc), 0))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDoc))
</script>

<template>
  <div ref="root" class="menu bottom-12 left-0 w-72 p-2" role="dialog" :aria-label="t('halloween.stickers.title')" data-testid="sticker-picker">
    <p class="px-1 pb-1.5 text-xs font-semibold text-base-200">{{ t('halloween.stickers.title') }}</p>
    <div v-if="loading" class="skeleton h-16" />
    <p v-else-if="!list.length" class="px-1 py-2 text-xs text-base-400">{{ t('halloween.stickers.none') }}</p>
    <div v-else class="grid grid-cols-4 gap-1.5">
      <button
        v-for="s in list"
        :key="s.id"
        type="button"
        class="grid place-items-center rounded-md p-1 transition-colors hover:bg-base-700 disabled:cursor-not-allowed disabled:opacity-35 disabled:hover:bg-transparent"
        :disabled="!s.owned"
        :title="s.owned ? localText(s.name, currentLocale) : t('halloween.stickers.locked', { name: localText(s.name, currentLocale) })"
        :data-testid="`sticker-pick-${s.id}`"
        @click="emit('pick', s.id)"
      >
        <HalloweenSticker :id="s.id" :size="52" />
      </button>
    </div>
    <p v-if="!loading && list.length && !owned.length" class="px-1 pt-2 text-[11px] text-base-400">{{ t('halloween.stickers.howTo') }}</p>
  </div>
</template>
