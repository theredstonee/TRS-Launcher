<script setup lang="ts">
// Anmeldung (§24.1 + §29): zwei gleichwertige Wege als Karten – „TRS Launcher“ (Code-Abgleich: die Website zeigt einen
// Code, der Launcher bestätigt ihn mit seinem Konto) und „Microsoft“ (Weiterleitung, kein Skript von Microsoft).
// Fehler kommen als ?error=<code> zurück; ?return=/pfad = Rücksprung nach der Anmeldung (prüft der Server).
const { t, fill } = useTeamText()
const { l } = useLoginText()
const lp = useLocalePath()
const route = useRoute()
const { account, loaded, load } = useAccount()

usePageSeo(() => ({ path: '/login', title: t.value.seo.login.title, description: l.value.lead.slice(0, 160), noindex: true }))

const returnTo = computed(() => {
  const r = route.query.return
  return typeof r === 'string' && /^\/(?![/\\])/.test(r) && r.length <= 200 ? r : '/applications'
})
const errorCode = computed(() => (typeof route.query.error === 'string' ? route.query.error : ''))
const errorText = computed(() => (errorCode.value ? t.value.login.errors[errorCode.value] ?? t.value.login.errors.ms_failed : ''))
const msEnabled = ref(true)
onMounted(async () => {
  void load()
  try {
    msEnabled.value = (await apiFetch<{ microsoft: boolean }>('/v1/web/login')).microsoft
  } catch {
    // Karte bleibt aktiv; der Server leitet notfalls mit ms_disabled zurück.
  }
})
const msHref = computed(() => `/auth/microsoft/login?return=${encodeURIComponent(returnTo.value)}`)

// --- Anmeldung per TRS Launcher --------------------------------------------------------------------------
type Step = 'choose' | 'wait' | 'expired' | 'denied' | 'done'
interface Started { token: string, code: string, link: string, expiresIn: number, pollMs: number }
const step = ref<Step>('choose')
const starting = ref(false)
const startError = ref('')
const request = ref<Started | null>(null)
const deniedBanned = ref(false)
const copied = ref(false)
/** Restzeit in Sekunden (lokal gezählt ab der Antwort des Servers – unabhängig von der Uhr des Rechners). */
const left = ref(0)
let deadline = 0
let pollTimer: ReturnType<typeof setTimeout> | null = null
let tickTimer: ReturnType<typeof setInterval> | null = null

function stopTimers() {
  if (pollTimer) clearTimeout(pollTimer)
  if (tickTimer) clearInterval(tickTimer)
  pollTimer = null
  tickTimer = null
}
onBeforeUnmount(stopTimers)

const leftText = computed(() => `${Math.floor(left.value / 60)}:${String(left.value % 60).padStart(2, '0')}`)
const leftShare = computed(() => (request.value ? Math.max(0, Math.min(1, left.value / request.value.expiresIn)) : 0))

function openLauncher() {
  if (!request.value) return
  // Öffnet den Launcher (läuft er schon, reicht das System den Link an das offene Fenster weiter).
  window.location.assign(request.value.link)
}

async function startLauncher() {
  if (starting.value) return
  starting.value = true
  startError.value = ''
  try {
    const r = await apiFetch<Started>('/v1/web/launcher-login', { method: 'POST', body: { return: returnTo.value }, credentials: 'same-origin' })
    request.value = r
    deniedBanned.value = false
    step.value = 'wait'
    deadline = Date.now() + r.expiresIn * 1000
    left.value = r.expiresIn
    stopTimers()
    tickTimer = setInterval(() => {
      left.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
      if (left.value === 0) {
        stopTimers()
        step.value = 'expired'
      }
    }, 250)
    pollTimer = setTimeout(poll, r.pollMs)
    openLauncher()
  } catch (e) {
    const code = apiCode(e)
    startError.value = l.value.errors[code === 'rate_limited' ? 'rate_limited' : code === 'busy' ? 'busy' : 'failed'] ?? l.value.errors.failed!
  } finally {
    starting.value = false
  }
}

