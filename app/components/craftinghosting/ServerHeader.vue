<script setup lang="ts">
import {
  ADDRESS_VIEWS,
  ADDRESS_VIEW_KEY,
  type AddressView,
  NAME_MAX,
  addressOptions,
  hostingBlockedReason,
  hostingCountdown,
  inCooldown,
  isTransitional,
  remainingSeconds,
  runtimePercent,
  secondsUntil,
  stateTone,
  validServerName,
} from '~/utils/craftinghosting'

// Kopfkarte des eigenen Servers: Zustand (Farbe), Name, Adresse, Spieler, Laufzeit, Aktionen.
const emit = defineEmits<{ delete: []; address: [] }>()

const store = useCraftingHostingStore()
const server = computed(() => store.server!)
const info = computed(() => store.info)
const mobile = computed(() => platformCaps.value.platform === 'android' || platformCaps.value.platform === 'ios')

const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null
onMounted(() => (ticker = setInterval(() => (now.value = Date.now()), 1000)))
onUnmounted(() => {
  if (ticker) clearInterval(ticker)
})

const tone = computed(() => stateTone(server.value))
const restarting = computed(() => server.value.setup === 'restarting')
const stateLabel = computed(() => (restarting.value ? t('craftinghosting.state.setup') : t(`craftinghosting.state.${server.value.state}`)))
const typeName = computed(() => info.value?.serverTypes.find((ty) => ty.id === server.value.serverType)?.name ?? server.value.serverType)
const versionLabel = computed(() => (server.value.version === 'latest' ? t('craftinghosting.latest') : server.value.version))
// Adresse: TRS-Adresse vorne, wahlweise IP & Port oder CraftingHosting-Adresse (Wahl bleibt lokal).
function readView(): AddressView {
  try {
    return ADDRESS_VIEWS.find((v) => v === localStorage.getItem(ADDRESS_VIEW_KEY)) ?? 'domain'
  } catch {
    return 'domain'
  }
}
const view = ref<AddressView>(readView())
const options = computed(() => addressOptions(server.value))
const shown = computed(() => options.value.find((o) => o.view === view.value) ?? options.value[0] ?? null)
const address = computed(() => shown.value?.value ?? null)
function setView(next: AddressView) {
  view.value = next
  try {
    localStorage.setItem(ADDRESS_VIEW_KEY, next)
  } catch {
    // nur für diese Sitzung
  }
}

const cooling = computed(() => inCooldown(server.value, now.value))
const cooldownLeft = computed(() => secondsUntil(server.value.session.cooldownUntil, now.value))
const cooldownPercent = computed(() => {
  const total = (info.value?.limits.cooldownHours ?? 2) * 3600
  return total ? Math.min(100, Math.max(0, ((total - cooldownLeft.value) / total) * 100)) : 0
})
const runLeft = computed(() => remainingSeconds(server.value, store.loadedAt, now.value))
// Anzeige von der API (§43.18): Texte/Regeln ändern ohne Launcher-Update.
const display = computed(() => server.value.display ?? null)
const shownText = (text: Localized) => fillDisplay(pickText(text, currentLocale.value), now.value - store.loadedAt, intlLocale())
const dateFormat = computed(() => new Intl.DateTimeFormat(intlLocale(), { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }))
function formatDate(iso: string) {
  return dateFormat.value.format(new Date(iso))
}
const runPercent = computed(() => runtimePercent(server.value, store.loadedAt, now.value))
const players = computed(() => store.stats?.players ?? null)

// Wartung oder vom Team gesperrt: kein Start, kein Beitreten.
const teamOff = computed(() => !!hostingBlockedReason(info.value, server.value))
const canStart = computed(() => !mobile.value && !cooling.value && !teamOff.value && (server.value.state === 'offline' || server.value.state === 'failed'))
const canStop = computed(() => server.value.state === 'running' || server.value.state === 'starting')
const canJoin = computed(() => !mobile.value || platformCaps.value.gameLaunch)
const joinDisabled = computed(
  () =>
    store.joining ||
    teamOff.value ||
    !!store.busy ||
    !!store.transfer?.active ||
    cooling.value ||
    ['installing', 'stopping', 'suspended'].includes(server.value.state),
)

