<script setup lang="ts">
// Zeigt statt des Inhalts einen ruhigen Hinweis, solange die TRS-Dienste nicht
// nutzbar sind: nicht zugestimmt, kein Account, gesperrt oder offline.
defineProps<{ what: string }>()
const trs = useTrsStore()
const accounts = useAccountsStore()

const state = computed(() => {
  if (!trs.status) return 'loading'
  if (!trs.enabled) return 'consent'
  if (!accounts.active) return 'account'
  if (trs.problem === 'banned') return 'banned'
  return 'ok'
})
</script>

<template>
  <slot v-if="state === 'ok'" />
  <div v-else-if="state === 'loading'" class="skeleton h-28" />
  <div v-else class="card flex items-start gap-3 px-4 py-4">
    <span class="grid size-9 shrink-0 place-items-center rounded-lg bg-redstone-900/40 text-redstone-300">
      <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path :d="icons.trs" />
      </svg>
    </span>
    <div class="min-w-0 flex-1 text-sm">
      <template v-if="state === 'consent'">
        <p class="font-semibold text-base-50">TRS-Dienste sind ausgeschaltet</p>
        <p class="mt-0.5 text-base-400">
          Für {{ what }} meldet sich der Launcher beim TRS-Server an. Das passiert erst, wenn du zustimmst.
        </p>
        <button class="btn btn-primary mt-3 px-3 py-1.5 text-xs" @click="trs.askConsent()">Mehr erfahren &amp; einschalten</button>
      </template>
      <template v-else-if="state === 'account'">
        <p class="font-semibold text-base-50">Kein Account angemeldet</p>
        <p class="mt-0.5 text-base-400">Melde dich unter „Accounts“ mit deinem Minecraft-Konto an.</p>
        <NuxtLink to="/accounts" class="btn btn-ghost mt-3 px-3 py-1.5 text-xs">Zu den Accounts</NuxtLink>
      </template>
      <template v-else>
        <p class="font-semibold text-base-50">Dein Konto ist für die TRS-Dienste gesperrt</p>
        <p class="mt-0.5 text-base-400">Umhänge und Freunde sind deshalb nicht verfügbar.</p>
      </template>
    </div>
  </div>
</template>
