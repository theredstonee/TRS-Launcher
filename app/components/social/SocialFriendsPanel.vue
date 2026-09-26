<script setup lang="ts">
import type { TrsFriend, TrsPlayerCape, TrsReportReason, TrsUserRef } from '~/utils/trs'

// Reiter „Freunde“ in drei Spalten: Freundesliste (online/zuletzt online,
// Menü), Anfragen (ein- und ausgehend, Umhang-Angebote) und Blockiert.
// Alles aktualisiert sich über den Echtzeit-Kanal.
const props = defineProps<{ search: string }>()
const emit = defineEmits<{ message: [uuid: string]; report: [friend: TrsUserRef] }>()

const trs = useTrsStore()
const toasts = useToasts()
const busy = ref<string | null>(null)
const capes = ref<Record<string, TrsPlayerCape>>({})
let capesFor = ''

const q = computed(() => props.search.trim().toLocaleLowerCase())
const match = (name: string) => !q.value || name.toLocaleLowerCase().includes(q.value)
const friends = computed(() => trsSortFriends(trs.friends?.friends ?? []).filter((f) => match(f.name)))
const incoming = computed(() => (trs.friends?.requests.incoming ?? []).filter((r) => match(r.name)))
const outgoing = computed(() => (trs.friends?.requests.outgoing ?? []).filter((r) => match(r.name)))
const blocked = computed(() => (trs.blocked ?? []).filter((b) => match(b.name)))
const offers = computed(() => (trs.capeOffers?.incoming.length ?? 0) + (trs.capeOffers?.outgoing.length ?? 0))
const onlineCount = computed(() => (trs.friends?.friends ?? []).filter((f) => f.presence).length)

/** Umhänge/Abzeichen der Freunde (nur Schmuck, höchstens bei Änderung der Liste). */
async function loadCapes() {
  const ids = (trs.friends?.friends ?? []).map((f) => f.uuid).slice(0, 100)
  const key = ids.join(',')
  if (!ids.length || key === capesFor) return
  capesFor = key
  try {
    const list = await backend.trs.playerCapes(ids)
    capes.value = Object.fromEntries(list.map((c) => [c.uuid, c]))
  } catch {
    // Ohne Umhänge geht es auch.
  }
}
watch(() => trs.friends?.friends.length, () => void loadCapes(), { immediate: true })

onMounted(() => {
  if (!trs.blocked) void trs.loadBlocked()
  if (!trs.capeOffers) void trs.loadCapeOffers()
})

function statusLine(f: TrsFriend): string {
  if (f.presence) return trsPresenceText(f.presence)
  const seen = trs.lastSeen[f.uuid]
  return seen ? t('social.friends.lastOnline', { time: formatRelative(seen, true) }) : t('common.status.offline')
}

function dotClass(f: TrsFriend) {
  if (f.presence?.state === 'in-game') return 'bg-lamp-400 animate-lamp'
  if (f.presence) return 'bg-ok'
  return 'bg-base-600'
}

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

const cancel = (uuid: string) =>
  run(`cancel:${uuid}`, async () => {
    await backend.trs.cancelRequest(uuid)
    await trs.loadFriends()
  })

const unblock = (uuid: string, name: string) =>
  run(`unblock:${uuid}`, async () => {
    await backend.trs.unblock(uuid)
    toasts.ok(t('friends.toasts.unblocked', { name }))
    await trs.loadBlocked()
  })

// --- Menü je Freund ---------------------------------------------------------------------

const menuFor = ref<string | null>(null)
function closeMenu(e: MouseEvent) {
  if (!(e.target as HTMLElement | null)?.closest('[data-row-menu]')) menuFor.value = null
}
onMounted(() => document.addEventListener('mousedown', closeMenu))
onBeforeUnmount(() => document.removeEventListener('mousedown', closeMenu))

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
      await trs.loadBlocked()
    }
    await trs.loadFriends()
  })
}

const shareWith = ref<TrsUserRef | null>(null)

