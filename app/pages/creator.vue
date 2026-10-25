<script setup lang="ts">
import type { AdminCreator } from '~/utils/creator'
import { messeRestricted } from '~/utils/messe'
// Creator-Programm (API §35): als Fan Codes einlösen, Creator unterstützen und
// blockieren; als Creator das Dashboard; als Helfer (§35.9) das Dashboard eines
// anderen Creators mit den eigenen Rechten. Team-Mitglieder mit
// `creators.manage` wählen über „Creator wählen (Team)“ jedes Dashboard und
// handeln dort mit allen Rechten (Team-Modus, §35.9 – der Server protokolliert
// jede Änderung). Oben stehen offene Einladungen, Helfer zu werden. Daten und
// Bilder kommen aus dem Kern (Token bleibt dort).
const store = useCreatorStore()
const trs = useTrsStore()
const accounts = useAccountsStore()
const route = useRoute()
// Messe-PC: Creator-Dashboard (Konto-Daten) gesperrt
if (messeRestricted.value) void navigateTo('/', { replace: true })
const toasts = useToasts()

/** `fan`, `own` (eigenes Dashboard) oder die UUID eines Creators, dem man hilft bzw. den das Team geöffnet hat. */
const view = ref<string>('fan')
const helping = computed(() => store.me?.helping ?? [])
const helpingCreator = computed(() => helping.value.find((h) => h.creator.uuid === view.value)?.creator ?? null)
/** Im Team-Modus geöffneter Creator (nicht in `helping` bzw. mit allen Rechten statt der Helfer-Rechte). */
const teamCreator = ref<{ uuid: string; name: string } | null>(null)

const options = computed(() => {
  const list: { id: string; label: string }[] = [{ id: 'fan', label: t('creator.views.fan') }]
  if (store.active) list.push({ id: 'own', label: t('creator.views.own') })
  for (const h of helping.value) {
    if (h.creator.uuid !== teamCreator.value?.uuid) list.push({ id: h.creator.uuid, label: t('creator.helpers.dashboardOf', { name: h.creator.name }) })
  }
  if (teamCreator.value) list.push({ id: teamCreator.value.uuid, label: t('creator.teamMode.option', { name: teamCreator.value.name || '…' }) })
  return list
})

/** `?view=<uuid>&team=1&name=…` aus dem Team-Bereich („Dashboard öffnen (Team)“). */
function teamFromRoute() {
  const id = typeof route.query.view === 'string' ? route.query.view : null
  if (!store.canTeamMode || route.query.team !== '1' || !id || !/^[0-9a-f]{32}$/.test(id)) return
  const name = typeof route.query.name === 'string' ? route.query.name.slice(0, 16) : ''
  teamCreator.value = { uuid: id, name }
}

/** Startansicht: `?view=`, sonst das eigene Dashboard für aktive Creator, sonst Fan. Danach bleibt die Wahl. */
let initialized = false
function pickInitial() {
  if (!store.me) return
  const wanted = typeof route.query.view === 'string' ? route.query.view : null
  if (!initialized && wanted && options.value.some((o) => o.id === wanted)) view.value = wanted
  else if (!initialized || !options.value.some((o) => o.id === view.value)) view.value = store.active ? 'own' : 'fan'
  initialized = true
  // Store und Ansicht abgleichen (z. B. noch als Helfer unterwegs von vorhin).
  void store.actAs(view.value === 'fan' || view.value === 'own' ? null : view.value)
}

watch(view, (v) => void store.actAs(v === 'fan' || v === 'own' ? null : v))

// Das Team-Recht steht erst nach `me` fest – dann den Einstieg aus der Adresse übernehmen.
watch(() => store.canTeamMode, (can) => {
  if (can && !teamCreator.value) teamFromRoute()
})

async function load() {
  teamFromRoute()
  await store.load()
  pickInitial()
}