async function poll() {
  const r = request.value
  if (!r || step.value !== 'wait') return
  try {
    const res = await apiFetch<{ status: 'pending' | 'expired' | 'denied' | 'approved', reason?: string, returnTo?: string }>(
      '/v1/web/launcher-login/poll',
      { method: 'POST', body: { token: r.token }, credentials: 'same-origin' },
    )
    if (step.value !== 'wait' || request.value !== r) return
    if (res.status === 'approved') {
      stopTimers()
      step.value = 'done'
      // Neu laden, damit Kopfzeile und Seiten die frische Sitzung sehen.
      window.location.assign(res.returnTo ?? returnTo.value)
      return
    }
    if (res.status === 'denied') {
      stopTimers()
      deniedBanned.value = res.reason === 'banned'
      step.value = 'denied'
      return
    }
    if (res.status === 'expired') {
      stopTimers()
      step.value = 'expired'
      return
    }
  } catch {
    // Netzwerk hakt kurz – einfach weiter fragen, bis die Zeit um ist.
  }
  pollTimer = setTimeout(poll, r.pollMs)
}

async function back() {
  const r = request.value
  stopTimers()
  request.value = null
  step.value = 'choose'
  if (r) {
    await apiFetch('/v1/web/launcher-login/cancel', { method: 'POST', body: { token: r.token }, credentials: 'same-origin' }).catch(() => {})
  }
}

async function copyCode() {
  if (!request.value) return
  try {
    await navigator.clipboard.writeText(request.value.code)
    copied.value = true
    setTimeout(() => (copied.value = false), 1600)
  } catch {
    // Kein Zugriff auf die Zwischenablage – der Code ist markierbar.
  }
}
</script>