// Kopieren
const copied = ref<string | null>(null)
async function copy(text: string | null) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copied.value = text
    setTimeout(() => (copied.value = null), 1500)
  } catch {
    useToasts().error(t('errors.unexpected'))
  }
}

// Umbenennen direkt am Titel
const renaming = ref(false)
const newName = ref('')
const nameInput = useTemplateRef<HTMLInputElement>('nameInput')
async function startRename() {
  menu.value = false
  newName.value = server.value.name
  renaming.value = true
  await nextTick()
  nameInput.value?.select()
}
async function saveName() {
  if (!validServerName(newName.value)) return
  if (await store.rename(newName.value)) renaming.value = false
}

// „⋯“-Menü
const menu = ref(false)
const menuButton = useTemplateRef<HTMLButtonElement>('menuButton')
function closeMenu(e: MouseEvent) {
  if (!(e.target as HTMLElement | null)?.closest('[data-hosting-menu]')) menu.value = false
}
function onMenuKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    menu.value = false
    menuButton.value?.focus()
  }
}
onMounted(() => document.addEventListener('mousedown', closeMenu))
onBeforeUnmount(() => document.removeEventListener('mousedown', closeMenu))
function pick(action: () => void) {
  menu.value = false
  action()
}
</script>

<template>
  <section class="ch-head card relative overflow-hidden p-5 mobile:p-4" :data-tone="tone" data-testid="hosting-server-head" :aria-label="server.name">
    <span class="ch-strip" aria-hidden="true" />
    <span class="ch-glow" aria-hidden="true" />

    <div class="relative flex items-start gap-3">
      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-2">
          <span class="ch-badge badge" data-testid="hosting-state">
            <span class="size-1.5 rounded-full bg-current" :class="{ 'animate-lamp': isTransitional(server.state) || restarting || server.state === 'running' }" />
            {{ stateLabel }}
          </span>
          <span class="chip gap-1.5 py-0.5 text-[11px]">
            <CraftinghostingTypeIcon :type="server.serverType" class="size-3.5" />{{ typeName }} · <span class="font-mono">{{ versionLabel }}</span>
          </span>
          <span class="chip py-0.5 text-[11px]">{{ t(`craftinghosting.access.${server.access}`) }}</span>
        </div>

        <form v-if="renaming" class="mt-3 flex flex-wrap items-center gap-2" @submit.prevent="saveName">
          <input
            ref="nameInput"
            v-model="newName"
            class="field max-w-72 text-base"
            :maxlength="NAME_MAX"
            :aria-label="t('craftinghosting.create.name')"
            autocomplete="off"
            spellcheck="false"
            @keydown.esc="renaming = false"
          />
          <button type="submit" class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!validServerName(newName) || !!store.busy">{{ t('common.actions.save') }}</button>
          <button type="button" class="btn btn-ghost px-3 py-1.5 text-xs" @click="renaming = false">{{ t('common.actions.cancel') }}</button>
        </form>
        <div v-else class="group mt-2 flex min-w-0 items-center gap-2">
          <h2 class="display truncate text-4xl leading-tight text-base-50 mobile:text-2xl">{{ server.name }}</h2>
          <button
            class="btn-icon size-8 bg-transparent text-base-400 opacity-0 group-hover:opacity-100 focus-visible:opacity-100 mobile:opacity-100"
            :aria-label="t('craftinghosting.rename')"
            :title="t('craftinghosting.rename')"
            data-testid="hosting-rename"
            @click="startRename"
          >
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h4L19 9l-4-4L4 16z" /><path d="m13.5 6.5 4 4" /></svg>
          </button>
        </div>

        <!-- Adresse -->
        <div class="mt-3">
          <div v-if="address" class="flex flex-wrap items-center gap-2">
            <code class="rounded-lg bg-base-950/70 px-3 py-1.5 font-mono text-lg text-base-50 ring-1 ring-base-800 select-all mobile:text-sm" data-testid="hosting-address">{{ address }}</code>
            <button class="btn btn-ghost px-2.5 py-1.5 text-xs" data-testid="hosting-copy" @click="copy(address)">
              <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2" /><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3" /></svg>
              {{ copied === address ? t('common.status.copied') : t('common.actions.copy') }}
            </button>
          </div>
          <p v-else class="text-sm text-base-400">{{ t('craftinghosting.noAddress') }}</p>
          <div v-if="options.length > 1" class="mt-2 inline-flex rounded-full bg-base-950/70 p-0.5 ring-1 ring-base-800" role="radiogroup" :aria-label="t('craftinghosting.address.view')" data-testid="hosting-address-view">
            <button
              v-for="o in options"
              :key="o.view"
              class="seg rounded-full px-2.5 py-1 text-[11px]"
              :class="{ 'seg-on': shown?.view === o.view }"
              role="radio"
              :aria-checked="shown?.view === o.view"
              :data-testid="`hosting-address-view-${o.view}`"
              @click="setView(o.view)"
            >
              {{ t(`craftinghosting.address.views.${o.view}`) }}
            </button>
          </div>
        </div>
      </div>

      <!-- ⋯ -->
      <div class="relative shrink-0" data-hosting-menu @keydown="onMenuKey">
        <button
          ref="menuButton"
          class="btn-icon"
          :aria-label="t('craftinghosting.header.more')"
          :aria-expanded="menu"
          aria-haspopup="menu"
          data-testid="hosting-menu"
          @click="menu = !menu"
        >
          <svg viewBox="0 0 24 24" class="size-4" fill="currentColor" aria-hidden="true"><circle cx="5.5" cy="12" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="18.5" cy="12" r="1.7" /></svg>
        </button>
        <div v-if="menu" class="menu top-11 right-0" role="menu">
          <button class="menu-item" role="menuitem" @click="startRename">{{ t('craftinghosting.rename') }}</button>
          <button class="menu-item" role="menuitem" @click="pick(() => emit('address'))">{{ t('craftinghosting.address.change') }}</button>
          <button class="menu-item text-redstone-300" role="menuitem" :disabled="!!store.busy" data-testid="hosting-delete" @click="pick(() => emit('delete'))">
            {{ t('common.actions.delete') }}
          </button>
        </div>
      </div>
    </div>

    <!-- Spieler, Laufzeit, Aktionen -->
    <div class="relative mt-5 grid gap-4 border-t border-white/5 pt-4 md:grid-cols-[minmax(0,1fr)_auto] md:items-end">
      <div class="grid gap-4 sm:grid-cols-[auto_minmax(0,1fr)] sm:items-end">
        <div class="min-w-28" data-testid="hosting-players">
          <p class="text-[11px] font-semibold tracking-wide text-base-400 uppercase">{{ t('craftinghosting.stats.players') }}</p>
          <p class="mt-0.5 text-2xl font-semibold text-base-50 tabular-nums">
            <template v-if="players">{{ players.online }}<span class="text-base text-base-400"> / {{ players.max }}</span></template>
            <template v-else>–</template>
          </p>
        </div>
        <div v-if="display?.runtime" class="min-w-0" data-testid="hosting-runtime">
          <div class="flex items-baseline justify-between gap-2 text-[11px]">
            <span class="font-semibold tracking-wide text-base-400 uppercase">{{ shownText(display.runtime.label) }}</span>
            <span class="tabular-nums" :class="display.runtime.tone === 'cooldown' || display.runtime.tone === 'warn' ? 'text-lamp-300' : 'text-base-200'" data-testid="hosting-runtime-value">{{ shownText(display.runtime.value) }}</span>
          </div>
          <div class="mt-1.5 h-2 overflow-hidden rounded-full bg-base-800" role="progressbar" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="Math.round(display.runtime.progress * 100)" :aria-label="shownText(display.runtime.label)">
            <div class="h-full rounded-full transition-[width] duration-700" :class="display.runtime.tone === 'cooldown' ? 'bg-lamp-400' : 'ch-bar'" :style="{ width: `${display.runtime.progress * 100}%` }" />
          </div>
          <p v-if="display.lines.length" class="mt-1 flex flex-wrap gap-x-3 text-[11px] tabular-nums" data-testid="hosting-display-lines">
            <span v-for="(line, i) in display.lines" :key="i" :class="line.tone === 'warn' ? 'text-lamp-300' : 'text-base-400'">{{ shownText(line.text) }}</span>
          </p>
        </div>
        <div v-else class="min-w-0" data-testid="hosting-runtime">
          <div class="flex items-baseline justify-between gap-2 text-[11px]">
            <span class="font-semibold tracking-wide text-base-400 uppercase">{{ cooling ? t('craftinghosting.header.cooldown') : t('craftinghosting.header.session') }}</span>
            <span v-if="cooling" class="text-lamp-300 tabular-nums" data-testid="hosting-cooldown">{{ t('craftinghosting.header.until', { time: hostingCountdown(cooldownLeft) }) }}</span>
            <span v-else-if="server.session.active" class="text-base-200 tabular-nums">{{ t('craftinghosting.header.left', { time: hostingCountdown(runLeft) }) }}</span>
            <span v-else-if="server.quota" class="text-base-400 tabular-nums" data-testid="hosting-quota-left">{{ t('craftinghosting.header.quotaLeft', { time: hostingCountdown(server.quota.remainingSeconds), hours: Math.round(server.quota.budgetSeconds / 3600) }) }}</span>
            <span v-else class="text-base-400">{{ t('craftinghosting.header.perSession', { hours: info?.limits.sessionMaxHours ?? 24 }) }}</span>
          </div>
          <div
            class="mt-1.5 h-2 overflow-hidden rounded-full bg-base-800"
            role="progressbar"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-valuenow="Math.round(cooling ? cooldownPercent : runPercent)"
            :aria-label="cooling ? t('craftinghosting.header.cooldown') : t('craftinghosting.header.session')"
          >
            <div class="h-full rounded-full transition-[width] duration-700" :class="cooling ? 'bg-lamp-400' : 'ch-bar'" :style="{ width: `${cooling ? cooldownPercent : runPercent}%` }" />
          </div>
          <p v-if="server.quota" class="mt-1 flex flex-wrap gap-x-3 text-[11px] text-base-400 tabular-nums" data-testid="hosting-quota-week">
            <span>{{ t('craftinghosting.header.week', { used: Math.floor(server.quota.weekUsedSeconds / 3600), cap: Math.round(server.quota.weekCapSeconds / 3600) }) }}</span>
            <span v-if="server.quota.idleResetAt && !server.session.active">{{ t('craftinghosting.header.idleReset', { date: formatDate(server.quota.idleResetAt) }) }}</span>
          </p>
        </div>
      </div>
      <div class="flex flex-wrap gap-2">
        <button v-if="canStop" class="btn btn-ghost" :disabled="!!store.busy || !!store.transfer?.active" data-testid="hosting-stop" @click="store.stop()">
          <svg viewBox="0 0 24 24" class="size-4" fill="currentColor" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2" /></svg>
          {{ t('common.actions.stop') }}
        </button>
        <button v-else class="btn btn-ghost" :disabled="!canStart || !!store.busy || !!store.transfer?.active" data-testid="hosting-start" @click="store.requestStart()">
          <svg viewBox="0 0 24 24" class="size-4" fill="currentColor" aria-hidden="true"><path d="M8 5.5v13l10.5-6.5z" /></svg>
          {{ t('common.actions.start') }}
        </button>
        <button v-if="canJoin" class="btn btn-primary px-6 py-2.5 text-base" :disabled="joinDisabled" data-testid="hosting-join" @click="store.join()">
          {{ store.joining ? t('craftinghosting.actions.joining') : t('craftinghosting.actions.join') }}
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.ch-head {
  --tone: var(--color-base-400);
}
.ch-head[data-tone='ok'] {
  --tone: var(--color-ok);
}
.ch-head[data-tone='busy'] {
  --tone: var(--color-lamp-400);
}
.ch-head[data-tone='bad'] {
  --tone: var(--color-redstone-400);
}
.ch-strip {
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  background: var(--tone);
  box-shadow: 0 0 18px 1px color-mix(in srgb, var(--tone) 60%, transparent);
  transition: background-color 0.4s ease;
}
.ch-glow {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(120% 100% at 0% 0%, color-mix(in srgb, var(--tone) 15%, transparent), transparent 55%);
  transition: background 0.4s ease;
}
.ch-badge {
  background: color-mix(in srgb, var(--tone) 16%, transparent);
  color: var(--tone);
}
.ch-bar {
  background: linear-gradient(90deg, color-mix(in srgb, var(--tone) 70%, transparent), var(--tone));
}
</style>
