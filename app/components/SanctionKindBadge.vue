<script setup lang="ts">
import { kindLabel, type SanctionKind } from '~/utils/sanctions'

// Art einer Strafe als farbiges Abzeichen (Spieler-Ansicht und Team-Bereich).
const props = defineProps<{ kind: SanctionKind; muted?: boolean }>()

const tones: Record<SanctionKind, string> = {
  warn: 'bg-lamp-900 text-lamp-300',
  chat_mute: 'bg-sky-500/15 text-sky-300',
  social_ban: 'bg-violet-500/15 text-violet-300',
  upload_ban: 'bg-teal-500/15 text-teal-300',
  hosting_ban: 'bg-emerald-500/15 text-emerald-300',
  account_ban: 'bg-redstone-600/30 text-redstone-300',
}
const tone = computed(() => (props.muted ? 'bg-base-800 text-base-400' : tones[props.kind]))
</script>

<template>
  <span class="badge" :class="tone">{{ kindLabel(kind) }}</span>
</template>
