<script setup lang="ts">
import { reasonLabel } from '~/utils/sanctions'
import type { AdminSanction } from '~/utils/team'

// Eine Strafe im Verlauf (Akte, Strafenliste): Art, Zeitraum, wer, Grund,
// interne Notiz, Änderungen (verkürzt/verlängert/aufgehoben) und Einspruch.
// Aufgehobene Strafen erscheinen durchgestrichen.
const props = defineProps<{ sanction: AdminSanction; showPlayer?: boolean }>()
const emit = defineEmits<{ lift: []; change: [] }>()
const team = useTeam()

const s = computed(() => props.sanction)
const active = computed(() => s.value.status === 'active')
/** Moderatoren dürfen Strafen von Admins nicht ändern. */
const locked = computed(() => s.value.createdRole === 'admin' && !team.isAdmin.value)
const actorName = (a: { uuid: string; name: string | null } | null) =>
  !a ? '–' : a.uuid === 'system' ? t('team.common.system') : a.uuid === 'api-key' ? t('team.common.apiKey') : (a.name ?? a.uuid)
</script>

<template>
  <article class="rounded-xl border bg-base-900 p-4" :class="active ? 'border-base-700' : 'border-base-800 opacity-80'" :data-sanction="s.id">
    <header class="flex flex-wrap items-center gap-2">
      <SanctionKindBadge :kind="s.kind" :muted="!active" />
      <span class="badge" :class="active ? 'bg-ok/15 text-ok' : s.status === 'lifted' ? 'bg-base-800 text-base-400 line-through' : 'bg-base-800 text-base-400'">
        {{ t(`team.status.${s.status}`) }}
      </span>
      <span v-if="s.auto" class="badge bg-base-800 text-base-200">{{ t('team.sanction.auto') }}</span>
      <span v-if="s.migrated" class="badge bg-base-800 text-base-400">{{ t('team.sanction.migrated') }}</span>
      <NuxtLink v-if="showPlayer" :to="`/admin/players/${s.player.uuid}`" class="flex items-center gap-1.5 text-sm font-semibold text-base-50 hover:text-redstone-300">
        <span class="block size-5 overflow-hidden rounded"><PlayerFace :uuid="s.player.uuid" :name="s.player.name ?? '?'" /></span>
        {{ s.player.name ?? s.player.uuid }}
      </NuxtLink>
      <span class="ml-auto text-[11px] text-base-600">#{{ s.id }}</span>
    </header>

    <p class="mt-2 text-sm" :class="s.status === 'lifted' ? 'text-base-400 line-through' : 'text-base-50'">
      {{ formatDate(s.createdAt) }} → {{ s.endsAt ? formatDate(s.endsAt) : t('team.durations.permanent') }}
      <span v-if="active && s.endsAt" class="text-xs text-base-400 no-underline">({{ formatRelative(s.endsAt, true) }})</span>
    </p>

    <dl class="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
      <dt class="text-base-400">{{ t('team.form.reasonCode') }}</dt>
      <dd class="text-base-100">{{ reasonLabel(s.reasonCode) }}<span v-if="s.reason"> – „{{ s.reason }}“</span></dd>
      <dt class="text-base-400">{{ t('team.sanction.by') }}</dt>
      <dd class="text-base-100">{{ actorName(s.createdBy) }} <span class="text-base-400">({{ t(`team.roles.${s.createdRole}`) }})</span></dd>
      <template v-if="s.note">
        <dt class="text-base-400">{{ t('team.form.note') }}</dt>
        <dd class="whitespace-pre-wrap text-lamp-300">{{ s.note }}</dd>
      </template>
      <template v-if="s.reportId">
        <dt class="text-base-400">{{ t('team.sanction.report') }}</dt>
        <dd><NuxtLink :to="`/admin/reports?open=${s.reportId}`" class="font-mono text-redstone-300 hover:underline">{{ s.reportId }}</NuxtLink></dd>
      </template>
      <template v-if="s.status === 'lifted'">
        <dt class="text-base-400">{{ t('team.sanction.lifted') }}</dt>
        <dd class="text-base-100">
          {{ formatDate(s.liftedAt) }} · {{ actorName(s.liftedBy) }}
          <span v-if="s.liftReason"> – {{ s.liftReason === 'replaced' ? t('team.sanction.replaced') : s.liftReason }}</span>
        </dd>
      </template>
    </dl>

    <ol v-if="s.changes.length" class="mt-3 space-y-1 border-l-2 border-base-700 pl-3 text-xs">
      <li v-for="(c, i) in s.changes" :key="i" class="text-base-200">
        <span class="font-medium" :class="c.action === 'shorten' ? 'text-ok' : c.action === 'extend' ? 'text-lamp-300' : 'text-base-50'">{{ t(`team.changes.${c.action}`) }}</span>
        · {{ formatDate(c.at) }} · {{ actorName(c.actor) }}
        <span v-if="c.action !== 'lift'" class="text-base-400">({{ c.oldEndsAt ? formatDate(c.oldEndsAt) : t('team.durations.permanent') }} → {{ c.newEndsAt ? formatDate(c.newEndsAt) : t('team.durations.permanent') }})</span>
        <span v-if="c.reason"> – {{ c.reason }}</span>
      </li>
    </ol>

    <div v-if="s.appeal" class="mt-3 rounded-lg border border-base-800 bg-base-850 p-3 text-xs">
      <p class="flex items-center gap-2">
        <span class="badge" :class="s.appeal.status === 'open' ? 'bg-lamp-900 text-lamp-300' : 'bg-base-800 text-base-200'">{{ t(`sanctions.appeal.status.${s.appeal.status}`) }}</span>
        <span class="text-base-400">{{ formatDate(s.appeal.createdAt) }}</span>
        <NuxtLink v-if="s.appeal.status === 'open'" to="/admin/appeals" class="ml-auto text-redstone-300 hover:underline">{{ t('team.sanction.decideAppeal') }}</NuxtLink>
      </p>
      <p class="mt-2 whitespace-pre-wrap text-base-100">„{{ s.appeal.text }}“</p>
      <p v-if="s.appeal.response" class="mt-2 whitespace-pre-wrap text-base-200">
        <span class="text-base-400">{{ t('team.sanction.answer', { name: actorName(s.appeal.decidedBy) }) }}</span> {{ s.appeal.response }}
      </p>
    </div>

    <footer v-if="active" class="mt-3 flex flex-wrap items-center justify-end gap-2">
      <span v-if="locked" class="mr-auto text-[11px] text-base-400">{{ t('team.sanction.adminLocked') }}</span>
      <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="locked" data-testid="sanction-change-open" @click="emit('change')">{{ t('team.sanction.changeEnd') }}</button>
      <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="locked" data-testid="sanction-lift-open" @click="emit('lift')">{{ t('team.change.lift') }}</button>
    </footer>
  </article>
</template>