// --- Team-Auswahl (alle Creator, mit Suche) ------------------------------------------------
const picking = ref(false)
const pickQuery = ref('')
const pickList = ref<AdminCreator[] | null>(null)
let pickTimer: ReturnType<typeof setTimeout> | null = null
async function searchCreators() {
  try {
    pickList.value = await backend.creator.adminList({ q: pickQuery.value.trim() || undefined, state: 'active' })
  } catch (e) {
    pickList.value = []
    toasts.error(e)
  }
}
function openPicker() {
  pickQuery.value = ''
  pickList.value = null
  picking.value = true
  void searchCreators()
}
watch(pickQuery, () => {
  if (pickTimer) clearTimeout(pickTimer)
  pickTimer = setTimeout(() => void searchCreators(), 250)
})
function openAsTeam(c: { uuid: string; name: string }) {
  teamCreator.value = { uuid: c.uuid, name: c.name }
  picking.value = false
  view.value = c.uuid
}
// Name nachtragen, sobald das Dashboard da ist (Einstieg per Link ohne Namen).
watch(() => store.dashboard, (d) => {
  if (d && teamCreator.value && d.uuid === teamCreator.value.uuid && !teamCreator.value.name) teamCreator.value = { uuid: d.uuid, name: d.name }
})

onMounted(load)
watch(() => [trs.enabled, accounts.active?.id], () => {
  initialized = false
  void load()
})
watch(options, pickInitial)

// --- Helfer-Einladungen -----------------------------------------------------------------
const answering = ref<string | null>(null)
async function answer(creator: string, accept: boolean) {
  answering.value = creator
  try {
    await store.answerInvite(creator, accept)
    toasts.ok(accept ? t('creator.helpers.acceptedToast') : t('creator.helpers.declinedToast'))
    if (accept) view.value = creator
  } catch (e) {
    toasts.error(e)
  } finally {
    answering.value = null
  }
}