<template>
  <div class="mx-auto max-w-3xl px-4 pt-14 pb-6 sm:px-6">
    <p class="text-xs tracking-[0.18em] text-redstone-300 uppercase">TRS Launcher</p>
    <h1 class="display mt-2 text-4xl text-base-50 sm:text-5xl">{{ l.title }}</h1>
    <p class="mt-3 max-w-2xl text-base-400">{{ l.lead }}</p>

    <p v-if="errorText && step === 'choose'" role="alert" class="alert mt-6">
      <SiteIcon name="warn" class="mt-0.5 size-4 shrink-0" />{{ errorText }}
    </p>

    <!-- Schon angemeldet -->
    <div v-if="loaded && account && step === 'choose'" class="card login-card mt-8 flex flex-wrap items-center gap-3 p-6">
      <PlayerHead :uuid="account.uuid" :name="account.name" :skin="account.skin" :size="40" />
      <p class="flex-1 text-sm text-base-200">{{ fill(t.login.signedIn, { name: account.name }) }}</p>
      <NuxtLink :to="returnTo" class="btn btn-primary">{{ t.login.continue }}<SiteIcon name="arrow" class="size-4" /></NuxtLink>
    </div>

    <!-- Zwei gleichwertige Wege -->
    <section v-else-if="step === 'choose'" class="mt-8" :aria-label="l.choose">
      <p v-if="startError" role="alert" class="alert mb-4"><SiteIcon name="warn" class="mt-0.5 size-4 shrink-0" />{{ startError }}</p>
      <div class="grid gap-4 sm:grid-cols-2">
        <button type="button" class="method" data-testid="login-launcher" :disabled="starting" @click="startLauncher">
          <span class="tile">
            <img src="/icon.png" alt="" width="40" height="40" class="size-10 [image-rendering:pixelated]" />
          </span>
          <span class="min-w-0 flex-1 text-left">
            <span class="block text-lg font-semibold text-base-50">{{ l.launcher.title }}</span>
            <span class="mt-0.5 block text-sm text-base-400">{{ starting ? l.starting : l.launcher.sub }}</span>
          </span>
          <SiteIcon name="arrow" class="go size-5 shrink-0" />
        </button>
        <a v-if="msEnabled" :href="msHref" class="method" data-testid="login-microsoft">
          <span class="tile"><MsLogo class="size-9" /></span>
          <span class="min-w-0 flex-1 text-left">
            <span class="block text-lg font-semibold text-base-50">{{ l.microsoft.title }}</span>
            <span class="mt-0.5 block text-sm text-base-400">{{ l.microsoft.sub }}</span>
          </span>
          <SiteIcon name="arrow" class="go size-5 shrink-0" />
        </a>
        <div v-else class="method" aria-disabled="true">
          <span class="tile opacity-50"><MsLogo class="size-9" /></span>
          <span class="min-w-0 flex-1 text-left">
            <span class="block text-lg font-semibold text-base-400">{{ l.microsoft.title }}</span>
            <span class="mt-0.5 block text-sm text-lamp-300">{{ t.login.disabled }}</span>
          </span>
        </div>
      </div>
    </section>

    <!-- Warten auf die Bestätigung im Launcher -->
    <section v-else-if="step === 'wait' || step === 'done'" class="card login-card mt-8 p-6 sm:p-8" aria-live="polite">
      <div class="flex items-start gap-4">
        <span class="hidden sm:block"><span class="tile"><img src="/icon.png" alt="" width="40" height="40" class="size-10 [image-rendering:pixelated]" /></span></span>
        <div class="min-w-0 flex-1">
          <p class="text-xs tracking-[0.14em] text-redstone-300 uppercase">{{ l.wait.kicker }}</p>
          <h2 class="display mt-1 text-3xl text-base-50">{{ l.wait.title }}</h2>
          <p class="mt-2 text-sm text-base-200">{{ l.wait.lead }}</p>
        </div>
      </div>

      <div class="code-box mt-6">
        <p class="text-xs font-medium tracking-wide text-base-400 uppercase">{{ l.wait.code }}</p>
        <p class="code display select-all" data-testid="login-code">{{ request?.code }}</p>
        <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="copyCode">
          <SiteIcon :name="copied ? 'check' : 'copy'" class="size-3.5" />{{ copied ? l.wait.copied : l.wait.copy }}
        </button>
      </div>

      <div class="mt-5 flex items-center gap-3 text-sm text-base-200">
        <span class="spinner" aria-hidden="true" />
        <span class="flex-1">{{ step === 'done' ? l.wait.signedIn : l.wait.waiting }}</span>
        <span class="font-mono text-xs text-base-400 tabular-nums">{{ fill(l.wait.expires, { time: leftText }) }}</span>
      </div>
      <div class="timebar mt-2" aria-hidden="true"><span :style="{ transform: `scaleX(${leftShare})` }" /></div>

      <div class="mt-6 flex flex-wrap gap-2">
        <button type="button" class="btn btn-primary" @click="openLauncher"><SiteIcon name="external" class="size-4" />{{ l.wait.reopen }}</button>
        <button type="button" class="btn btn-ghost" @click="back"><SiteIcon name="back" class="size-4" />{{ l.wait.back }}</button>
      </div>

      <div class="mt-6 grid gap-3 sm:grid-cols-2">
        <div class="hint">
          <p class="font-semibold text-base-50">{{ l.wait.manualTitle }}</p>
          <p class="mt-1">{{ l.wait.manual }}</p>
        </div>
        <div class="hint">
          <p class="font-semibold text-base-50">{{ l.wait.noLauncher }}</p>
          <NuxtLink :to="lp('/download')" class="mt-1 inline-flex items-center gap-1.5 text-redstone-300 hover:underline">
            <SiteIcon name="download" class="size-4" />{{ l.wait.download }}
          </NuxtLink>
        </div>
      </div>
    </section>

    <!-- Abgelaufen / abgelehnt -->
    <section v-else class="card login-card mt-8 p-6 sm:p-8" role="alert">
      <div class="flex items-start gap-3">
        <SiteIcon :name="step === 'expired' ? 'clock' : 'ban'" class="mt-1 size-6 shrink-0 text-lamp-300" />
        <div>
          <h2 class="display text-3xl text-base-50">{{ step === 'expired' ? l.expired.title : l.denied.title }}</h2>
          <p class="mt-2 text-sm text-base-200">{{ step === 'expired' ? l.expired.text : deniedBanned ? l.denied.banned : l.denied.text }}</p>
        </div>
      </div>
      <div class="mt-6 flex flex-wrap gap-2">
        <button type="button" class="btn btn-primary" :disabled="starting" @click="startLauncher"><SiteIcon name="refresh" class="size-4" />{{ l.restart }}</button>
        <button type="button" class="btn btn-ghost" @click="back"><SiteIcon name="back" class="size-4" />{{ l.wait.back }}</button>
      </div>
    </section>

    <p class="mt-6 text-xs text-base-400">
      <SiteIcon name="shield" class="mr-1 inline size-3.5 align-[-2px]" />
      <NuxtLink :to="`${lp('/privacy')}#${t.login.anchor}`" class="hover:text-base-50 hover:underline">{{ t.login.privacy }}</NuxtLink>
    </p>
  </div>
