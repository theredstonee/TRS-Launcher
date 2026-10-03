<script setup lang="ts">
import type { IconName } from '~/utils/icons'

// Gemeinsamer Rahmen für die globalen und die Instanz-Einstellungen:
// Titel + runder Schließen-Knopf, links eine Navigation mit Gruppen und
// aktiver Pille, rechts eine eigenständig scrollende Inhaltsspalte.
export interface ShellSection {
  key: string
  label: string
  icon: IconName
  group?: string
}

const props = defineProps<{ title: string; sections: ShellSection[]; status?: { ok: boolean; text: string } | null }>()
const active = defineModel<string>({ required: true })
const emit = defineEmits<{ close: [] }>()

const grouped = computed(() => {
  const out: { group: string | undefined; items: ShellSection[] }[] = []
  for (const s of props.sections) {
    const last = out[out.length - 1]
    if (last && last.group === s.group) last.items.push(s)
    else out.push({ group: s.group, items: [s] })
  }
  return out
})

const content = ref<HTMLElement | null>(null)
const nav = ref<HTMLElement | null>(null)
/** Handy: Die Abschnitte liegen in einer wischbaren Leiste – der aktive soll sichtbar sein. */
function revealActive() {
  if (!mobileUi.value) return
  nav.value?.querySelector<HTMLElement>('[aria-current="page"]')?.scrollIntoView({ block: 'nearest', inline: 'center' })
}
watch(active, () => {
  content.value?.scrollTo({ top: 0 })
  void nextTick(revealActive)
})
onMounted(() => void nextTick(revealActive))

function onKey(e: KeyboardEvent) {
  // Ein offener Dialog darüber (z. B. Bestätigung) schließt zuerst sich selbst.
  if (e.key === 'Escape' && !document.querySelector('[data-dialog-over-settings]')) emit('close')
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
// Am Handy: Vollbild, die Abschnitte als wischbare Leiste oben; Zurück-Taste schließt.
useOverlay(() => emit('close'))
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-6 backdrop-blur-[2px] mobile:p-0" @mousedown.self="emit('close')">
    <section
      role="dialog"
      aria-modal="true"
      :aria-label="title"
      class="flex h-[min(720px,90vh)] w-[min(1000px,94vw)] animate-pop flex-col overflow-hidden rounded-2xl border border-base-800 bg-base-900 shadow-2xl shadow-black/50 mobile:h-full mobile:w-full mobile:rounded-none mobile:border-0 mobile:pt-[var(--safe-top)]"
    >
      <header class="flex items-center gap-3 border-b border-base-800 px-6 py-4 mobile:px-4 mobile:py-2.5">
        <slot name="title-icon" />
        <h2 class="min-w-0 flex-1 truncate text-lg font-semibold">{{ title }}</h2>
        <span v-if="status" role="status" class="text-xs" :class="status.ok ? 'text-base-400' : 'text-redstone-300'">{{ status.text }}</span>
        <button class="grid size-9 place-items-center rounded-full bg-base-800 mobile:size-11 text-base-200 transition-colors hover:bg-base-700 hover:text-base-50" :aria-label="t('common.actions.close')" @click="emit('close')">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path :d="icons.close" /></svg>
        </button>
      </header>

      <div class="flex min-h-0 flex-1 mobile:flex-col">
        <nav ref="nav" class="flex w-64 shrink-0 flex-col overflow-y-auto border-r border-base-800 p-3 mobile-scroll-x mobile:w-full mobile:flex-row mobile:gap-1 mobile:overflow-y-hidden mobile:border-r-0 mobile:border-b mobile:px-3 mobile:py-2" :aria-label="t('settingsShell.sectionsLabel')">
          <div v-for="(g, i) in grouped" :key="i" :class="{ 'mt-4': i > 0 && g.group }" class="mobile:mt-0! mobile:flex mobile:shrink-0 mobile:gap-1">
            <p v-if="g.group" class="mobile:hidden mb-1.5 px-3 text-[11px] font-semibold tracking-wider text-base-600 uppercase">{{ g.group }}</p>
            <button
              v-for="s in g.items"
              :key="s.key"
              class="mb-0.5 flex w-full items-center gap-2.5 rounded-full px-3 py-2 text-left text-sm font-medium transition-colors mobile:mb-0 mobile:min-h-10 mobile:w-auto mobile:shrink-0 mobile:whitespace-nowrap"
              :class="active === s.key ? 'bg-redstone-500 text-white' : 'text-base-400 hover:bg-base-800 hover:text-base-50'"
              :aria-current="active === s.key ? 'page' : undefined"
              @click="active = s.key"
            >
              <svg viewBox="0 0 24 24" class="size-4 shrink-0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="icons[s.icon]" /></svg>
              <span class="min-w-0 leading-tight">{{ s.label }}</span>
            </button>
          </div>
          <div class="mt-auto pt-4 px-3 text-[11px] leading-5 text-base-600 mobile:hidden">
            <slot name="nav-footer" />
          </div>
        </nav>

        <div ref="content" class="min-w-0 flex-1 overflow-y-auto px-7 py-5 mobile:px-4 mobile:pb-[calc(1.25rem+var(--safe-bottom))]">
          <slot />
        </div>
      </div>
    </section>
  </div>
</template>
