<script setup lang="ts">
import { achievementIcon } from '~/utils/achievements'

// Symbol eines Erfolgs: Linien-Icon (bekannter Name) oder Emoji der API, sonst der Pokal.
const props = defineProps<{ icon: string }>()
const shown = computed(() => achievementIcon(props.icon))
</script>

<template>
  <span v-if="'emoji' in shown" class="leading-none" aria-hidden="true">{{ shown.emoji }}</span>
  <SocialIcon v-else :name="shown.icon" />
</template>
