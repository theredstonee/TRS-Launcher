<script setup lang="ts">
import type { TrsBlocked, TrsFriend, TrsPlayerCape, TrsReportReason } from '~/utils/trs'

// Freundesliste über die TRS API: wer ist online, wer spielt was – und wer
// seinen Server teilt, dem kann man mit einem Klick folgen (gleicher Start wie
// auf der Server-Seite, nur mit der geteilten Adresse). Die Seite fragt alle
// 30 s nach, solange sie sichtbar ist.
const POLL_MS = 30_000

const trs = useTrsStore()
const accounts = useAccountsStore()
const instances = useInstancesStore()
const games = useGamesStore()
const toasts = useToasts()

type Tab = 'friends' | 'requests' | 'blocked'
const tab = ref<Tab>('friends')
const tabs: Tab[] = ['friends', 'requests', 'blocked']
const loading = ref(true)
const offline = ref(false)
const busy = ref<string | null>(null)
const blocked = ref<TrsBlocked[]>([])
const capes = ref<Record<string, TrsPlayerCape>>({})
let capesFor = ''
let capesAt = 0

const view = computed(() => trs.friends)
const friends = computed(() => trsSortFriends(view.value?.friends ?? []))
const incoming = computed(() => view.value?.requests.incoming ?? [])
const outgoing = computed(() => view.value?.requests.outgoing ?? [])
const onlineCount = computed(() => friends.value.filter((f) => f.presence).length)

async function refresh(showErrors = false) {
  if (!trs.enabled || !accounts.active) {
    loading.value = false
    return
  }
  try {
    await trs.loadFriends()
    offline.value = trs.problem === 'offline'
    await loadCapes()
  } catch (e) {
    if (showErrors) toasts.error(e)
  } finally {
    loading.value = false
  }
}

/** Umhänge der Freunde (Lookup, höchstens alle 5 Minuten oder wenn sich die Liste ändert). */
async function loadCapes() {
  const ids = friends.value.map((f) => f.uuid).slice(0, 100)
  const key = ids.join(',')
  if (!ids.length || (key === capesFor && Date.now() - capesAt < 5 * 60_000)) return
  try {
    const list = await backend.trs.playerCapes(ids)
    capes.value = Object.fromEntries(list.map((c) => [c.uuid, c]))
    capesFor = key
    capesAt = Date.now()
  } catch {
    // Nur Schmuck – ohne Umhänge geht es auch.
  }
}

async function loadBlocked() {
  try {
    blocked.value = await backend.trs.blocks()
  } catch (e) {
    if (!(e instanceof BackendError && trsIsQuiet(e.kind))) toasts.error(e)
  }
}

const menuFor = ref<string | null>(null)
function closeMenu(e: MouseEvent) {
  if (!(e.target as HTMLElement | null)?.closest('[data-row-menu]')) menuFor.value = null
}

let timer: ReturnType<typeof setInterval> | null = null
onMounted(async () => {
  document.addEventListener('mousedown', closeMenu)
  if (!instances.items.length) instances.load().catch(() => {})
  if (!trs.status) await trs.refreshStatus()
  await refresh()
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') void refresh()
  }, POLL_MS)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', closeMenu)
  if (timer) clearInterval(timer)
})
watch(
  () => [trs.enabled, accounts.active?.id],
  () => {
    loading.value = true
    capesFor = ''
    void refresh()
  },
)
watch(tab, (value) => {
  if (value === 'blocked') void loadBlocked()
})

