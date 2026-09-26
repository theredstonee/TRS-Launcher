<script setup lang="ts">
// Eine Strafe mit Art, Status, Grund, Zeiten, Bearbeiter, Einspruch und Verlauf; Aufheben/Dauer ändern.
// Aufgehobene bleiben sichtbar (durchgestrichen), abgelaufene sind grau markiert.
const props = withDefaults(defineProps<{ sanction: AdminSanction, showPlayer?: boolean, actions?: boolean, active?: boolean }>(), {
  showPlayer: false,
  actions: true,
  active: false,
})
const emit = defineEmits<{ changed: [sanction: AdminSanction] }>()
const { a, fill, when, rel, actor } = useAdminText()
const { isAdmin } = useAdmin()

const dialog = ref<'lift' | 'duration' | null>(null)
const open = ref(false)
const s = computed(() => props.sanction)
const canModify = computed(() => s.value.status === 'active' && (isAdmin.value || (s.value.createdRole !== 'admin' && s.value.kind !== 'account_ban')))
const endText = computed(() => {
  const x = s.value
  if (x.status === 'lifted') return fill(a.value.common.ended, { date: when(x.liftedAt) })
  if (!x.endsAt) return x.auto === 'reports' ? a.value.common.untilReview : a.value.common.permanent
  return x.status === 'expired' ? fill(a.value.common.ended, { date: when(x.endsAt) }) : `${fill(a.value.common.ends, { date: when(x.endsAt) })} (${rel(x.endsAt)})`
})
function done(next: AdminSanction) {
  dialog.value = null
  emit('changed', next)
}
</script>

<template>
  <article class="adm-row flex-col" :class="{ 'adm-lifted': s.status === 'lifted' }" :data-active="active">
    <div class="flex w-full flex-wrap items-center gap-2">
      <span class="tone" :class="kindTone(s.kind)">{{ a.kinds[s.kind] }}</span>
      <span class="tone" :class="s.status === 'active' ? 'tone-danger' : s.status === 'lifted' ? 'tone-lifted' : 'tone-muted'">{{ a.status[s.status] }}</span>
      <span v-if="s.appeal" class="tone" :class="s.appeal.status === 'open' ? 'tone-warn' : 'tone-muted'">{{ a.appealStatus[s.appeal.status] }}</span>
      <span v-if="s.migrated" class="tone tone-muted">{{ a.sanctions.migrated }}</span>
      <NuxtLink v-if="showPlayer" :to="`/admin/players/${s.player.uuid}`" class="font-semibold text-base-50 hover:underline">{{ s.player.name || s.player.uuid.slice(0, 8) }}</NuxtLink>
      <span class="adm-strike text-sm text-base-200">{{ a.reasons[s.reasonCode] ?? s.reasonCode }}</span>
      <span class="ml-auto text-xs text-base-400">#{{ s.id }}</span>
    </div>
    <p v-if="s.reason" class="adm-strike text-sm text-base-100">„{{ s.reason }}“</p>
    <div class="flex w-full flex-wrap gap-x-4 gap-y-1 text-xs text-base-400">
      <span>{{ when(s.createdAt) }} · {{ fill(a.common.by, { name: actor(s.createdBy) }) }}<span v-if="s.createdRole !== 'system'"> ({{ a.role[s.createdRole] }})</span></span>
      <span :class="s.status === 'active' ? 'text-base-200' : ''">{{ endText }}</span>
      <NuxtLink v-if="s.reportId" :to="`/admin/reports/${s.reportId}`" class="text-redstone-300 hover:underline">{{ a.sanctions.reportLink }} {{ s.reportId }}</NuxtLink>
    </div>
    <p v-if="s.note" class="adm-note w-full rounded-md bg-base-950 px-3 py-2 text-xs text-base-200"><SiteIcon name="note" class="mr-1 inline size-3.5 align-[-2px] text-base-400" />{{ s.note }}</p>
    <p v-if="s.status === 'lifted' && s.liftReason" class="text-xs text-base-400">{{ a.status.lifted }}: {{ s.liftReason === 'replaced' ? a.sanctions.replaced : s.liftReason }} · {{ actor(s.liftedBy) }}</p>

    <details v-if="s.changes.length || s.appeal" class="w-full text-xs" :open="open" @toggle="open = ($event.target as HTMLDetailsElement).open">
      <summary class="cursor-pointer text-base-300 select-none">{{ a.sanctions.history }} ({{ s.changes.length + (s.appeal ? 1 : 0) }})</summary>
      <ol class="mt-2 space-y-1.5 border-l border-base-800 pl-3">
        <li v-if="s.appeal" class="text-base-300">
          <span class="text-base-400">{{ when(s.appeal.createdAt) }}</span> · {{ a.appeals.text }}: <span class="adm-note text-base-200">{{ s.appeal.text }}</span>
          <span v-if="s.appeal.response" class="block text-base-400">→ {{ s.appeal.response }} ({{ actor(s.appeal.decidedBy) }})</span>
        </li>
        <li v-for="(c, i) in s.changes" :key="i" class="text-base-300">
          <span class="text-base-400">{{ when(c.at) }}</span> · {{ a.changes[c.action] }} · {{ actor(c.actor) }}
          <span v-if="c.action !== 'lift'"> · {{ c.oldEndsAt ? when(c.oldEndsAt) : a.common.permanent }} → {{ c.newEndsAt ? when(c.newEndsAt) : a.common.permanent }}</span>
          <span class="block text-base-400">{{ c.reason }}</span>
        </li>
      </ol>
    </details>

    <div v-if="actions && canModify" class="flex flex-wrap gap-2">
      <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="dialog = 'duration'"><SiteIcon name="clock" class="size-3.5" />{{ a.sanctions.change }}</button>
      <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="dialog = 'lift'"><SiteIcon name="check" class="size-3.5" />{{ a.sanctions.lift }}</button>
    </div>
    <SanctionChangeDialog v-if="dialog" :sanction="s" :mode="dialog" @close="dialog = null" @done="done" />
  </article>
</template>
