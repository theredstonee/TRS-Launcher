<script setup lang="ts">
import {
  APPEAL_MAX,
  APPEAL_MIN,
  appealProblem,
  endText,
  kindEffect,
  sanctionIsActive,
  sanctionReasonText,
  type MySanction,
} from '~/utils/sanctions'

// „Meine Strafen“: aktive und vergangene Strafen mit Grund und Ende; gegen jede
// aktive Strafe einmal Einspruch (20–1000 Zeichen), danach Status und Antwort
// des Teams. Geht auch mit gesperrtem Konto (Einspruch-Token im Kern).
const sanctions = useSanctionsStore()
const trs = useTrsStore()
const toasts = useToasts()

const writing = ref<number | null>(null)
const text = ref('')
const error = ref<string | null>(null)
const sending = ref(false)
const tab = ref<'active' | 'past'>('active')

const activeList = computed(() => sanctions.active.filter((s) => sanctionIsActive(s)))
const pastList = computed(() => [...sanctions.past, ...sanctions.active.filter((s) => !sanctionIsActive(s))])
const shown = computed(() => (tab.value === 'active' ? activeList.value : pastList.value))
const count = computed(() => [...text.value.replace(/\r/g, '').trim()].length)

function close() {
  sanctions.dialogOpen = false
  sanctions.focusId = null
}

function startAppeal(s: MySanction) {
  writing.value = s.id
  text.value = ''
  error.value = null
  void nextTick(() => document.querySelector<HTMLTextAreaElement>('[data-testid=appeal-text]')?.focus())
}