async function run(key: string, action: () => Promise<void>) {
  if (busy.value) return
  busy.value = key
  try {
    await action()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

// --- Hinzufügen ---------------------------------------------------------------------

const adding = ref(false)
const addName = ref('')
const addError = ref<string | null>(null)

function startAdd() {
  addName.value = ''
  addError.value = null
  adding.value = true
}

async function sendRequest() {
  const parsed = trsTargetSchema.safeParse(addName.value)
  if (!parsed.success) {
    addError.value = firstIssue(parsed.error)
    return
  }
  addError.value = null
  busy.value = 'add'
  try {
    const result = await backend.trs.friendRequest(parsed.data)
    adding.value = false
    toasts.ok(
      result.status === 'accepted'
        ? t('friends.toasts.nowFriends', { name: result.user.name })
        : t('friends.toasts.requestSent', { name: result.user.name }),
    )
    await refresh()
  } catch (e) {
    addError.value = errorMessage(e)
  } finally {
    busy.value = null
  }
}

// --- Anfragen, Entfernen, Blockieren ------------------------------------------------

const accept = (uuid: string, name: string) =>
  run(`accept:${uuid}`, async () => {
    await backend.trs.acceptFriend(uuid)
    toasts.ok(t('friends.toasts.nowFriends', { name }))
    await refresh()
  })
const decline = (uuid: string) =>
  run(`decline:${uuid}`, async () => {
    await backend.trs.declineFriend(uuid)
    await refresh()
  })
const cancel = (uuid: string) =>
  run(`cancel:${uuid}`, async () => {
    await backend.trs.cancelRequest(uuid)
    await refresh()
  })

const confirm = ref<{ kind: 'remove' | 'block'; uuid: string; name: string } | null>(null)
async function confirmAction() {
  const c = confirm.value
  confirm.value = null
  if (!c) return
  await run(`${c.kind}:${c.uuid}`, async () => {
    if (c.kind === 'remove') {
      await backend.trs.removeFriend(c.uuid)
      toasts.ok(t('friends.toasts.removed', { name: c.name }))
    } else {
      await backend.trs.block(c.uuid)
      toasts.ok(t('friends.toasts.blocked', { name: c.name }))
      await loadBlocked()
    }
    await refresh()
  })
}

const blockName = ref('')
const blockError = ref<string | null>(null)
async function blockByName() {
  const parsed = trsTargetSchema.safeParse(blockName.value)
  if (!parsed.success) {
    blockError.value = firstIssue(parsed.error)
    return
  }
  blockError.value = null
  await run('block-name', async () => {
    try {
      const user = await backend.trs.block(parsed.data)
      blockName.value = ''
      toasts.ok(t('friends.toasts.blocked', { name: user.name }))
      await Promise.all([loadBlocked(), refresh()])
    } catch (e) {
      blockError.value = errorMessage(e)
    }
  })
}

const unblock = (user: TrsBlocked) =>
  run(`unblock:${user.uuid}`, async () => {
    await backend.trs.unblock(user.uuid)
    toasts.ok(t('friends.toasts.unblocked', { name: user.name }))
    await loadBlocked()
  })

// --- Melden -------------------------------------------------------------------------

const reporting = ref<{ friend: TrsFriend; capeId: string } | null>(null)
const reportReason = ref<TrsReportReason>('inappropriate')
const reportNote = ref('')
const reportError = ref<string | null>(null)
const reasons: TrsReportReason[] = ['inappropriate', 'copyright', 'impersonation', 'other']

function startReport(friend: TrsFriend) {
  const cape = capes.value[friend.uuid]
  if (!cape?.capeId || !cape.upload) return
  reporting.value = { friend, capeId: cape.capeId }
  reportReason.value = 'inappropriate'
  reportNote.value = ''
  reportError.value = null
}

async function sendReport() {
  const r = reporting.value
  if (!r) return
  const note = trsNoteSchema.safeParse(reportNote.value)
  if (!note.success) {
    reportError.value = firstIssue(note.error)
    return
  }
  await run('report', async () => {
    try {
      await backend.trs.reportCape(r.capeId, reportReason.value, note.data || null)
      reporting.value = null
      toasts.ok(t('friends.report.thanks'))
    } catch (e) {
      reportError.value = errorMessage(e)
    }
  })
}

// --- Beitreten ----------------------------------------------------------------------

/** "" = passende Instanz automatisch (gleiche Version/Loader, sonst zuletzt gespielt). */
const joinWith = ref('')
function joinTarget(friend: TrsFriend) {
  if (joinWith.value) return instances.items.find((i) => i.id === joinWith.value) ?? null
  return trsJoinInstance(instances.items, friend.presence?.game ?? null)
}
function joinBusy(friend: TrsFriend) {
  const target = joinTarget(friend)
  return !target || games.state(target.id).phase !== 'idle'
}
function join(friend: TrsFriend) {
  const server = friend.presence?.game?.server
  const target = joinTarget(friend)
  if (!server || !target) return
  toasts.info(t('friends.join.starting', { instance: target.name, server }))
  void games.launch(target.id, null, server)
}

function dotClass(friend: TrsFriend) {
  if (friend.presence?.state === 'in-game') return 'bg-lamp-400 animate-lamp'
  if (friend.presence) return 'bg-ok'
  return 'bg-base-600'
}
</script>

<template>
  <div class="mx-auto max-w-3xl p-6">
    <PageHeader :title="t('friends.title')" :subtitle="t('friends.subtitle')">
      <button class="btn btn-primary" :disabled="!trs.enabled || !accounts.active" data-testid="friends-add" @click="startAdd">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        {{ t('friends.add') }}
      </button>
    </PageHeader>

    <TrsGate what="friends">
      <div v-if="offline" class="card mb-4 flex items-center gap-3 px-4 py-3 text-sm text-base-400">
        <span class="size-2 rounded-full bg-base-600" />
        <span class="flex-1">{{ t('friends.offline') }}</span>
        <button class="btn btn-ghost px-3 py-1 text-xs" @click="refresh(true)">{{ t('common.actions.retry') }}</button>
      </div>

      <div class="mb-4 flex flex-wrap items-center gap-2">
        <div class="flex gap-1 rounded-lg bg-base-850 p-1 text-xs" role="tablist" :aria-label="t('friends.tabs.label')">
          <button
            v-for="key in tabs"
            :key="key"
            class="seg flex items-center gap-1.5 rounded-md px-3"
            :class="{ 'seg-on': tab === key }"
            role="tab"
            :aria-selected="tab === key"
            @click="tab = key"
          >
            {{ t(`friends.tabs.${key}`) }}
            <span v-if="key === 'friends' && friends.length" class="text-base-400">{{ onlineCount }}/{{ friends.length }}</span>
            <span v-if="key === 'requests' && incoming.length" class="rounded-full bg-redstone-500 px-1.5 text-[10px] font-bold text-white">
              {{ incoming.length }}
            </span>
          </button>
        </div>
        <label v-if="tab === 'friends' && instances.items.length > 1 && friends.some((f) => f.presence?.game?.server)" class="ml-auto flex items-center gap-2 text-xs text-base-400">
          {{ t('friends.join.withLabel') }}
          <select v-model="joinWith" class="field w-56 py-1.5">
            <option value="">{{ t('friends.join.auto') }}</option>
            <option v-for="i in instances.items" :key="i.id" :value="i.id">{{ i.name }} ({{ i.gameVersion }})</option>
          </select>
        </label>
      </div>

      <!-- Freunde ----------------------------------------------------------------- -->
      <template v-if="tab === 'friends'">
        <div v-if="loading" class="space-y-2">
          <div v-for="i in 3" :key="i" class="skeleton h-[68px]" />
        </div>
        <ul v-else-if="friends.length" class="space-y-2" data-testid="friends-list">
          <li v-for="f in friends" :key="f.uuid" class="card flex items-center gap-3 px-3 py-2.5">
            <span class="relative shrink-0">
              <span class="block size-10 overflow-hidden rounded-md">
                <PlayerFace :uuid="f.uuid" :name="f.name" />
              </span>
              <span class="absolute -right-0.5 -bottom-0.5 size-3 rounded-full ring-2 ring-base-900" :class="dotClass(f)" />
            </span>
            <div class="min-w-0 flex-1">
              <p class="flex items-center gap-2 truncate text-sm font-semibold text-base-50">
                {{ f.name }}
                <span v-if="capes[f.uuid]?.badge" class="badge bg-redstone-900/50 px-1.5 py-0 text-[10px] text-redstone-300" :title="t('friends.list.usesTrs')">TRS</span>
              </p>
              <p class="truncate text-xs" :class="f.presence ? 'text-base-200' : 'text-base-400'">{{ trsPresenceText(f.presence) }}</p>
            </div>
            <CapeThumb
              v-if="capes[f.uuid]?.texture"
              :texture="capes[f.uuid]!.texture"
              :scale="capes[f.uuid]!.scale"
              :frames="capes[f.uuid]!.frames"
              :frame-time-ms="capes[f.uuid]!.frameTimeMs"
              :width="18"
              :title="t('friends.list.capeOf', { name: f.name })"
            />
            <button
              v-if="f.presence?.game?.server"
              class="btn btn-primary px-3 py-1.5 text-xs"
              :disabled="joinBusy(f)"
              :title="joinTarget(f) ? t('friends.join.withInstance', { name: joinTarget(f)!.name }) : t('friends.join.noInstance')"
              data-testid="friend-join"
              @click="join(f)"
            >
              {{ t('friends.join.button') }}
            </button>
            <div class="relative" data-row-menu>
              <button
                class="btn-icon size-8 bg-transparent opacity-70 hover:opacity-100"
                :aria-label="t('friends.list.moreActions', { name: f.name })"
                :aria-expanded="menuFor === f.uuid"
                @click="menuFor = menuFor === f.uuid ? null : f.uuid"
              >
                <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><circle cx="12" cy="5.5" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="12" cy="18.5" r="1.7" /></svg>
              </button>
              <div v-if="menuFor === f.uuid" class="menu top-9 right-0 w-48" role="menu">
                <button class="menu-item" role="menuitem" @click="menuFor = null; confirm = { kind: 'remove', uuid: f.uuid, name: f.name }">{{ t('common.actions.remove') }}</button>
                <button class="menu-item" role="menuitem" @click="menuFor = null; confirm = { kind: 'block', uuid: f.uuid, name: f.name }">{{ t('friends.list.block') }}</button>
                <button v-if="capes[f.uuid]?.upload" class="menu-item" role="menuitem" @click="menuFor = null; startReport(f)">{{ t('friends.list.reportCape') }}</button>
              </div>
            </div>
          </li>
        </ul>
        <RedstoneEmpty
          v-else
          :title="t('friends.empty.title')"
          :text="t('friends.empty.text')"
          :seed="0x7f"
        >
          <button class="btn btn-primary" @click="startAdd">{{ t('friends.add') }}</button>
        </RedstoneEmpty>
      </template>

      <!-- Anfragen ---------------------------------------------------------------- -->
      <template v-else-if="tab === 'requests'">
        <RedstoneEmpty v-if="!incoming.length && !outgoing.length" :title="t('friends.requests.empty')" compact :seed="0x3a" />
        <div v-else class="space-y-5">
          <section v-if="incoming.length">
            <h2 class="section-title mb-2">{{ t('friends.requests.incoming') }}</h2>
            <ul class="space-y-2">
              <li v-for="r in incoming" :key="r.uuid" class="card flex items-center gap-3 px-3 py-2.5">
                <span class="block size-9 shrink-0 overflow-hidden rounded-md"><PlayerFace :uuid="r.uuid" :name="r.name" /></span>
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-semibold text-base-50">{{ r.name }}</p>
                  <p class="text-xs text-base-400">{{ t('friends.requests.incomingText', { date: trsDate(r.createdAt) }) }}</p>
                </div>
                <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!!busy" @click="accept(r.uuid, r.name)">{{ t('friends.requests.accept') }}</button>
                <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy" @click="decline(r.uuid)">{{ t('friends.requests.decline') }}</button>
              </li>
            </ul>
          </section>
          <section v-if="outgoing.length">
            <h2 class="section-title mb-2">{{ t('friends.requests.outgoing') }}</h2>
            <ul class="space-y-2">
              <li v-for="r in outgoing" :key="r.uuid" class="card flex items-center gap-3 px-3 py-2.5">
                <span class="block size-9 shrink-0 overflow-hidden rounded-md"><PlayerFace :uuid="r.uuid" :name="r.name" /></span>
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-semibold text-base-50">{{ r.name }}</p>
                  <p class="text-xs text-base-400">{{ t('friends.requests.outgoingText', { date: trsDate(r.createdAt) }) }}</p>
                </div>
                <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy" @click="cancel(r.uuid)">{{ t('friends.requests.withdraw') }}</button>
              </li>
            </ul>
          </section>
        </div>
      </template>

      <!-- Blockiert --------------------------------------------------------------- -->
      <template v-else>
        <form class="card mb-3 flex flex-wrap items-center gap-2 px-3 py-2.5" @submit.prevent="blockByName">
          <input v-model="blockName" class="field min-w-0 flex-1 py-1.5" maxlength="36" :placeholder="t('friends.blocked.placeholder')" :aria-label="t('friends.blocked.inputLabel')" />
          <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy">{{ t('friends.list.block') }}</button>
          <p v-if="blockError" role="alert" class="w-full text-xs text-redstone-300">{{ blockError }}</p>
        </form>
        <p class="mb-3 text-xs text-base-400">{{ t('friends.blocked.hint') }}</p>
        <ul v-if="blocked.length" class="space-y-2">
          <li v-for="b in blocked" :key="b.uuid" class="card flex items-center gap-3 px-3 py-2.5">
            <span class="block size-9 shrink-0 overflow-hidden rounded-md opacity-60"><PlayerFace :uuid="b.uuid" :name="b.name" /></span>
            <div class="min-w-0 flex-1">
              <p class="truncate text-sm font-semibold text-base-50">{{ b.name }}</p>
              <p class="text-xs text-base-400">{{ t('friends.blocked.since', { date: trsDate(b.since) }) }}</p>
            </div>
            <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy" @click="unblock(b)">{{ t('friends.blocked.unblock') }}</button>
          </li>
        </ul>
        <RedstoneEmpty v-else :title="t('friends.blocked.empty')" compact :seed="0x21" />
      </template>
    </TrsGate>

    <!-- Dialoge -------------------------------------------------------------------- -->
    <BaseDialog v-if="adding" :title="t('friends.add')" @close="adding = false">
      <label class="label" for="friend-name">{{ t('friends.addDialog.nameLabel') }}</label>
      <input
        id="friend-name"
        v-model="addName"
        class="field"
        maxlength="36"
        :placeholder="t('friends.addDialog.placeholder')"
        autocomplete="off"
        spellcheck="false"
        autofocus
        @keydown.enter="sendRequest"
      />
      <p class="mt-2 text-xs text-base-400">{{ t('friends.addDialog.hint') }}</p>
      <p v-if="addError" role="alert" class="mt-2 text-xs text-redstone-300">{{ addError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="adding = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-primary" :disabled="busy === 'add'" @click="sendRequest">
          {{ busy === 'add' ? t('friends.addDialog.sending') : t('friends.addDialog.send') }}
        </button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="confirm" :title="confirm.kind === 'remove' ? t('friends.confirm.removeTitle') : t('friends.confirm.blockTitle')" @close="confirm = null">
      <i18n-t
        :keypath="confirm.kind === 'remove' ? 'friends.confirm.removeText' : 'friends.confirm.blockText'"
        tag="p"
        scope="global"
        class="text-sm text-base-200"
      >
        <template #name><strong class="text-base-50">{{ confirm.name }}</strong></template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="confirm = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmAction">{{ confirm.kind === 'remove' ? t('common.actions.remove') : t('friends.list.block') }}</button>
      </template>
    </BaseDialog>

    <BaseDialog v-if="reporting" :title="t('friends.report.title')" @close="reporting = null">
      <i18n-t keypath="friends.report.text" tag="p" scope="global" class="mb-3 text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ reporting.friend.name }}</strong></template>
      </i18n-t>
      <p class="label">{{ t('friends.report.reason') }}</p>
      <div class="grid grid-cols-2 gap-2">
        <button
          v-for="key in reasons"
          :key="key"
          class="rounded-lg border px-3 py-2 text-left text-sm transition-colors"
          :class="reportReason === key ? 'border-redstone-500 bg-redstone-900/40' : 'border-base-700 hover:border-base-600'"
          :aria-pressed="reportReason === key"
          @click="reportReason = key"
        >
          {{ t(`friends.report.reasons.${key}`) }}
        </button>
      </div>
      <label class="label mt-3" for="report-note">{{ t('friends.report.note') }}</label>
      <textarea id="report-note" v-model="reportNote" class="field h-20 resize-none" maxlength="200" />
      <p v-if="reportError" role="alert" class="mt-2 text-xs text-redstone-300">{{ reportError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="reporting = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="busy === 'report'" @click="sendReport">{{ t('friends.report.submit') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
