<script setup lang="ts">
import type { AdminRoom } from '~/utils/team'

// Offene Welten (Welt-Hosting): Host, Version, Spieler, Mitglieder. Schließen
// beendet die Welt für alle; wer draußen bleiben soll, bekommt eine Hosting-Sperre.
const toasts = useToasts()
const rooms = ref<AdminRoom[] | null>(null)
const closing = ref<AdminRoom | null>(null)
const busy = ref(false)

async function load() {
  try {
    rooms.value = (await backend.team.rooms()).rooms
  } catch (e) {
    rooms.value = []
    toasts.error(e)
  }
}
onMounted(load)

async function close(reason: string) {
  const r = closing.value
  if (!r) return
  busy.value = true
  try {
    await backend.team.closeRoom(r.id, reason || null)
    toasts.ok(t('team.worlds.closed', { name: r.name }))
    closing.value = null
    await load()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}
const items = computed(() => rooms.value ?? [])
const { active } = useListKeys(items, { onR: (r) => (closing.value = r) })
</script>

<template>
  <section :aria-label="t('team.nav.worlds')" data-testid="admin-worlds">
    <div class="mb-4 flex items-center gap-2">
      <p class="flex-1 text-xs text-base-400">{{ t('team.worlds.lead') }}</p>
      <button class="btn btn-ghost px-3 py-1.5 text-xs" @click="load">{{ t('common.actions.refresh') }}</button>
    </div>
    <div v-if="!rooms" class="skeleton h-40" />
    <RedstoneEmpty v-else-if="!rooms.length" :title="t('team.worlds.none')" compact :seed="0x7e" />
    <div v-else class="card overflow-x-auto">
      <table class="w-full text-left text-sm">
        <thead class="text-xs text-base-400">
          <tr class="border-b border-base-800">
            <th class="px-4 py-2 font-medium">{{ t('team.worlds.world') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('team.worlds.host') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('team.worlds.game') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('team.worlds.players') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('team.worlds.members') }}</th>
            <th class="px-3 py-2 font-medium">{{ t('team.worlds.since') }}</th>
            <th class="px-3 py-2" />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in rooms" :key="r.id" :data-row="i" class="adm-row border-b border-base-800/60 last:border-0" :class="{ 'adm-row-active': active === i }">
            <td class="px-4 py-2">
              <p class="font-semibold text-base-50">{{ r.name }}</p>
              <p class="font-mono text-[11px] text-base-400">{{ r.code }} · {{ t(`team.worlds.visibility.${r.visibility === 'invited' ? 'invited' : 'friends'}`) }}{{ r.open ? '' : ` · ${t('team.worlds.locked')}` }}</p>
            </td>
            <td class="px-3 py-2">
              <NuxtLink :to="`/admin/players/${r.host.uuid}`" class="flex items-center gap-2 text-base-100 hover:text-redstone-300">
                <span class="block size-5 overflow-hidden rounded"><PlayerFace :uuid="r.host.uuid" :name="r.host.name || '?'" /></span>{{ r.host.name }}
              </NuxtLink>
            </td>
            <td class="px-3 py-2 text-xs text-base-200">{{ r.mcVersion }} · {{ r.loader }}</td>
            <td class="px-3 py-2 text-xs text-base-50 tabular-nums">{{ r.players }} / {{ r.maxPlayers }}</td>
            <td class="px-3 py-2 text-xs text-base-400">{{ t('team.worlds.memberLine', r.members) }}</td>
            <td class="px-3 py-2 text-xs text-base-400">{{ formatRelative(r.createdAt) }}</td>
            <td class="px-3 py-2 text-right"><button class="btn btn-ghost px-2.5 py-1 text-xs hover:text-redstone-300" @click="closing = r">{{ t('team.worlds.close') }}</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <AdminConfirm
      v-if="closing"
      :title="t('team.worlds.closeTitle', { name: closing.name })"
      :text="t('team.worlds.closeText')"
      :confirm-label="t('team.worlds.close')"
      danger
      :busy="busy"
      :reason="{ label: t('team.worlds.reason'), max: 200 }"
      @confirm="close"
      @close="closing = null"
    />
  </section>
</template>