async function submit(s: MySanction) {
  const problem = appealProblem(text.value)
  if (problem) {
    error.value = problem
    return
  }
  sending.value = true
  error.value = null
  try {
    await sanctions.appeal(s.id, text.value)
    writing.value = null
    toasts.ok(t('sanctions.appeal.sent'))
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    sending.value = false
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  if (writing.value !== null) writing.value = null
  else close()
}

onMounted(async () => {
  window.addEventListener('keydown', onKey)
  await sanctions.load()
  const focus = sanctions.focusId
  if (focus !== null) {
    const s = sanctions.active.find((x) => x.id === focus)
    if (s?.appealable) startAppeal(s)
    void nextTick(() => document.querySelector(`[data-sanction="${focus}"]`)?.scrollIntoView({ block: 'center' }))
  }
  if (!activeList.value.length && pastList.value.length) tab.value = 'past'
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

function tone(s: MySanction): string {
  if (!sanctionIsActive(s)) return 'border-base-800'
  if (s.kind === 'account_ban') return 'border-redstone-600/60'
  if (s.kind === 'warn') return 'border-lamp-400/40'
  return 'border-base-700'
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[55] flex items-center justify-center bg-black/60 p-6" @mousedown.self="close">
      <section
        role="dialog"
        aria-modal="true"
        :aria-label="t('sanctions.dialog.title')"
        class="card flex max-h-full w-full max-w-2xl flex-col bg-base-850 shadow-2xl"
        data-testid="my-sanctions"
      >
        <header class="flex items-center gap-3 border-b border-base-800 px-5 py-3.5">
          <SocialIcon name="shield" class="size-5 text-redstone-300" />
          <div class="min-w-0 flex-1">
            <h2 class="font-semibold">{{ t('sanctions.dialog.title') }}</h2>
            <p class="text-xs text-base-400">{{ t('sanctions.dialog.subtitle', { name: trs.me?.name ?? '' }) }}</p>
          </div>
          <button class="btn-icon size-8" :aria-label="t('common.actions.close')" @click="close"><SocialIcon name="close" class="size-4" /></button>
        </header>

        <div class="flex gap-1 border-b border-base-800 px-5 py-2 text-xs" role="tablist">
          <button class="tab" :class="{ 'tab-on': tab === 'active' }" role="tab" :aria-selected="tab === 'active'" @click="tab = 'active'">
            {{ t('sanctions.dialog.active') }} <span class="ml-1 tabular-nums text-base-400">{{ activeList.length }}</span>
          </button>
          <button class="tab" :class="{ 'tab-on': tab === 'past' }" role="tab" :aria-selected="tab === 'past'" @click="tab = 'past'">
            {{ t('sanctions.dialog.past') }} <span class="ml-1 tabular-nums text-base-400">{{ pastList.length }}</span>
          </button>
        </div>

        <div class="min-h-0 flex-1 overflow-y-auto px-5 py-4">
          <div v-if="sanctions.loading && !sanctions.loaded" class="space-y-2"><div v-for="i in 2" :key="i" class="skeleton h-24" /></div>
          <div v-else-if="!shown.length" class="py-8 text-center">
            <p class="text-sm text-base-200">{{ tab === 'active' ? t('sanctions.dialog.noneActive') : t('sanctions.dialog.nonePast') }}</p>
            <p v-if="tab === 'active'" class="mt-1 text-xs text-base-400">{{ t('sanctions.dialog.noneHint') }}</p>
          </div>
          <ul v-else class="space-y-3">
            <li v-for="s in shown" :key="s.id" :data-sanction="s.id" class="rounded-xl border bg-base-900 p-4" :class="tone(s)">
              <div class="flex flex-wrap items-center gap-2">
                <SanctionKindBadge :kind="s.kind" />
                <span class="text-sm font-semibold text-base-50">{{ endText(s) }}</span>
                <span class="ml-auto text-[11px] text-base-600">#{{ s.id }}</span>
              </div>
              <p class="mt-2 text-sm text-base-200">{{ kindEffect(s.kind) }}</p>
              <dl class="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
                <dt class="text-base-400">{{ t('sanctions.dialog.reason') }}</dt>
                <dd class="text-base-100">{{ sanctionReasonText(s) }}</dd>
                <dt class="text-base-400">{{ t('sanctions.dialog.since') }}</dt>
                <dd class="text-base-100">{{ formatDate(s.startsAt) }}</dd>
              </dl>

              <!-- Einspruch: Status und Antwort -->
              <div v-if="s.appeal" class="mt-3 rounded-lg border border-base-800 bg-base-850 px-3 py-2 text-xs" data-testid="appeal-state">
                <p class="flex items-center gap-2">
                  <span class="badge" :class="s.appeal.status === 'open' ? 'bg-lamp-900 text-lamp-300' : s.appeal.status === 'upheld' ? 'bg-base-800 text-base-200' : 'bg-ok/15 text-ok'">
                    {{ t(`sanctions.appeal.status.${s.appeal.status}`) }}
                  </span>
                  <span class="text-base-400">{{ t('sanctions.appeal.sentAt', { date: formatDate(s.appeal.createdAt) }) }}</span>
                </p>
                <p v-if="s.appeal.response" class="mt-2 whitespace-pre-wrap text-base-100">
                  <span class="block text-[11px] text-base-400">{{ t('sanctions.appeal.teamAnswer', { date: formatDate(s.appeal.decidedAt) }) }}</span>
                  {{ s.appeal.response }}
                </p>
                <p v-else-if="s.appeal.status === 'open'" class="mt-1 text-base-400">{{ t('sanctions.appeal.waiting') }}</p>
              </div>

              <!-- Einspruch schreiben -->
              <form v-if="writing === s.id" class="mt-3" @submit.prevent="submit(s)">
                <label class="label" :for="`appeal-${s.id}`">{{ t('sanctions.appeal.label') }}</label>
                <textarea
                  :id="`appeal-${s.id}`"
                  v-model="text"
                  class="field min-h-28 resize-y"
                  :maxlength="APPEAL_MAX + 50"
                  :placeholder="t('sanctions.appeal.placeholder')"
                  data-testid="appeal-text"
                />
                <div class="mt-1 flex items-center gap-2 text-[11px]">
                  <span class="text-base-400">{{ t('sanctions.appeal.once') }}</span>
                  <span
                    class="ml-auto tabular-nums"
                    :class="count < APPEAL_MIN || count > APPEAL_MAX ? 'text-lamp-300' : 'text-base-400'"
                    data-testid="appeal-count"
                  >{{ count }} / {{ APPEAL_MAX }}</span>
                </div>
                <p v-if="error" role="alert" class="mt-2 text-xs text-redstone-300">{{ error }}</p>
                <div class="mt-3 flex justify-end gap-2">
                  <button type="button" class="btn btn-ghost" :disabled="sending" @click="writing = null">{{ t('common.actions.cancel') }}</button>
                  <button class="btn btn-primary" :disabled="sending || count < APPEAL_MIN || count > APPEAL_MAX" data-testid="appeal-send">
                    {{ sending ? t('sanctions.appeal.sending') : t('sanctions.appeal.send') }}
                  </button>
                </div>
              </form>
              <div v-else-if="s.appealable && sanctionIsActive(s)" class="mt-3 flex justify-end">
                <button class="btn btn-primary px-3 py-1.5 text-xs" data-testid="appeal-start" @click="startAppeal(s)">{{ t('sanctions.appeal.button') }}</button>
              </div>
            </li>
          </ul>
        </div>

        <footer class="border-t border-base-800 px-5 py-3 text-[11px] text-base-400">
          {{ t('sanctions.dialog.footer') }}
        </footer>
      </section>
    </div>
  </Teleport>
</template>
