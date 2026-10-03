<script setup lang="ts">
import { APPLICATIONS_URL, TEAM_PAGE_URL, applicationStatusLabel, jobTitle, type MyApplication } from '~/utils/applications'

// „Meine Bewerbungen“ (API §24.3): Stelle, Status, Antwort des Teams; offene
// Bewerbungen lassen sich zurückziehen. Bewerben, Stellen und Formulare gibt es
// auf der Website (Anmeldung mit Microsoft).
const applications = useApplicationsStore()
const toasts = useToasts()

const confirming = ref<string | null>(null)
const working = ref(false)

function close() {
  applications.dialogOpen = false
  applications.focusId = null
}

function tone(a: MyApplication): string {
  switch (a.status) {
    case 'accepted':
      return 'bg-ok/15 text-ok'
    case 'rejected':
      return 'bg-redstone-900/60 text-redstone-300'
    case 'withdrawn':
      return 'bg-base-800 text-base-400'
    case 'interview':
      return 'bg-lamp-900 text-lamp-300'
    default:
      return 'bg-base-800 text-base-200'
  }
}

async function withdraw(a: MyApplication) {
  working.value = true
  try {
    await applications.withdraw(a.id)
    confirming.value = null
    toasts.ok(t('applications.dialog.withdrawn', { job: jobTitle(a) }))
  } catch (e) {
    toasts.error(e)
  } finally {
    working.value = false
  }
}

function openSite(url: string) {
  void backend.openExternalUrl(url).catch((e) => toasts.error(e))
}

function onKey(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  if (confirming.value) confirming.value = null
  else close()
}

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  await applications.load()
  const focus = applications.focusId
  if (focus) void nextTick(() => document.querySelector(`[data-application="${focus}"]`)?.scrollIntoView({ block: 'center' }))
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
// Handy: Bottom-Sheet (nach unten wegwischen), Zurück-Taste schließt.
const mobile = mobileUi
useOverlay(close)
const swipe = useSheetSwipe(close)
/** Wischen nur am Kopf selbst, nicht auf seinen Knöpfen (sonst kommt deren Klick nicht an). */
const swipeOn = {
  ...swipe.handlers,
  pointerdown: (e: PointerEvent) => {
    if (!(e.target as HTMLElement | null)?.closest('button')) swipe.handlers.pointerdown(e)
  },
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[55] flex items-center justify-center bg-black/60 p-6 mobile:items-end mobile:p-0 mobile:pt-[calc(var(--safe-top)+1.5rem)]" @mousedown.self="close">
      <section
        role="dialog"
        aria-modal="true"
        :aria-label="t('applications.dialog.title')"
        class="card flex max-h-full w-full max-w-2xl flex-col bg-base-850 shadow-2xl mobile:max-w-none mobile:animate-sheet mobile:rounded-t-2xl mobile:rounded-b-none mobile:transition-transform"
        :style="swipe.style.value"
        data-testid="my-applications"
      >
        <header class="flex items-center gap-3 border-b border-base-800 px-5 py-3.5 mobile:relative mobile:touch-none mobile:pt-5" v-on="mobile ? swipeOn : {}">
          <span v-if="mobile" class="absolute top-2 left-1/2 h-1 w-10 -translate-x-1/2 rounded-full bg-base-700" aria-hidden="true" />
          <SocialIcon name="mailUnread" class="size-5 text-redstone-300" />
          <div class="min-w-0 flex-1">
            <h2 class="font-semibold">{{ t('applications.dialog.title') }}</h2>
            <p class="text-xs text-base-400">{{ t('applications.dialog.subtitle') }}</p>
          </div>
          <button class="btn-icon size-8" :aria-label="t('common.actions.close')" @click="close"><SocialIcon name="close" class="size-4" /></button>
        </header>

        <div class="min-h-0 flex-1 overflow-y-auto px-5 py-4">
          <div v-if="applications.loading && !applications.loaded" class="space-y-2"><div v-for="i in 2" :key="i" class="skeleton h-20" /></div>
          <p v-else-if="applications.error && !applications.loaded" role="alert" class="py-6 text-center text-sm text-redstone-300">{{ applications.error }}</p>
          <div v-else-if="!applications.list.length" class="py-8 text-center">
            <p class="text-sm text-base-200">{{ t('applications.dialog.none') }}</p>
            <p class="mt-1 text-xs text-base-400">{{ t('applications.dialog.noneHint') }}</p>
          </div>
          <ul v-else class="space-y-3">
            <li
              v-for="a in applications.list"
              :key="a.id"
              :data-application="a.id"
              class="rounded-xl border bg-base-900 p-4"
              :class="a.id === applications.focusId ? 'border-redstone-600/60' : 'border-base-800'"
            >
              <div class="flex flex-wrap items-center gap-2">
                <span class="text-sm font-semibold text-base-50">{{ jobTitle(a) }}</span>
                <span class="badge" :class="tone(a)" data-testid="application-status">{{ applicationStatusLabel(a.status) }}</span>
                <span class="ml-auto text-[11px] text-base-600">{{ t('applications.dialog.sent', { date: formatDate(a.createdAt) }) }}</span>
              </div>
              <p class="mt-1 text-xs text-base-400">{{ t(`applications.statusHelp.${a.status}`) }}</p>
              <div v-if="a.response" class="mt-3 rounded-lg border border-base-800 bg-base-850 px-3 py-2 text-xs">
                <span class="block text-[11px] text-base-400">{{ t('applications.dialog.answer', { date: formatDate(a.decidedAt ?? a.updatedAt) }) }}</span>
                <p class="mt-1 whitespace-pre-wrap text-base-100">{{ a.response }}</p>
              </div>
              <div v-if="a.canWithdraw" class="mt-3 flex items-center justify-end gap-2">
                <template v-if="confirming === a.id">
                  <span class="mr-auto text-xs text-base-300">{{ t('applications.dialog.withdrawConfirm') }}</span>
                  <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="working" @click="confirming = null">{{ t('common.actions.cancel') }}</button>
                  <button class="btn btn-danger px-3 py-1.5 text-xs" :disabled="working" data-testid="application-withdraw-confirm" @click="withdraw(a)">
                    {{ t('applications.dialog.withdraw') }}
                  </button>
                </template>
                <button v-else class="btn btn-ghost px-3 py-1.5 text-xs" data-testid="application-withdraw" @click="confirming = a.id">
                  {{ t('applications.dialog.withdraw') }}
                </button>
              </div>
            </li>
          </ul>
        </div>

        <footer class="mobile:pb-[calc(0.75rem+var(--safe-bottom))] flex flex-wrap items-center gap-2 border-t border-base-800 px-5 py-3">
          <p class="mr-auto text-[11px] text-base-400">{{ t('applications.dialog.footer') }}</p>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="openSite(TEAM_PAGE_URL)">
            {{ t('applications.dialog.jobs') }} <SocialIcon name="external" class="size-3.5" />
          </button>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="openSite(APPLICATIONS_URL)">
            {{ t('applications.dialog.website') }} <SocialIcon name="external" class="size-3.5" />
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>
