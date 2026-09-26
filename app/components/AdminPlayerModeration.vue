<script setup lang="ts">
import { muteDurations, type AdminModeration } from '~/utils/moderation'

// Admin → Spieler: Chat-Moderation eines Spielers – Stummschaltung (mit
// Dauer), Verwarnung, frühere Maßnahmen und Meldungs-Zahlen (API §20.5).
const props = defineProps<{ uuid: string }>()
const toasts = useToasts()

const moderation = ref<AdminModeration | null>(null)
const reason = ref('')
const minutes = ref<number | null>(1440)
const busy = ref(false)
const error = ref<string | null>(null)

async function load() {
  try {
    moderation.value = (await backend.social.adminModerationUser(props.uuid)).moderation
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function run(action: () => Promise<{ moderation: AdminModeration }>, done: string) {
  if (busy.value) return
  busy.value = true
  error.value = null
  try {
    moderation.value = (await action()).moderation
    reason.value = ''
    toasts.ok(done)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

const mute = () => run(() => backend.social.adminMute(props.uuid, minutes.value, reason.value.trim() || null), t('admin.mod.toasts.muted'))
const unmute = () => run(() => backend.social.adminUnmute(props.uuid), t('admin.mod.toasts.unmuted'))
function warn() {
  if (!reason.value.trim()) {
    error.value = t('admin.mod.warnNeedsReason')
    return
  }
  void run(() => backend.social.adminWarn(props.uuid, reason.value.trim()), t('admin.mod.toasts.warned'))
}

function durationLabel(m: number | null): string {
  switch (m) {
    case 60:
      return t('admin.mod.durations.d60')
    case 1440:
      return t('admin.mod.durations.d1440')
    case 10_080:
      return t('admin.mod.durations.d10080')
    case 43_200:
      return t('admin.mod.durations.d43200')
    default:
      return t('admin.mod.durations.forever')
  }
}

onMounted(load)
</script>

<template>
  <div class="border-t border-base-800 pt-3" data-testid="admin-player-moderation">
    <p class="label">{{ t('admin.mod.chatModeration') }}</p>
    <div v-if="!moderation && !error" class="skeleton h-16" />
    <template v-else-if="moderation">
      <p class="text-xs text-base-200">{{ t('admin.mod.targetStats', moderation.reportsAgainst) }}</p>
      <p class="mt-1 text-xs text-base-400">
        {{ t('admin.mod.filedStats', { total: moderation.reportsFiled.total, actioned: moderation.reportsFiled.actioned, dismissed: moderation.reportsFiled.dismissed }) }}
        <span v-if="moderation.reportsFiled.lowTrust" class="badge ml-1 bg-lamp-900 text-lamp-300">{{ t('admin.mod.lowTrust') }}</span>
      </p>
      <p v-if="moderation.mute" class="mt-2 flex flex-wrap items-center gap-2 text-xs text-lamp-300">
        {{ moderation.mute.expiresAt ? t('admin.mod.mutedUntil', { date: dateTime(moderation.mute.expiresAt) }) : t('admin.mod.mutedReview') }}
        <button class="btn btn-ghost px-2 py-0.5 text-xs" :disabled="busy" @click="unmute">{{ t('admin.mod.unmute') }}</button>
      </p>
      <div class="mt-3 flex flex-wrap gap-2">
        <input v-model="reason" class="field min-w-0 flex-1 py-1.5" maxlength="200" :placeholder="t('admin.mod.reason')" :aria-label="t('admin.mod.reason')" />
        <select v-model="minutes" class="field w-40 py-1.5" :aria-label="t('admin.mod.duration')">
          <option v-for="d in muteDurations" :key="String(d)" :value="d">{{ durationLabel(d) }}</option>
        </select>
        <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="busy" @click="warn">{{ t('admin.mod.warn') }}</button>
        <button class="btn btn-danger px-3 py-1.5 text-xs" :disabled="busy" @click="mute">{{ t('admin.mod.mute') }}</button>
      </div>
      <details v-if="moderation.sanctions.length" class="mt-2 text-xs">
        <summary class="cursor-pointer text-base-200">{{ t('admin.mod.history') }}</summary>
        <ul class="mt-1 space-y-1 text-base-400">
          <li v-for="s in moderation.sanctions" :key="s.id">
            {{ dateTime(s.createdAt) }} · {{ t(`admin.mod.sanction.${s.kind}`) }}<span v-if="s.auto"> ({{ t('admin.mod.auto') }})</span>
            <span v-if="s.reason"> · {{ s.reason }}</span><span v-if="s.liftedAt"> · {{ t('admin.mod.lifted') }}</span>
          </li>
        </ul>
      </details>
    </template>
    <p v-if="error" role="alert" class="mt-2 text-xs text-redstone-300">{{ error }}</p>
  </div>
</template>
