<script setup lang="ts">
// Modaler Dialog des Team-Bereichs: Fokus-Falle, Esc schließt, Klick auf den Hintergrund schließt,
// Fokus kehrt danach zurück. Breite über `size`.
const props = withDefaults(defineProps<{ title: string, size?: 'sm' | 'md' | 'lg' | 'xl', kicker?: string }>(), { size: 'md', kicker: '' })
const emit = defineEmits<{ close: [] }>()
const { a } = useAdminText()

const panel = shallowRef<HTMLElement | null>(null)
const id = `adm-dlg-${Math.random().toString(36).slice(2, 8)}`
let returnFocus: HTMLElement | null = null
let overflow = ''

function onKey(e: KeyboardEvent) {
  if (e.key !== 'Escape' || e.defaultPrevented) return
  // Nur der oberste Dialog reagiert.
  const all = document.querySelectorAll('[aria-modal="true"]')
  if (all[all.length - 1] !== panel.value) return
  e.preventDefault()
  emit('close')
}
function trapTab(e: KeyboardEvent) {
  if (e.key !== 'Tab' || !panel.value) return
  const items = [...panel.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
  )].filter((el) => el.offsetParent !== null)
  if (!items.length) return
  const first = items[0]!
  const last = items[items.length - 1]!
  if (e.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault()
    first.focus()
  }
}
onMounted(() => {
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  overflow = document.documentElement.style.overflow
  document.documentElement.style.overflow = 'hidden'
  window.addEventListener('keydown', onKey)
  void nextTick(() => {
    const auto = panel.value?.querySelector<HTMLElement>('[autofocus]')
    ;(auto ?? panel.value)?.focus()
  })
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  // Verschachtelt: der innere Dialog hat „hidden“ gemerkt und stellt es wieder her.
  document.documentElement.style.overflow = overflow
  if (returnFocus && document.contains(returnFocus)) returnFocus.focus()
})
const width = computed(() => ({ sm: 'max-w-md', md: 'max-w-xl', lg: 'max-w-3xl', xl: 'max-w-6xl' })[props.size])
</script>

<template>
  <Teleport to="body">
    <div class="adm-backdrop" @mousedown.self="emit('close')">
      <div
        ref="panel"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="id"
        tabindex="-1"
        class="adm-dialog card w-full animate-pop p-5 sm:p-6"
        :class="width"
        @keydown="trapTab"
      >
        <header class="flex items-start gap-3">
          <div class="min-w-0 flex-1">
            <p v-if="kicker" class="text-[11px] tracking-[0.16em] text-base-400 uppercase">{{ kicker }}</p>
            <h2 :id="id" class="display mt-0.5 text-2xl leading-tight text-base-50">{{ title }}</h2>
          </div>
          <slot name="head" />
          <button type="button" class="btn-icon" :aria-label="a.common.close" :title="`${a.common.close} (Esc)`" @click="emit('close')">
            <SiteIcon name="close" class="size-4" />
          </button>
        </header>
        <div class="mt-4">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="mt-6 flex flex-wrap justify-end gap-2 border-t border-base-800 pt-4">
          <slot name="footer" />
        </footer>
      </div>
    </div>
  </Teleport>
</template>
