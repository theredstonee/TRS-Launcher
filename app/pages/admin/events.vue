<script setup lang="ts">
import { z } from 'zod'
import type { TrsAdminEvent } from '~/utils/trs'
import { isEventPlayer } from '~/utils/halloween'

// Team-Bereich Events (API §31, Recht events.manage): global an/aus und Allowlist.
// Der Server prüft das Recht selbst – hier nur die Oberfläche.
const toasts = useToasts()
const team = useTeam()

const events = ref<TrsAdminEvent[] | null>(null)
const shown = computed(() => events.value ?? [])
const names = reactive<Record<string, string>>({})
const formError = ref<Record<string, string>>({})
const busy = ref<string | null>(null)
const removing = ref<{ eventId: string; uuid: string; name: string } | null>(null)

const playerSchema = z.string().trim().refine(isEventPlayer)

function titleOf(id: string): string {
  return id === 'halloween' ? t('admin.events.names.halloween') : id
}

function when(value: string | number | null): string {
  if (value == null || value === '') return '–'
  if (typeof value === 'number') {
    const ms = value < 1e12 ? value * 1000 : value
    return formatShortDate(new Date(ms).toISOString())
  }
  return formatShortDate(value)
}

async function load() {
  try {
    events.value = await backend.trs.adminEvents()
  } catch (e) {
    events.value = []
    toasts.error(e)
  }
}
onMounted(load)

async function toggle(ev: TrsAdminEvent) {
  if (!team.can('events.manage') || busy.value) return
  busy.value = ev.id
  try {
    await backend.trs.adminSetEvent(ev.id, !ev.enabled)
    toasts.ok(t(ev.enabled ? 'admin.toasts.eventOff' : 'admin.toasts.eventOn'))
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

async function add(ev: TrsAdminEvent) {
  if (!team.can('events.manage') || busy.value) return
  const parsed = playerSchema.safeParse(names[ev.id] ?? '')
  if (!parsed.success) {
    formError.value = { ...formError.value, [ev.id]: t('admin.events.invalidPlayer') }
    return
  }
  formError.value = { ...formError.value, [ev.id]: '' }
  busy.value = ev.id
  try {
    await backend.trs.adminAddEventPlayer(ev.id, parsed.data)
    names[ev.id] = ''
    toasts.ok(t('admin.toasts.eventPlayerAdded', { name: parsed.data }))
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}

async function remove() {
  const target = removing.value
  if (!target || busy.value) return
  busy.value = target.uuid
  try {
    await backend.trs.adminRemoveEventPlayer(target.eventId, target.uuid)
    toasts.ok(t('admin.toasts.eventPlayerRemoved', { name: target.name }))
    removing.value = null
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <section class="space-y-4" :aria-label="t('team.nav.events')" data-testid="admin-events">
    <p class="text-sm text-base-300">{{ t('admin.events.intro') }}</p>
    <div v-if="!events" class="skeleton h-40" />
    <RedstoneEmpty v-else-if="!shown.length" :title="t('admin.events.empty')" compact :seed="0x1a11" />
    <template v-else>
    <article v-for="ev in shown" :key="ev.id" class="card space-y-4 p-4" :data-event="ev.id">
      <header class="flex flex-wrap items-center gap-3">
        <h2 class="text-base font-semibold text-base-50">{{ titleOf(ev.id) }}</h2>
        <span class="badge" :class="ev.enabled ? 'bg-ok/15 text-ok' : 'bg-base-800 text-base-300'">
          {{ ev.enabled ? t('admin.events.enabled') : t('admin.events.disabled') }}
        </span>
        <span v-if="ev.updatedAt != null" class="text-xs text-base-400">{{ when(ev.updatedAt) }}</span>
        <button
          v-if="team.can('events.manage')"
          class="btn btn-primary ml-auto px-3 py-1.5 text-xs"
          :disabled="busy === ev.id"
          @click="toggle(ev)"
        >
          {{ ev.enabled ? t('admin.events.disable') : t('admin.events.enable') }}
        </button>
      </header>
      <p class="text-xs text-base-400">{{ t('admin.events.globalHint') }}</p>

      <form v-if="team.can('events.manage')" class="flex flex-wrap items-end gap-2" @submit.prevent="add(ev)">
        <label class="min-w-52 flex-1">
          <span class="label">{{ t('admin.events.players') }}</span>
          <input
            v-model="names[ev.id]"
            class="field"
            maxlength="36"
            autocomplete="off"
            :placeholder="t('admin.events.playerPlaceholder')"
          />
        </label>
        <button class="btn btn-primary px-3 py-2 text-xs" :disabled="busy === ev.id">
          {{ busy === ev.id ? t('admin.events.adding') : t('admin.events.add') }}
        </button>
        <p v-if="formError[ev.id]" role="alert" class="w-full text-xs text-redstone-300">{{ formError[ev.id] }}</p>
      </form>

      <p v-if="!ev.players.length" class="text-sm text-base-400">{{ t('admin.events.noPlayers') }}</p>
      <ul v-else class="divide-y divide-base-800">
        <li v-for="p in ev.players" :key="p.uuid" class="flex items-center gap-3 py-2 text-sm">
          <span class="min-w-0 flex-1 truncate text-base-50">{{ p.name || p.uuid }}</span>
          <span class="font-mono text-[11px] text-base-400">{{ p.uuid }}</span>
          <span class="text-xs text-base-400">{{ when(p.addedAt) }}</span>
          <button
            v-if="team.can('events.manage')"
            class="btn btn-ghost px-2 py-0.5 text-[11px] hover:text-redstone-300"
            @click="removing = { eventId: ev.id, uuid: p.uuid, name: p.name || p.uuid }"
          >
            {{ t('admin.events.remove') }}
          </button>
        </li>
      </ul>
    </article>
    </template>

    <BaseDialog v-if="removing" :title="t('admin.events.removeTitle')" @close="removing = null">
      <i18n-t keypath="admin.events.removeText" tag="p" scope="global" class="text-sm text-base-200">
        <template #name><strong class="text-base-50">{{ removing.name }}</strong></template>
      </i18n-t>
      <template #actions>
        <button class="btn btn-ghost" @click="removing = null">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="!!busy" @click="remove">{{ t('admin.events.remove') }}</button>
      </template>
    </BaseDialog>
  </section>
</template>
