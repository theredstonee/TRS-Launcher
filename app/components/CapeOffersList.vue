<script setup lang="ts">
import type { TrsIncomingOffer, TrsOutgoingOffer } from '~/utils/trs'

// Umhang-Angebote von Freunden: Vorschau + Annehmen/Ablehnen. Mit `outgoing`
// auch die eigenen offenen Angebote (zurückziehen). Daten kommen aus dem Store
// (der fragt nach, sobald `GET /v1/friends` neue Angebote meldet).
const props = defineProps<{ outgoing?: boolean; compact?: boolean }>()

const trs = useTrsStore()
const toasts = useToasts()
const busy = ref<string | null>(null)

const incoming = computed(() => trs.capeOffers?.incoming ?? [])
const mine = computed(() => (props.outgoing ? (trs.capeOffers?.outgoing ?? []) : []))

onMounted(() => {
  if (!trs.capeOffers) void trs.loadCapeOffers()
})

async function run(key: string, action: () => Promise<void>) {
  if (busy.value) return
  busy.value = key
  try {
    await action()
  } catch (e) {
    toasts.error(e)
    await trs.loadCapeOffers()
  } finally {
    busy.value = null
  }
}

const accept = (o: TrsIncomingOffer) =>
  run(`accept:${o.cape.id}`, async () => {
    await backend.trs.acceptCapeOffer(o.cape.id)
    toasts.ok(t('capeShare.toasts.accepted', { cape: o.cape.name }))
    await trs.capeSharesChanged()
  })

const decline = (o: TrsIncomingOffer) =>
  run(`decline:${o.cape.id}`, async () => {
    await backend.trs.declineCapeOffer(o.cape.id)
    await trs.capeSharesChanged()
  })

const withdraw = (o: TrsOutgoingOffer) =>
  run(`withdraw:${o.cape.id}:${o.to.uuid}`, async () => {
    await backend.trs.revokeCapeShare(o.cape.id, o.to.uuid)
    toasts.ok(t('capeShare.toasts.withdrawn', { name: o.to.name }))
    await trs.capeSharesChanged()
  })
</script>

<template>
  <div v-if="incoming.length || mine.length" class="space-y-5" data-testid="cape-offers">
    <section v-if="incoming.length">
      <h2 class="section-title mb-2">{{ t('capeShare.offers.incoming', { count: incoming.length }) }}</h2>
      <ul class="space-y-2">
        <li v-for="o in incoming" :key="`${o.cape.id}:${o.from.uuid}`" class="card flex items-center gap-3 px-3 py-2.5">
          <CapeThumb
            :texture="o.cape.texture"
            :scale="o.cape.scale"
            :frames="o.cape.frames"
            :frame-time-ms="o.cape.frameTimeMs"
            :width="compact ? 20 : 26"
            class="shrink-0"
          />
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-semibold text-base-50">{{ o.cape.name }}</p>
            <p class="truncate text-xs text-base-400">
              {{
                o.creator.uuid === o.from.uuid
                  ? t('capeShare.offers.from', { name: o.from.name, date: trsDate(o.createdAt) })
                  : t('capeShare.offers.fromVia', { name: o.from.name, creator: o.creator.name, date: trsDate(o.createdAt) })
              }}
            </p>
          </div>
          <button class="btn btn-primary px-3 py-1.5 text-xs" :disabled="!!busy" @click="accept(o)">
            {{ busy === `accept:${o.cape.id}` ? t('capeShare.offers.accepting') : t('capeShare.offers.accept') }}
          </button>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy" @click="decline(o)">
            {{ t('capeShare.offers.decline') }}
          </button>
        </li>
      </ul>
      <p v-if="!compact" class="mt-2 text-[11px] text-base-600">{{ t('capeShare.offers.hint') }}</p>
    </section>
    <section v-if="mine.length">
      <h2 class="section-title mb-2">{{ t('capeShare.offers.outgoing') }}</h2>
      <ul class="space-y-2">
        <li v-for="o in mine" :key="`${o.cape.id}:${o.to.uuid}`" class="card flex items-center gap-3 px-3 py-2.5">
          <CapeThumb
            :texture="o.cape.texture"
            :scale="o.cape.scale"
            :frames="o.cape.frames"
            :frame-time-ms="o.cape.frameTimeMs"
            :width="20"
            class="shrink-0"
          />
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm font-semibold text-base-50">{{ o.cape.name }}</p>
            <p class="truncate text-xs text-base-400">{{ t('capeShare.offers.to', { name: o.to.name, date: trsDate(o.createdAt) }) }}</p>
          </div>
          <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="!!busy" @click="withdraw(o)">
            {{ t('capeShare.offers.withdraw') }}
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>
