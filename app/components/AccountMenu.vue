<script setup lang="ts">
// Konto oben rechts in der Titelleiste: Skin-Kopf und Name, im Menü alle
// Accounts zum Wechseln, dazu „Account hinzufügen“ und „Accounts verwalten“.
const accounts = useAccountsStore()
const toasts = useToasts()
const router = useRouter()

const open = ref(false)
const switching = ref<string | null>(null)
const root = useTemplateRef<HTMLElement>('root')
const trigger = useTemplateRef<HTMLButtonElement>('trigger')

async function activate(id: string) {
  if (accounts.active?.id === id) {
    open.value = false
    return
  }
  switching.value = id
  try {
    await accounts.setActive(id)
    open.value = false
  } catch (e) {
    toasts.error(e)
  } finally {
    switching.value = null
  }
}

function go(path: string, query?: Record<string, string>) {
  open.value = false
  router.push({ path, query })
}

function onDocPointer(e: PointerEvent) {
  if (open.value && root.value && !root.value.contains(e.target as Node)) open.value = false
}
function onKey(e: KeyboardEvent) {
  if (open.value && e.key === 'Escape') {
    open.value = false
    trigger.value?.focus()
  }
}
onMounted(() => {
  document.addEventListener('pointerdown', onDocPointer)
  document.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointer)
  document.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div ref="root" class="relative h-full">
    <button
      ref="trigger"
      class="account-trigger"
      :class="{ 'account-open': open }"
      :aria-expanded="open"
      aria-haspopup="menu"
      :aria-label="accounts.active ? `Konto: ${accounts.active.name}` : 'Anmelden'"
      @click="open = !open"
    >
      <span class="relative">
        <SkinHead :skin-url="accounts.active?.skinUrl ?? null" :name="accounts.active?.name ?? '?'" :size="20" />
        <span v-if="accounts.active" class="absolute -right-0.5 -bottom-0.5 size-1.5 bg-ok ring-1 ring-base-900" />
      </span>
      <span class="max-w-36 truncate">{{ accounts.active?.name ?? 'Anmelden' }}</span>
      <svg viewBox="0 0 24 24" class="size-3 text-base-400 transition-transform" :class="{ 'rotate-180': open }" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6" /></svg>
    </button>

    <div v-if="open" class="menu top-full right-0 mt-1 w-64 animate-pop" role="menu" aria-label="Konten">
      <p class="px-2.5 pt-1.5 pb-1 text-[11px] text-base-400">Spielt als</p>
      <template v-if="accounts.items.length">
        <button
          v-for="a in accounts.items"
          :key="a.id"
          class="menu-item gap-3 py-2"
          role="menuitemradio"
          :aria-checked="a.active"
          :disabled="switching !== null"
          @click="activate(a.id)"
        >
          <SkinHead :skin-url="a.skinUrl" :name="a.name" :size="28" />
          <span class="min-w-0 flex-1">
            <span class="block truncate font-medium text-base-50">{{ a.name }}</span>
            <span class="block text-[11px]" :class="a.active ? 'text-ok' : 'text-base-400'">
              {{ a.active ? 'Aktiv' : switching === a.id ? 'Wechsle …' : 'Wechseln' }}
            </span>
          </span>
          <span v-if="a.active" class="size-2 bg-ok shadow-[0_0_6px_var(--color-ok)]" />
        </button>
      </template>
      <p v-else class="px-2.5 pb-2 text-xs text-base-400">Noch kein Account – ohne Anmeldung startet nur der Demo-Modus.</p>

      <div class="my-1 h-px bg-base-700" />
      <button class="menu-item" role="menuitem" @click="go('/accounts', { add: 'browser' })">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M12 5v14M5 12h14" /></svg>
        Account hinzufügen
      </button>
      <button class="menu-item" role="menuitem" @click="go('/accounts')">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="4" /><path d="M4 20c1.5-4 4.5-6 8-6s6.5 2 8 6" /></svg>
        Accounts verwalten
      </button>
    </div>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.account-trigger {
  @apply flex h-full items-center gap-2 px-2.5 text-xs font-medium text-base-200 transition-colors hover:bg-base-800 hover:text-base-50;
}
.account-open {
  @apply bg-base-800 text-base-50;
}
</style>
