<script setup lang="ts">
import type { TrsHeadCosmetic } from '~/utils/trs'

// Belohnung der Kürbis-Suche: Nach allen 13 Kürbissen darf man genau EINE der vier
// Halloween-Flügel wählen (endgültig, mit Bestätigung). Karten-Bilder kommen aus dem
// Kosmetik-Katalog; anprobieren geht auf der Skins-Seite (Reiter Kosmetik → Flügel).
const halloween = useHalloweenStore()
const toasts = useToasts()
const catalog = ref<TrsHeadCosmetic[]>([])
const confirm = ref<string | null>(null)
const busy = ref(false)

const wings = computed(() => halloween.state?.wings ?? null)
const options = computed(() => (wings.value?.options ?? []).map((id) => ({ id, item: catalog.value.find((c) => c.id === id) ?? null })))
const chosen = computed(() => {
  const id = wings.value?.choice
  return id ? (catalog.value.find((c) => c.id === id)?.name ?? id) : null
})

onMounted(async () => {
  if (halloween.demo) return
  try {
    catalog.value = (await backend.trs.headCosmetics()).filter((c) => c.slot === 'wings')
  } catch {
    // Ohne Katalog: nur die Namen.
  }
})

const nameOf = (id: string) => catalog.value.find((c) => c.id === id)?.name ?? id

async function choose() {
  const id = confirm.value
  if (!id || busy.value) return
  busy.value = true
  try {
    await halloween.chooseWings(id)
    toasts.ok(t('halloween.wings.chosen', { name: nameOf(id) }))
    confirm.value = null
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section v-if="wings && (wings.canChoose || wings.choice)" data-testid="halloween-wings">
    <h3 class="display text-lg text-base-50">{{ t('halloween.wings.title') }}</h3>
    <p v-if="chosen" class="text-sm text-base-200">{{ t('halloween.wings.yours', { name: chosen }) }}</p>
    <template v-else>
      <p class="text-xs text-base-400">{{ t('halloween.wings.text') }}</p>
      <ul class="mt-3 grid grid-cols-4 gap-2 mobile:grid-cols-2">
        <li v-for="o in options" :key="o.id">
          <button
            type="button"
            class="card card-hover flex w-full flex-col items-center gap-1.5 p-2"
            :data-testid="`wings-option-${o.id}`"
            @click="confirm = o.id"
          >
            <img v-if="o.item?.card" :src="o.item.card" alt="" class="aspect-square w-full rounded-md object-cover" draggable="false">
            <span v-else class="grid aspect-square w-full place-items-center rounded-md bg-base-900/60"><HalloweenPixelSprite name="bat" class="h-8 w-12" /></span>
            <span class="w-full truncate text-center text-xs font-medium">{{ o.item?.name ?? o.id }}</span>
          </button>
        </li>
      </ul>
      <p class="mt-2 text-[11px] text-base-400">{{ t('halloween.wings.tryOn') }}</p>
    </template>
    <BaseDialog v-if="confirm" :title="t('halloween.wings.confirmTitle')" @close="confirm = null">
      <p class="text-sm text-base-200">{{ t('halloween.wings.confirmText', { name: nameOf(confirm) }) }}</p>
      <template #actions>
        <button type="button" class="btn btn-ghost" @click="confirm = null">{{ t('common.actions.cancel') }}</button>
        <button type="button" class="btn btn-primary" :disabled="busy" data-testid="wings-confirm" @click="choose">{{ t('halloween.wings.confirm') }}</button>
      </template>
    </BaseDialog>
  </section>
</template>
