<script setup lang="ts">
// Aufklappbarer Abschnitt der Skins-Seite. Der Zustand (auf/zu) wird je
// Abschnitt im Browser-Speicher gemerkt – nur eine Bequemlichkeit, ohne
// Speicher (privates Fenster) ist einfach alles offen.
const props = withDefaults(
  defineProps<{
    /** Schlüssel zum Merken (eindeutig auf der Seite). */
    id: string
    title: string
    /** Anzahl hinter dem Titel (optional). */
    count?: number | null
  }>(),
  { count: null },
)

const STORE = 'trs.skins.collapsed'

function readCollapsed(): string[] {
  try {
    const raw = localStorage.getItem(STORE)
    const list = raw ? (JSON.parse(raw) as unknown) : []
    return Array.isArray(list) ? list.filter((x): x is string => typeof x === 'string') : []
  } catch {
    return []
  }
}

const open = ref(!readCollapsed().includes(props.id))

function toggle() {
  open.value = !open.value
  try {
    const rest = readCollapsed().filter((id) => id !== props.id)
    localStorage.setItem(STORE, JSON.stringify(open.value ? rest : [...rest, props.id]))
  } catch {
    // Ohne Speicher bleibt es für diese Sitzung so.
  }
}

const bodyId = computed(() => `skin-section-${props.id}`)
</script>

<template>
  <section class="skin-section" :data-section="id">
    <div class="flex flex-wrap items-center gap-x-3 gap-y-1.5 py-1.5">
      <button
        class="group -ml-1 flex min-w-0 items-center gap-1.5 rounded-md px-1 py-0.5 text-left outline-none focus-visible:ring-2 focus-visible:ring-redstone-400"
        :aria-expanded="open"
        :aria-controls="bodyId"
        @click="toggle"
      >
        <svg
          viewBox="0 0 24 24"
          class="size-4 shrink-0 text-base-400 transition-transform duration-200 group-hover:text-base-50"
          :class="{ '-rotate-90': !open }"
          fill="none"
          stroke="currentColor"
          stroke-width="2.4"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="m6 9 6 6 6-6" />
        </svg>
        <h3 class="truncate text-sm font-semibold text-base-50">{{ title }}</h3>
        <span v-if="count != null" class="rounded-full bg-base-800 px-1.5 text-[11px] leading-4 font-medium text-base-400 tabular-nums">{{ count }}</span>
      </button>
      <div class="ml-auto flex flex-wrap items-center gap-2">
        <slot name="aside" />
      </div>
    </div>
    <div v-show="open" :id="bodyId" class="pt-1.5 pb-4">
      <slot />
    </div>
  </section>
</template>
