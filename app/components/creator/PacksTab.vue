<script setup lang="ts">
import type { CreatorPackRef, CreatorSelf } from '~/utils/creator'
import type { OwnPack } from '~/utils/packs'

// Packs: eigene geteilte Modpacks (§27) als Creator-Packs unter „Entdecken“
// listen oder zurückziehen. Gelistete Packs laufen nicht ab. Der Creator sieht
// zusätzlich seine geteilten Packs, die noch nicht in der Liste des Servers
// stehen; Helfer nur die des Creators (§35.9).
const props = defineProps<{ self: CreatorSelf }>()
const store = useCreatorStore()
const packs = usePacksStore()
const toasts = useToasts()

const helper = computed(() => !!props.self.viewer?.helper)
const own = ref<OwnPack[]>([])
onMounted(async () => {
  if (helper.value) return
  try {
    own.value = (await backend.packs.mine()).packs
  } catch {
    // Ohne eigene Liste reicht die des Servers.
  }
})

/** Liste des Servers plus eigene geteilte Packs, die dort fehlen. */
const rows = computed<CreatorPackRef[]>(() => {
  const known = new Set(props.self.packs.map((p) => p.code))
  const extra = own.value
    .filter((p) => !known.has(p.code))
    .map((p) => ({ code: p.code, name: p.name, installs: p.installs, listed: false }))
  return [...props.self.packs, ...extra]
})

const busy = ref<string | null>(null)
async function setListed(p: CreatorPackRef, listed: boolean) {
  busy.value = p.code
  try {
    await backend.creator.setPackListed(p.code, listed, store.actingAs)
    toasts.ok(listed ? t('creator.packs.listedToast', { name: p.name }) : t('creator.packs.unlistedToast', { name: p.name }))
    await store.loadDashboard()
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <div class="space-y-3" data-testid="creator-packs">
    <p class="rounded-lg border-l-2 border-redstone-500 bg-base-900 px-3 py-2 text-xs text-base-400">{{ t('creator.packs.note') }}</p>
    <section class="card p-4">
      <div class="flex flex-wrap items-center gap-2">
        <h3 class="section-title mr-auto">{{ t('creator.packs.title') }}</h3>
        <span class="text-xs text-base-400">{{ t('creator.packs.installs', { n: formatNumber(self.stats.packInstalls) }) }}</span>
        <button v-if="!helper" class="btn btn-ghost px-3 py-1.5 text-xs" @click="packs.mineOpen = true">{{ t('creator.packs.myPacks') }}</button>
      </div>
      <p v-if="!rows.length" class="mt-3 text-sm text-base-400">{{ t('creator.packs.empty') }}</p>
      <ul v-else class="mt-2 divide-y divide-base-800">
        <li v-for="p in rows" :key="p.code" class="flex flex-wrap items-center gap-3 py-2.5">
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-medium text-base-50">{{ p.name }}</p>
            <p class="text-[11px] text-base-400"><span class="font-mono">{{ p.code }}</span> · {{ t('creator.packs.installCount', { n: formatNumber(p.installs) }) }}</p>
          </div>
          <span class="badge" :class="p.listed ? 'bg-ok/15 text-ok' : 'bg-base-800 text-base-400'">{{ p.listed ? t('creator.packs.listed') : t('creator.packs.notListed') }}</span>
          <button class="btn btn-ghost px-3 py-1 text-xs" :disabled="busy === p.code" @click="setListed(p, !p.listed)">
            {{ p.listed ? t('creator.packs.unlist') : t('creator.packs.list') }}
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>
