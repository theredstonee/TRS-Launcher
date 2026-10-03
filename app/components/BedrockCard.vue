<script setup lang="ts">
// Bibliothekskachel für Minecraft Bedrock (Microsoft Store): klar als Bedrock gekennzeichnet,
// ohne Instanz-Einstellungen – nur Starten über die Store-App.
const bedrock = useBedrockStore()
</script>

<template>
  <article
    class="card group relative flex flex-col transition-[border-color,background-color,transform] hover:-translate-y-0.5 hover:border-base-700 hover:bg-base-850"
    data-testid="bedrock-card"
  >
    <div class="relative h-28 overflow-hidden rounded-t-xl bg-gradient-to-br from-emerald-900 via-base-850 to-base-900">
      <svg viewBox="0 0 24 24" class="absolute -right-3 -bottom-3 size-24 text-emerald-300/15" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round" aria-hidden="true">
        <path d="m12 2 9 5v10l-9 5-9-5V7zM3 7l9 5 9-5M12 12v10" />
      </svg>
      <span class="badge absolute top-2 left-2 bg-emerald-400 text-base-950">{{ t('bedrock.label') }}</span>
      <button
        class="absolute right-2 -bottom-4 grid size-11 place-items-center rounded-full bg-redstone-500 text-white shadow-lg shadow-black/50 transition-all hover:bg-redstone-400 disabled:opacity-60"
        :disabled="bedrock.starting"
        :aria-label="t('bedrock.playLabel')"
        :title="t('bedrock.playLabel')"
        data-testid="bedrock-play"
        @click="bedrock.launch()"
      >
        <svg viewBox="0 0 24 24" class="ml-0.5 size-5" fill="currentColor"><path d="M7 4v16l13-8z" /></svg>
      </button>
    </div>
    <div class="px-2.5 pt-3 pb-2.5">
      <p class="truncate font-semibold text-base-50">{{ t('bedrock.title') }}</p>
      <p class="mt-0.5 truncate text-xs text-base-400">{{ t('bedrock.subtitle') }}</p>
      <p class="mt-0.5 truncate text-[11px] text-base-600">{{ t('bedrock.hint') }}</p>
    </div>
  </article>
</template>