// --- Umhang melden (hochgeladene Umhänge von Freunden) ----------------------------------------

const reportingCape = ref<{ friend: TrsFriend; capeId: string } | null>(null)
const capeReason = ref<TrsReportReason>('inappropriate')
const capeNote = ref('')
const capeError = ref<string | null>(null)
const capeReasons: TrsReportReason[] = ['inappropriate', 'copyright', 'impersonation', 'other']

function startCapeReport(friend: TrsFriend) {
  const cape = capes.value[friend.uuid]
  if (!cape?.capeId || !cape.upload) return
  reportingCape.value = { friend, capeId: cape.capeId }
  capeReason.value = 'inappropriate'
  capeNote.value = ''
  capeError.value = null
}

async function sendCapeReport() {
  const r = reportingCape.value
  if (!r) return
  const note = trsNoteSchema.safeParse(capeNote.value)
  if (!note.success) {
    capeError.value = firstIssue(note.error)
    return
  }
  await run('report', async () => {
    try {
      await backend.trs.reportCape(r.capeId, capeReason.value, note.data || null)
      reportingCape.value = null
      toasts.ok(t('friends.report.thanks'))
    } catch (e) {
      capeError.value = errorMessage(e)
    }
  })
}

function join(f: TrsFriend) {
  const server = f.presence?.game?.server
  if (server) void useJoinStore().request(server, f.presence?.game?.version ?? null)
}
</script>

