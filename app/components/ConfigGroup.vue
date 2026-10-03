<script setup lang="ts">
import type { ConfigGroup } from '~/utils/config/types'
import { configEditorKey } from '~/utils/config/ui'

// Aufklappbare Gruppe (Abschnitt/Kategorie/Objekt) im Einfach-Modus; rekursiv.
const props = defineProps<{ group: ConfigGroup; depth: number }>()
const ctx = inject(configEditorKey)!

const children = computed(() => props.group.children.filter((c) => ctx.visible(c.type === 'entry' ? c.entry.id : c.group.id)))
const isOpen = computed(() => ctx.open(props.group.id))
const count = computed(() => {
  let n = 0
  const walk = (g: ConfigGroup) => {
    for (const c of g.children) {
      if (c.type === 'entry') n += ctx.changed(c.entry) ? 1 : 0
      else walk(c.group)
    }
  }
  walk(props.group)
  return n
})
</script>

<template>
  <section class="cfg-group" :class="depth ? 'ml-3 border-l border-base-800' : ''">
    <button type="button" class="cfg-head" :aria-expanded="isOpen" @click="ctx.toggle(group.id)">
      <svg viewBox="0 0 24 24" class="size-3.5 shrink-0 text-base-400 transition-transform" :class="{ 'rotate-90': isOpen }" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="m9 6 6 6-6 6" /></svg>
      <span class="min-w-0 truncate font-mono text-[13px] font-semibold text-base-50">{{ group.title }}</span>
      <span v-if="count" class="badge bg-lamp-900 text-lamp-300">{{ t('configEditor.changed', count) }}</span>
      <span v-if="group.help" class="min-w-0 flex-1 truncate text-left text-xs text-base-400" :title="group.help">{{ group.help }}</span>
    </button>
    <div v-if="isOpen" class="pb-1">
      <template v-for="child in children" :key="child.type === 'entry' ? child.entry.id : child.group.id">
        <ConfigField v-if="child.type === 'entry'" :entry="child.entry" />
        <ConfigGroup v-else :group="child.group" :depth="depth + 1" />
      </template>
    </div>
  </section>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.cfg-head {
  @apply flex w-full items-center gap-2 px-3 py-2 text-left transition-colors hover:bg-base-850;
}
.cfg-group + .cfg-group,
:deep(.cfg-row) + .cfg-group {
  @apply border-t border-base-800;
}
</style>