const leaving = ref(false)
const confirmLeave = ref(false)
async function leave() {
  const c = helpingCreator.value
  if (!c) return
  leaving.value = true
  try {
    await store.leaveHelping(c.uuid)
    confirmLeave.value = false
    view.value = store.active ? 'own' : 'fan'
    toasts.ok(t('creator.helpers.leftToast', { name: c.name }))
  } catch (e) {
    toasts.error(e)
  } finally {
    leaving.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-5xl p-6 mobile:p-4" data-testid="creator-page">
    <PullToRefresh :refresh="load" />
    <PageHeader :title="t('creator.title')" :subtitle="t('creator.subtitle')">
      <div v-if="options.length > 1" class="flex rounded-full bg-base-900 p-1 ring-1 ring-base-800 mobile-scroll-x" role="radiogroup" :aria-label="t('creator.views.label')">
        <button
          v-for="o in options"
          :key="o.id"
          class="seg rounded-full text-xs whitespace-nowrap"
          :class="{ 'seg-on': view === o.id }"
          role="radio"
          :aria-checked="view === o.id"
          :data-testid="`creator-view-${o.id === 'fan' || o.id === 'own' ? o.id : 'helping'}`"
          @click="view = o.id"
        >
          {{ o.label }}
        </button>
      </div>
      <button v-if="store.canTeamMode" class="btn btn-ghost" data-testid="creator-team-pick" @click="openPicker">
        <SocialIcon name="crown" class="size-4" />{{ t('creator.teamMode.pick') }}
      </button>
      <button class="btn btn-ghost mobile:hidden" :disabled="store.loading || !trs.enabled" @click="load">
        <SocialIcon name="sync" class="size-4" :class="{ 'animate-spin': store.loading }" />{{ t('creator.refresh') }}
      </button>
    </PageHeader>

    <TrsGate what="creator">
      <div v-if="store.loading && !store.me" class="space-y-3">
        <div class="grid gap-3 md:grid-cols-2">
          <div class="skeleton h-36" />
          <div class="skeleton h-36" />
        </div>
        <div class="skeleton h-40" />
      </div>
      <div v-else-if="store.error && !store.me" class="card px-4 py-5 text-sm" role="alert">
        <p class="font-semibold text-base-50">{{ t('creator.error') }}</p>
        <p class="mt-0.5 text-base-400">{{ store.error }}</p>
        <button class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" @click="load">{{ t('common.actions.retry') }}</button>
      </div>

      <template v-else-if="store.me">
        <!-- Einladungen, Helfer zu werden -->
        <section v-for="inv in store.me.helperInvites" :key="inv.creator.uuid" class="card mb-4 flex flex-wrap items-center gap-3 border-lamp-400/40 px-4 py-3" data-testid="creator-helper-invite-card">
          <span class="block size-9 shrink-0 overflow-hidden rounded-lg"><PlayerFace :uuid="inv.creator.uuid" :name="inv.creator.name" /></span>
          <div class="min-w-0 flex-1">
            <p class="text-sm font-semibold text-base-50">{{ t('creator.helpers.inviteFrom', { name: inv.creator.name }) }}</p>
            <p class="text-xs text-base-400">
              {{ t('creator.helpers.inviteRights', { rights: inv.rights.map((r) => t(`creator.helpers.rights.${r}`)).join(', ') || '–' }) }}
            </p>
          </div>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="answering === inv.creator.uuid" @click="answer(inv.creator.uuid, false)">{{ t('creator.helpers.decline') }}</button>
          <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="answering === inv.creator.uuid" data-testid="creator-helper-accept" @click="answer(inv.creator.uuid, true)">
            {{ t('creator.helpers.accept') }}
          </button>
        </section>

        <CreatorFanView v-if="view === 'fan'" />
        <template v-else>
          <div v-if="helpingCreator && !store.teamMode" class="mb-3 flex justify-end">
            <button class="btn btn-ghost px-3 py-1.5 text-xs" data-testid="creator-leave-helping" @click="confirmLeave = true">{{ t('creator.helpers.leave') }}</button>
          </div>
          <CreatorDashboard v-if="store.dashboard" :self="store.dashboard" />
          <div v-else-if="store.dashboardError" class="card px-4 py-5 text-sm" role="alert">
            <p class="text-base-400">{{ store.dashboardError }}</p>
            <button class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" @click="store.loadDashboard()">{{ t('common.actions.retry') }}</button>
          </div>
          <div v-else class="space-y-3">
            <div class="skeleton h-24" />
            <div class="skeleton h-48" />
          </div>
        </template>
      </template>
    </TrsGate>

    <CapeUploadDialog v-if="store.fanCapeWorkshop" fan-cape @close="store.fanCapeWorkshop = false" @fan-uploaded="store.fanCapeWorkshop = false" />

    <BaseDialog v-if="picking" :title="t('creator.teamMode.pickTitle')" @close="picking = false">
      <p class="mb-3 text-xs text-base-400">{{ t('creator.teamMode.pickText') }}</p>
      <input v-model="pickQuery" class="field" :placeholder="t('creator.teamMode.search')" :aria-label="t('creator.teamMode.search')" data-testid="creator-team-search" />
      <ul class="mt-2 max-h-72 overflow-y-auto rounded-lg border border-base-800" :aria-label="t('creator.teamMode.pickTitle')">
        <li v-if="!pickList" class="px-3 py-3"><div class="skeleton h-6" /></li>
        <li v-for="c in pickList ?? []" :key="c.uuid">
          <button type="button" class="flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm text-base-200 transition-colors hover:bg-base-800 hover:text-base-50" @click="openAsTeam(c)">
            <span class="block size-6 shrink-0 overflow-hidden rounded"><PlayerFace :uuid="c.uuid" :name="c.name" /></span>
            <span class="min-w-0 flex-1 truncate">{{ c.name }}</span>
            <CreatorTierBadge :tier="c.tier" />
            <span class="text-xs text-base-400 tabular-nums">{{ t('creator.teamMode.fans', { n: formatNumber(c.fans) }) }}</span>
          </button>
        </li>
        <li v-if="pickList && !pickList.length" class="px-3 py-3 text-xs text-base-400">{{ t('creator.teamMode.noResults') }}</li>
      </ul>
      <template #actions>
        <button class="btn btn-ghost" @click="picking = false">{{ t('common.actions.cancel') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="confirmLeave && helpingCreator" :title="t('creator.helpers.leaveTitle', { name: helpingCreator.name })" @close="confirmLeave = false">
      <p class="text-sm text-base-200">{{ t('creator.helpers.leaveText', { name: helpingCreator.name }) }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="confirmLeave = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="leaving" @click="leave">{{ t('creator.helpers.leave') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
