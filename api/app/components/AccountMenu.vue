<script setup lang="ts">
// Konto in der Kopfzeile: „Anmelden“ (→ /login) bzw. Kopf + Name mit Menü (Meine Bewerbungen, Team-Bereich, Abmelden).
// Lädt die Sitzung erst im Browser – das SSR-HTML bleibt für alle gleich (cachebar, keine persönlichen Daten).
const { t } = useTeamText()
const { c } = useCircuitText()
const { account, loaded, load, logout } = useAccount()
const lp = useLocalePath()
const route = useRoute()
const router = useRouter()
const open = ref(false)
const root = shallowRef<HTMLElement | null>(null)

onMounted(() => void load())
watch(() => route.fullPath, () => (open.value = false))

function onDoc(e: MouseEvent) {
  if (open.value && root.value && !root.value.contains(e.target as Node)) open.value = false
}
onMounted(() => document.addEventListener('click', onDoc))
onBeforeUnmount(() => document.removeEventListener('click', onDoc))

const loginLink = computed(() => `${lp('/login')}${lp('/login').includes('?') ? '&' : '?'}return=${encodeURIComponent(route.fullPath)}`)
async function signOut() {
  open.value = false
  await logout().catch(() => {})
  if (route.path.startsWith('/applications')) void router.push(lp('/team'))
}
</script>

<template>
  <div ref="root" class="relative">
    <template v-if="loaded">
      <NuxtLink v-if="!account" :to="loginLink" class="btn btn-ghost px-2.5 sm:px-4" :aria-label="t.account.signIn"><SiteIcon name="user" class="size-4" /><span class="hidden sm:inline">{{ t.account.signIn }}</span></NuxtLink>
      <button v-else type="button" class="account-btn" :aria-expanded="open" :aria-label="t.account.menu" @click.stop="open = !open">
        <PlayerHead :uuid="account.uuid" :name="account.name" :skin="account.skin" :size="26" />
        <span class="hidden max-w-32 truncate text-sm text-base-100 lg:inline">{{ account.name }}</span>
        <SiteIcon name="chevron" class="size-4 text-base-400" />
      </button>
      <div v-if="account && open" class="menu right-0 mt-2 w-56" role="menu">
        <p class="px-3 pt-2 pb-1 text-xs text-base-400">{{ account.name }}</p>
        <NuxtLink :to="lp('/applications')" class="menu-item" role="menuitem"><SiteIcon name="inbox" class="size-4" />{{ t.account.myApplications }}</NuxtLink>
        <NuxtLink :to="lp('/circuits/mine')" class="menu-item" role="menuitem"><SiteIcon name="blocks" class="size-4" />{{ c.list.mine }}</NuxtLink>
        <NuxtLink v-if="account.team" to="/admin" class="menu-item" role="menuitem"><SiteIcon name="key" class="size-4" />{{ t.account.teamArea }}</NuxtLink>
        <button type="button" class="menu-item w-full" role="menuitem" @click="signOut"><SiteIcon name="logout" class="size-4" />{{ t.account.signOut }}</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.account-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  border-radius: 0.5rem;
  padding: 0.3rem 0.5rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
}
.account-btn:hover {
  border-color: var(--color-base-700);
}
.menu {
  position: absolute;
}
</style>