<template>
  <div class="grid min-h-0 flex-1 grid-cols-1 gap-3 overflow-y-auto lg:grid-cols-3 lg:overflow-hidden" data-testid="friends-panel">
    <!-- Freundesliste -->
    <section class="card flex min-h-0 flex-col">
      <h2 class="display flex items-center gap-2 border-b border-base-800 px-4 py-3 text-base text-base-50">
        {{ t('social.friends.list') }}
        <span v-if="trs.friends?.friends.length" class="text-xs text-base-400">{{ onlineCount }}/{{ trs.friends.friends.length }}</span>
      </h2>
      <ul v-if="friends.length" class="min-h-0 flex-1 space-y-1.5 overflow-y-auto p-2" data-testid="friends-list">
        <li v-for="f in friends" :key="f.uuid" class="flex items-center gap-3 rounded-lg bg-base-850 px-3 py-2">
          <span class="relative shrink-0">
            <span class="block size-10 overflow-hidden rounded-md"><PlayerFace :uuid="f.uuid" :name="f.name" /></span>
            <span class="absolute -right-0.5 -bottom-0.5 size-3 rounded-full ring-2 ring-base-850" :class="dotClass(f)" />
          </span>
          <div class="min-w-0 flex-1">
            <p class="flex items-center gap-2 truncate text-sm font-semibold text-base-50">
              {{ f.name }}
              <span v-if="capes[f.uuid]?.badge" class="badge bg-redstone-900/50 px-1.5 py-0 text-[10px] text-redstone-300" :title="t('friends.list.usesTrs')">TRS</span>
            </p>
            <p class="truncate text-xs" :class="f.presence ? 'text-base-200' : 'text-base-400'">{{ statusLine(f) }}</p>
          </div>
          <CapeThumb
            v-if="capes[f.uuid]?.texture"
            :texture="capes[f.uuid]!.texture"
            :scale="capes[f.uuid]!.scale"
            :frames="capes[f.uuid]!.frames"
            :frame-time-ms="capes[f.uuid]!.frameTimeMs"
            :width="16"
            :title="t('friends.list.capeOf', { name: f.name })"
          />
          <button v-if="f.presence?.game?.server" class="btn btn-primary px-2.5 py-1 text-xs" data-testid="friend-join" @click="join(f)">
            {{ t('social.invite.join') }}
          </button>
          <div class="relative" data-row-menu>
            <button class="btn-icon size-8" :aria-label="t('friends.list.moreActions', { name: f.name })" :aria-expanded="menuFor === f.uuid" @click="menuFor = menuFor === f.uuid ? null : f.uuid">
              <SocialIcon name="menu" class="size-4" />
            </button>
            <div v-if="menuFor === f.uuid" class="menu top-9 right-0 w-52" role="menu">
              <button class="menu-item" role="menuitem" data-testid="friend-message" @click="menuFor = null; emit('message', f.uuid)">
                <SocialIcon name="chat" class="size-4" />{{ t('social.friends.sendMessage') }}
              </button>
              <button class="menu-item" role="menuitem" data-testid="friend-share-cape" @click="menuFor = null; shareWith = { uuid: f.uuid, name: f.name }">
                <SocialIcon name="skins" class="size-4" />{{ t('capeShare.friendMenu') }}
              </button>
              <div class="my-1 border-t border-base-700" />
              <button class="menu-item" role="menuitem" @click="menuFor = null; confirm = { kind: 'remove', uuid: f.uuid, name: f.name }">
                <SocialIcon name="close" class="size-4" />{{ t('social.menu.removeFriend') }}
              </button>
              <button class="menu-item" role="menuitem" @click="menuFor = null; confirm = { kind: 'block', uuid: f.uuid, name: f.name }">
                <SocialIcon name="block" class="size-4" />{{ t('social.menu.block') }}
              </button>
              <button class="menu-item text-redstone-300" role="menuitem" @click="menuFor = null; emit('report', { uuid: f.uuid, name: f.name })">
                <SocialIcon name="flag" class="size-4" />{{ t('social.menu.reportPlayer') }}
              </button>
              <button v-if="capes[f.uuid]?.upload" class="menu-item text-redstone-300" role="menuitem" @click="menuFor = null; startCapeReport(f)">
                <SocialIcon name="flag" class="size-4" />{{ t('friends.list.reportCape') }}
              </button>
            </div>
          </div>
        </li>
      </ul>
      <div v-else class="grid flex-1 place-items-center p-6 text-center text-sm text-base-400">
        <p>{{ search.trim() ? t('social.chat.noMatches') : t('friends.empty.text') }}</p>
      </div>
    </section>

    <!-- Anfragen -->
    <section class="card flex min-h-0 flex-col">
      <h2 class="display border-b border-base-800 px-4 py-3 text-base text-base-50">{{ t('social.friends.requests') }}</h2>
      <div class="min-h-0 flex-1 space-y-4 overflow-y-auto p-2">
        <p v-if="!incoming.length && !outgoing.length && !offers" class="px-2 py-6 text-center text-sm text-base-400">{{ t('friends.requests.empty') }}</p>
        <CapeOffersList v-if="offers" outgoing compact />
        <div v-if="incoming.length">
          <h3 class="section-title mb-1.5 px-1 text-xs text-base-400">{{ t('friends.requests.incoming') }}</h3>
          <ul class="space-y-1.5">
            <li v-for="r in incoming" :key="r.uuid" class="flex items-center gap-3 rounded-lg bg-base-850 px-3 py-2">
              <span class="block size-9 shrink-0 overflow-hidden rounded-md"><PlayerFace :uuid="r.uuid" :name="r.name" /></span>
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-semibold text-base-50">{{ r.name }}</p>
                <p class="truncate text-xs text-base-400">{{ t('friends.requests.incomingText', { date: trsDate(r.createdAt) }) }}</p>
              </div>
              <button class="btn btn-primary px-2.5 py-1 text-xs" :disabled="!!busy" data-testid="request-accept" @click="trs.answerRequest(r, true)">{{ t('social.friends.accept') }}</button>
              <button class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="!!busy" @click="trs.answerRequest(r, false)">{{ t('social.friends.decline') }}</button>
            </li>
          </ul>
        </div>
        <div v-if="outgoing.length">
          <h3 class="section-title mb-1.5 px-1 text-xs text-base-400">{{ t('friends.requests.outgoing') }}</h3>
          <ul class="space-y-1.5">
            <li v-for="r in outgoing" :key="r.uuid" class="flex items-center gap-3 rounded-lg bg-base-850 px-3 py-2">
              <span class="block size-9 shrink-0 overflow-hidden rounded-md"><PlayerFace :uuid="r.uuid" :name="r.name" /></span>
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-semibold text-base-50">{{ r.name }}</p>
                <p class="truncate text-xs text-base-400">{{ t('friends.requests.outgoingText', { date: trsDate(r.createdAt) }) }}</p>
              </div>
              <button class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="!!busy" @click="cancel(r.uuid)">{{ t('friends.requests.withdraw') }}</button>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <!-- Blockiert -->
    <section class="card flex min-h-0 flex-col">
      <h2 class="display border-b border-base-800 px-4 py-3 text-base text-base-50">{{ t('social.friends.blocked') }}</h2>
      <ul v-if="blocked.length" class="min-h-0 flex-1 space-y-1.5 overflow-y-auto p-2">
        <li v-for="b in blocked" :key="b.uuid" class="flex items-center gap-3 rounded-lg bg-base-850 px-3 py-2">
          <span class="block size-9 shrink-0 overflow-hidden rounded-md opacity-60"><PlayerFace :uuid="b.uuid" :name="b.name" /></span>
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-semibold text-base-50">{{ b.name }}</p>
            <p class="truncate text-xs text-base-400">{{ t('friends.blocked.since', { date: trsDate(b.since) }) }}</p>
          </div>
          <button class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="!!busy" @click="unblock(b.uuid, b.name)">{{ t('friends.blocked.unblock') }}</button>
        </li>
      </ul>
      <div v-else class="grid flex-1 place-items-center p-6 text-center text-sm text-base-400">
        <p>{{ t('friends.blocked.empty') }}</p>
      </div>
      <p class="border-t border-base-800 px-4 py-2.5 text-[11px] text-base-400">{{ t('friends.blocked.hint') }}</p>
    </section>

    <!-- Dialoge -->
    <FriendCapeShareDialog v-if="shareWith" :friend="shareWith" @close="shareWith = null; trs.loadCapeOffers()" />
    <BaseDialog v-if="confirm" :title="confirm.kind === 'remove' ? t('friends.confirm.removeTitle') : t('friends.confirm.blockTitle')" @close="confirm = null">
      <p class="text-sm text-base-200">{{ confirm.kind === 'remove' ? t('social.confirm.remove', { name: confirm.name }) : t('social.confirm.block', { name: confirm.name }) }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="confirm = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" @click="confirmAction">{{ confirm.kind === 'remove' ? t('common.actions.remove') : t('friends.list.block') }}</button>
      </template>
    </BaseDialog>
    <BaseDialog v-if="reportingCape" :title="t('friends.report.title')" @close="reportingCape = null">
      <i18n-t keypath="friends.report.text" tag="p" scope="global" class="mb-3 text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ reportingCape.friend.name }}</strong></template>
      </i18n-t>
      <p class="label">{{ t('friends.report.reason') }}</p>
      <div class="grid grid-cols-2 gap-2">
        <button
          v-for="key in capeReasons"
          :key="key"
          class="rounded-lg border px-3 py-2 text-left text-sm transition-colors"
          :class="capeReason === key ? 'border-redstone-500 bg-redstone-900/40' : 'border-base-700 hover:border-base-600'"
          :aria-pressed="capeReason === key"
          @click="capeReason = key"
        >
          {{ t(`friends.report.reasons.${key}`) }}
        </button>
      </div>
      <label class="label mt-3" for="cape-report-note">{{ t('friends.report.note') }}</label>
      <textarea id="cape-report-note" v-model="capeNote" class="field h-20 resize-none" maxlength="200" />
      <p v-if="capeError" role="alert" class="mt-2 text-xs text-redstone-300">{{ capeError }}</p>
      <template #actions>
        <button class="btn btn-ghost" @click="reportingCape = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="busy === 'report'" @click="sendCapeReport">{{ t('friends.report.submit') }}</button>
      </template>
    </BaseDialog>
  </div>
</template>
