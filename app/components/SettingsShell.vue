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
watch(active, () => content.value?.scrollTo({ top: 0 }))

function onKey(e: KeyboardEvent) {
  // Ein offener Dialog darüber (z. B. Bestätigung) schließt zuerst sich selbst.
  if (e.key === 'Escape' && !document.querySelector('[data-dialog-over-settings]')) emit('close')
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-6 backdrop-blur-[2px]" @mousedown.self="emit('close')">
    <section
      role="dialog"
      aria-modal="true"
      :aria-label="title"
      class="flex h-[min(720px,90vh)] w-[min(1000px,94vw)] animate-pop flex-col overflow-hidden rounded-2xl border border-base-800 bg-base-900 shadow-2xl shadow-black/50"
    >
      <header class="flex items-center gap-3 border-b border-base-800 px-6 py-4">
        <slot name="title-icon" />
        <h2 class="min-w-0 flex-1 truncate text-lg font-semibold">{{ title }}</h2>
        <span v-if="status" role="status" class="text-xs" :class="status.ok ? 'text-base-400' : 'text-redstone-300'">{{ status.text }}</span>
        <button class="grid size-9 place-items-center rounded-full bg-base-800 text-base-200 transition-colors hover:bg-base-700 hover:text-base-50" :aria-label="t('common.actions.close')" @click="emit('close')">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path :d="icons.close" /></svg>
        </button>
      </header>

      <div class="flex min-h-0 flex-1">
        <nav class="flex w-64 shrink-0 flex-col overflow-y-auto border-r border-base-800 p-3" :aria-label="t('settingsShell.sectionsLabel')">
          <div v-for="(g, i) in grouped" :key="i" :class="{ 'mt-4': i > 0 && g.group }">
            <p v-if="g.group" class="mb-1.5 px-3 text-[11px] font-semibold tracking-wider text-base-600 uppercase">{{ g.group }}</p>
            <button
              v-for="s in g.items"
              :key="s.key"
              class="mb-0.5 flex w-full items-center gap-2.5 rounded-full px-3 py-2 text-left text-sm font-medium transition-colors"
              :class="active === s.key ? 'bg-redstone-500 text-white' : 'text-base-400 hover:bg-base-800 hover:text-base-50'"
              :aria-current="active === s.key ? 'page' : undefined"
              @click="active = s.key"
            >
              <svg viewBox="0 0 24 24" class="size-4 shrink-0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="icons[s.icon]" /></svg>
              <span class="min-w-0 leading-tight">{{ s.label }}</span>
            </button>
          </div>
          <div class="mt-auto pt-4 px-3 text-[11px] leading-5 text-base-600">
            <slot name="nav-footer" />
          </div>
        </nav>

        <div ref="content" class="min-w-0 flex-1 overflow-y-auto px-7 py-5">
          <slot />
        </div>
      </div>
    </section>
  </div>
</template>