</template>

<style scoped>
.login-card {
  background-image: var(--deepslate);
  background-size: 48px 48px;
}
.alert {
  display: flex;
  gap: 0.5rem;
  border-radius: 0.375rem;
  border: 1px solid color-mix(in srgb, var(--color-redstone-600) 60%, transparent);
  background: color-mix(in srgb, var(--color-redstone-900) 45%, transparent);
  padding: 0.5rem 0.75rem;
  font-size: 0.875rem;
  color: var(--color-base-200);
}
/* Anmelde-Karte: groß, dunkel, Symbol-Kachel links – beide Wege gleich betont. */
.method {
  display: flex;
  align-items: center;
  gap: 1rem;
  min-height: 6.5rem;
  padding: 1.25rem;
  border-radius: 0.75rem;
  border: 1px solid var(--color-base-700);
  background-color: var(--color-base-900);
  background-image: var(--deepslate);
  background-size: 48px 48px;
  text-align: left;
  transition: border-color 0.15s, background-color 0.15s, translate 0.15s;
}
.method:not([aria-disabled='true'], :disabled):hover {
  border-color: color-mix(in srgb, var(--color-redstone-500) 70%, var(--color-base-700));
  background-color: var(--color-base-850);
  translate: 0 -1px;
}
.method:disabled {
  cursor: progress;
  opacity: 0.8;
}
.method .go {
  color: var(--color-base-400);
  transition: translate 0.15s, color 0.15s;
}
.method:hover .go {
  color: var(--color-redstone-300);
  translate: 2px 0;
}
.tile {
  display: grid;
  place-items: center;
  width: 4rem;
  height: 4rem;
  flex-shrink: 0;
  border-radius: 0.625rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-950);
  box-shadow: inset 0 -2px 0 color-mix(in srgb, var(--color-redstone-600) 30%, transparent);
}
.code-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 1.25rem 1rem;
  border-radius: 0.75rem;
  border: 1px dashed color-mix(in srgb, var(--color-redstone-500) 55%, var(--color-base-700));
  background: color-mix(in srgb, var(--color-base-950) 70%, transparent);
}
.code {
  font-size: clamp(2.5rem, 11vw, 4rem);
  line-height: 1.1;
  letter-spacing: 0.12em;
  color: var(--color-base-50);
  text-shadow: 0 0 18px color-mix(in srgb, var(--color-redstone-500) 45%, transparent);
}
.spinner {
  width: 1.1rem;
  height: 1.1rem;
  border-radius: 9999px;
  border: 2px solid var(--color-base-700);
  border-top-color: var(--color-redstone-400);
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    rotate: 360deg;
  }
}
.timebar {
  height: 3px;
  border-radius: 9999px;
  background: var(--color-base-800);
  overflow: hidden;
}
.timebar span {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--color-redstone-600), var(--color-redstone-400));
  transform-origin: left;
  transition: transform 0.25s linear;
}
.hint {
  border-radius: 0.5rem;
  border: 1px solid var(--color-base-800);
  background: color-mix(in srgb, var(--color-base-900) 80%, transparent);
  padding: 0.75rem 0.875rem;
  font-size: 0.8125rem;
  color: var(--color-base-400);
}
@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation-duration: 3s;
  }
  .method,
  .method .go,
  .timebar span {
    transition: none;
  }
}
</style>
