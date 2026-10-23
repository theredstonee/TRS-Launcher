<script setup lang="ts">
// Events (§32): global an/aus, einzelne Spieler freigeben und für einzelne Spieler nur den Look abschalten (§32.6).
// Nur mit events.manage.
const { a, fill, rel } = useAdminText()
const { api, session } = useAdmin()

const isOwner = computed(() => session.value?.owner === true)
const NAME_OR_UUID = /^(?:[A-Za-z0-9_]{1,16}|[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$/

interface EventPlayer {
  uuid: string
  name: string | null
  addedAt: string
}
interface AdminEvent {
  id: string
  enabled: boolean
  updatedAt: string | null
  players: EventPlayer[]
  /** Look aus für diese Spieler (§32.6); fehlt bei alten Servern. */
  lookBlocked?: EventPlayer[]
}

/** Freigabe-Liste (`players`) oder Look-Sperre (`look-blocks`). */
type ListKind = 'players' | 'look-blocks'

const NAME = /^[A-Za-z0-9_]{1,16}$/

const events = ref<AdminEvent[]>([])
const error = ref('')
const loading = ref(true)
const busy = ref('')
const name = ref('')
const lookName = ref('')

// Owner-only: Part C – einen Spieler erschrecken.
const scareTarget = ref('')
const scareMsg = ref('')
const scaring = ref(false)

async function scare() {
  const t = scareTarget.value.trim()
  scareMsg.value = ''
  if (!NAME_OR_UUID.test(t)) {
    scareMsg.value = a.value.events.scareInvalid
    return
  }
  scaring.value = true
  try {
    await api('/v1/admin/halloween/scare', { method: 'POST', body: { target: t } })
    scareMsg.value = fill(a.value.events.scareSent, { target: t })
    scareTarget.value = ''
  } catch (e) {
    scareMsg.value = apiCode(e) === 'player_offline' ? a.value.events.scareOffline : fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    scaring.value = false
  }
}

function titleOf(id: string): string {
  return id === 'halloween' ? a.value.events.halloween : id
}

async function load() {
  error.value = ''
  try {
    events.value = await api<AdminEvent[]>('/v1/admin/events')
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
onMounted(load)

function replace(ev: AdminEvent | undefined) {
  if (!ev) return
  const i = events.value.findIndex((x) => x.id === ev.id)
  if (i >= 0) events.value[i] = ev
}

async function toggle(ev: AdminEvent) {
  busy.value = `t:${ev.id}`
  error.value = ''
  try {
    replace(await api<AdminEvent>(`/v1/admin/events/${encodeURIComponent(ev.id)}`, { method: 'PUT', body: { enabled: !ev.enabled } }))
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = ''
  }
}

async function add(ev: AdminEvent, kind: ListKind = 'players') {
  const input = kind === 'players' ? name : lookName
  const who = input.value.trim()
  if (!NAME.test(who)) {
    error.value = a.value.events.invalidName
    return
  }
  busy.value = `a:${kind}:${ev.id}`
  error.value = ''
  try {
    replace(await api<AdminEvent>(`/v1/admin/events/${encodeURIComponent(ev.id)}/${kind}`, { method: 'POST', body: { name: who } }))
    input.value = ''
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = ''
  }
}

async function remove(ev: AdminEvent, p: EventPlayer, kind: ListKind = 'players') {
  busy.value = `r:${kind}:${p.uuid}`
  error.value = ''
  try {
    replace(await api<AdminEvent>(`/v1/admin/events/${encodeURIComponent(ev.id)}/${kind}/${p.uuid}`, { method: 'DELETE' }))
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <div class="adm-page">
    <header>
      <h1 class="adm-title">{{ a.events.title }}</h1>
      <p class="adm-lead">{{ a.events.lead }}</p>
    </header>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <div v-if="loading" class="skeleton mt-6 h-48 rounded-xl" />
    <p v-else-if="!events.length" class="adm-empty mt-6"><SiteIcon name="moon" class="size-6" />{{ a.common.empty }}</p>

    <section v-for="ev in events" :key="ev.id" class="card mt-6 p-5">
      <div class="flex flex-wrap items-center gap-3">
        <h2 class="display min-w-0 flex-1 text-2xl text-base-50">{{ titleOf(ev.id) }}</h2>
        <span class="tone" :class="ev.enabled ? 'tone-warn' : 'tone-muted'">{{ ev.enabled ? a.events.on : a.events.off }}</span>
        <button
          type="button"
          class="btn"
          :class="ev.enabled ? 'btn-ghost' : 'btn-primary'"
          :aria-pressed="ev.enabled"
          :disabled="busy === `t:${ev.id}`"
          @click="toggle(ev)"
        >
          {{ ev.enabled ? a.events.disable : a.events.enable }}
        </button>
      </div>
      <p v-if="ev.updatedAt" class="mt-1 text-xs text-base-400">{{ fill(a.events.updated, { date: rel(ev.updatedAt) }) }}</p>

      <h3 class="mt-6 font-semibold text-base-50">{{ a.events.players }}</h3>
      <p class="mt-1 text-sm text-base-400">{{ a.events.playersLead }}</p>
      <form class="mt-3 flex flex-wrap gap-2" @submit.prevent="add(ev)">
        <label class="sr-only" :for="`ev-name-${ev.id}`">{{ a.events.name }}</label>
        <input
          :id="`ev-name-${ev.id}`"
          v-model="name"
          class="field max-w-56"
          maxlength="16"
          autocomplete="off"
          spellcheck="false"
          :placeholder="a.events.name"
          :aria-label="a.events.name"
        />
        <button type="submit" class="btn btn-primary" :disabled="busy === `a:players:${ev.id}` || !name.trim()">{{ a.events.add }}</button>
      </form>
      <p v-if="!ev.players.length" class="mt-4 text-sm text-base-400">{{ a.events.empty }}</p>
      <ul v-else class="mt-3 divide-y divide-base-800 text-sm">
        <li v-for="p in ev.players" :key="p.uuid" class="flex items-center gap-3 py-2">
          <PlayerHead :uuid="p.uuid" :name="p.name" :size="28" />
          <span class="min-w-0 flex-1 truncate text-base-50">{{ p.name || p.uuid }}</span>
          <span class="text-xs text-base-400">{{ rel(p.addedAt) }}</span>
          <button
            type="button"
            class="btn btn-ghost"
            :disabled="busy === `r:players:${p.uuid}`"
            :aria-label="fill(a.events.remove, { name: p.name || p.uuid })"
            @click="remove(ev, p)"
          >
            {{ a.events.removeLabel }}
          </button>
        </li>
      </ul>

      <h3 class="mt-8 font-semibold text-base-50">{{ a.events.lookTitle }}</h3>
      <p class="mt-1 text-sm text-base-400">{{ a.events.lookLead }}</p>
      <form class="mt-3 flex flex-wrap gap-2" @submit.prevent="add(ev, 'look-blocks')">
        <label class="sr-only" :for="`ev-look-${ev.id}`">{{ a.events.name }}</label>
        <input
          :id="`ev-look-${ev.id}`"
          v-model="lookName"
          class="field max-w-56"
          maxlength="16"
          autocomplete="off"
          spellcheck="false"
          :placeholder="a.events.name"
          :aria-label="a.events.name"
        />
        <button type="submit" class="btn btn-primary" :disabled="busy === `a:look-blocks:${ev.id}` || !lookName.trim()">{{ a.events.add }}</button>
      </form>
      <p v-if="!ev.lookBlocked?.length" class="mt-4 text-sm text-base-400">{{ a.events.lookEmpty }}</p>
      <ul v-else class="mt-3 divide-y divide-base-800 text-sm">
        <li v-for="p in ev.lookBlocked" :key="p.uuid" class="flex items-center gap-3 py-2">
          <PlayerHead :uuid="p.uuid" :name="p.name" :size="28" />
          <span class="min-w-0 flex-1 truncate text-base-50">{{ p.name || p.uuid }}</span>
          <span class="text-xs text-base-400">{{ rel(p.addedAt) }}</span>
          <button
            type="button"
            class="btn btn-ghost"
            :disabled="busy === `r:look-blocks:${p.uuid}`"
            :aria-label="fill(a.events.remove, { name: p.name || p.uuid })"
            @click="remove(ev, p, 'look-blocks')"
          >
            {{ a.events.removeLabel }}
          </button>
        </li>
      </ul>
    </section>

    <section v-if="isOwner" class="card mt-6 p-5">
      <h2 class="display text-2xl text-base-50">{{ a.events.scareTitle }}</h2>
      <p class="mt-1 text-sm text-base-400">{{ a.events.scareLead }}</p>
      <form class="mt-3 flex flex-wrap gap-2" @submit.prevent="scare">
        <label class="sr-only" for="scare-target">{{ a.events.scarePlaceholder }}</label>
        <input
          id="scare-target"
          v-model="scareTarget"
          class="field max-w-56"
          maxlength="36"
          autocomplete="off"
          spellcheck="false"
          :placeholder="a.events.scarePlaceholder"
          :aria-label="a.events.scarePlaceholder"
        />
        <button type="submit" class="btn btn-primary" :disabled="scaring || !scareTarget.trim()">{{ a.events.scareButton }}</button>
      </form>
      <p v-if="scareMsg" class="mt-3 text-sm text-base-300">{{ scareMsg }}</p>
    </section>
  </div>
</template>
