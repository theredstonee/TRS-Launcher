<script setup lang="ts">
import { localText, nextTrickAt, type HalloweenReward, type TrickOutcome } from '~/utils/halloween'
import type { SpriteName } from '~/utils/moonnight/sprites'

// „Süßes oder Saures?“ und Kürbis-Suche (Entwurf Event-Inhalte): Eimer wackelt, dann Süßes
// (Belohnung aus dem Pool) oder Saures (der Kürbis streckt die Zunge raus). Einmal am Tag;
// der Server würfelt. Darunter die 13 Kürbisse und das nächste Rätsel.
const emit = defineEmits<{ close: [] }>()
const halloween = useHalloweenStore()
const { reduced } = useMotion()

const phase = ref<'idle' | 'knocking' | 'done'>('idle')
const outcome = ref<TrickOutcome | null>(null)
const failure = ref<string | null>(null)

const hint = computed(() => localText(halloween.state?.pumpkins.nextHint, currentLocale.value))
const slots = computed(() => Array.from({ length: halloween.total }, (_, i) => i < halloween.found))
const next = computed(() => nextTrickAt(halloween.state))
const nextText = computed(() => (next.value ? t('halloween.trick.nextAt', { time: formatTime(next.value) }) : t('halloween.trick.tomorrow')))
/** Heute schon geklopft (vor dem Öffnen) – letztes Ergebnis zeigen. */
const earlier = computed(() => (phase.value === 'idle' && !halloween.trickOpen && halloween.state?.trick.result ? halloween.state.trick : null))
const shown = computed(() => outcome.value ?? (earlier.value ? { result: earlier.value.result!, reward: earlier.value.reward, next: earlier.value.next } : null))

function kindLabel(r: HalloweenReward): string {
  if (r.kind === 'cape') return t('halloween.trick.kind.cape')
  if (r.kind === 'emote') return t('halloween.trick.kind.emote')
  if (r.kind === 'sticker') return t('halloween.trick.kind.sticker')
  return t('halloween.trick.kind.other')
}
function spriteFor(r: HalloweenReward | null): SpriteName {
  if (!r) return 'ghost'
  if (r.kind === 'cape') return 'moonbat'
  if (r.kind === 'emote') return 'ghost'
  return 'pumpkin'
}

async function knock() {
  if (phase.value === 'knocking' || !halloween.trickOpen) return
  phase.value = 'knocking'
  failure.value = null
  outcome.value = null
  const started = Date.now()
  try {
    const r = await halloween.trick()
    // Eimer erst ausschütteln lassen (Entwurf: 0,9 s).
    const wait = reduced.value ? 0 : Math.max(0, 900 - (Date.now() - started))
    await new Promise((resolve) => setTimeout(resolve, wait))
    outcome.value = r
    if (r.reward?.kind === 'sticker') await halloween.loadStickers(true)
  } catch (e) {
    failure.value = errorMessage(e)
    void halloween.load()
  } finally {
    phase.value = 'done'
  }
}
</script>

<template>
  <BaseDialog :title="t('halloween.card.title')" wide @close="emit('close')">
    <div class="space-y-6">
      <section>
        <h3 class="display text-lg text-base-50">{{ t('halloween.trick.title') }}</h3>
        <div class="mt-2 grid grid-cols-[150px_1fr] items-center gap-4 mobile:grid-cols-1">
          <div class="bucket" :class="{ shake: phase === 'knocking' && !reduced, sour: shown?.result === 'sour' && !reduced }">
            <HalloweenPixelSprite name="pumpkin" class="bucket-img" />
            <span class="bubble" :class="{ show: shown?.result === 'sour' }">{{ t('halloween.trick.sourBubble') }}</span>
          </div>
          <div class="min-h-28" aria-live="polite">
            <p v-if="phase === 'knocking'" class="text-sm text-base-400">{{ t('halloween.trick.knocking') }}</p>
            <div v-else-if="shown?.result === 'sweet'" class="prize">
              <HalloweenSticker v-if="shown.reward?.kind === 'sticker'" :id="shown.reward.id" :size="48" />
              <HalloweenPixelSprite v-else :name="spriteFor(shown.reward)" class="size-12" />
              <div class="min-w-0">
                <p class="kind">{{ t('halloween.trick.sweet') }}<template v-if="shown.reward"> · {{ kindLabel(shown.reward) }}</template></p>
                <p class="display truncate text-xl text-base-50">{{ shown.reward ? localText(shown.reward.name, currentLocale) || shown.reward.id : t('halloween.trick.sweet') }}</p>
                <p class="text-xs text-base-400">{{ t('halloween.trick.yours') }}</p>
              </div>
            </div>
            <div v-else-if="shown?.result === 'sour'" class="prize">
              <HalloweenPixelSprite name="ghost" class="size-12" />
              <div class="min-w-0">
                <p class="kind">{{ t('halloween.trick.sour') }}</p>
                <p class="display text-xl text-base-50">{{ t('halloween.trick.nothing') }}</p>
                <p class="text-xs text-base-400">{{ t('halloween.trick.giggles') }}</p>
              </div>
            </div>
            <p v-else class="text-sm text-base-400">{{ t('halloween.trick.intro') }}</p>
            <p v-if="failure" role="alert" class="mt-2 text-sm text-redstone-300">{{ failure }}</p>
            <div class="mt-3 flex flex-wrap items-center gap-3">
              <button type="button" class="btn btn-primary" :disabled="!halloween.trickOpen || phase === 'knocking'" data-testid="halloween-knock" @click="knock">
                {{ t('halloween.trick.knock') }}
              </button>
              <span v-if="!halloween.trickOpen" class="text-xs text-base-400">{{ nextText }}</span>
            </div>
          </div>
        </div>
        <div class="mt-3 flex flex-wrap gap-1.5">
          <span class="pool">{{ t('halloween.trick.pool.cape') }}</span>
          <span class="pool">{{ t('halloween.trick.pool.emotes') }}</span>
          <span class="pool">{{ t('halloween.trick.pool.stickers') }}</span>
          <span class="pool">{{ t('halloween.trick.pool.sour') }}</span>
        </div>
      </section>

      <section>
        <h3 class="display text-lg text-base-50">{{ t('halloween.hunt.title') }}</h3>
        <p class="text-xs text-base-400">{{ t('halloween.hunt.text') }}</p>
        <div class="hunt">
          <div v-for="(f, i) in slots" :key="i" class="slot" :class="{ f }">
            <HalloweenPixelSprite name="pumpkin" class="h-[70%] w-[70%]" :class="{ 'hw-unfound': !f }" />
          </div>
        </div>
        <p class="text-sm text-base-200">{{ t('halloween.card.hunt', { n: halloween.found, total: halloween.total }) }}</p>
        <p v-if="hint" class="hw-italic mt-2 text-lg text-redstone-300">„{{ hint }}“</p>
        <p v-else-if="halloween.found >= halloween.total" class="mt-2 text-sm text-base-200">{{ t('halloween.hunt.complete') }}</p>
        <div class="mt-3 flex flex-wrap items-center gap-2 text-xs text-base-400">
          <span>{{ t('halloween.hunt.rewardLabel') }}</span>
          <span class="hw-badge"><HalloweenPixelSprite name="pk8" class="h-[18px] w-5" />{{ t('halloween.badges.pumpkinhunter2026') }}</span>
          <span>{{ t('halloween.hunt.rewardCape') }}</span>
        </div>
      </section>

      <HalloweenWingsChoice />
    </div>
    <template #actions>
      <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.close') }}</button>
    </template>
  </BaseDialog>
</template>

<style scoped>
.bucket {
  position: relative;
  display: grid;
  width: 150px;
  height: 150px;
  place-items: center;
}
.bucket-img {
  width: 120px;
  height: 120px;
  filter: drop-shadow(0 0 22px rgba(224, 138, 60, 0.35));
}
.bucket.shake .bucket-img {
  animation: hw-shake 0.12s linear 7;
}
.bucket.sour .bucket-img {
  animation: hw-sour 1.4s ease forwards;
}
@keyframes hw-shake {
  25% { transform: rotate(-6deg); }
  75% { transform: rotate(6deg); }
}
@keyframes hw-sour {
  20% { transform: scale(1.1, 0.8); }
  45% { transform: scale(0.9, 1.12) rotate(-8deg); }
  70% { transform: rotate(6deg); }
  100% { transform: none; filter: drop-shadow(0 0 22px rgba(159, 214, 230, 0.35)) hue-rotate(60deg) saturate(0.6); }
}
.bubble {
  position: absolute;
  top: 0;
  right: -6px;
  padding: 4px 9px;
  border-radius: 10px;
  background: #f3f5fb;
  color: #1a1426;
  font-family: var(--font-display);
  font-weight: 600;
  transform: scale(0);
  transition: transform 0.3s cubic-bezier(0.2, 0.9, 0.3, 1.4);
}
.bubble.show {
  transform: scale(1);
}
.prize {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px;
  border: 1px solid var(--color-base-600);
  border-radius: 12px;
  background: rgb(7 6 12 / 0.5);
  animation: hw-rise 0.5s ease;
}
.prize .kind {
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--color-redstone-300);
}
@keyframes hw-rise {
  from { transform: translateY(10px); opacity: 0; }
}
:root[data-reduced-motion] .prize {
  animation: none;
}
.pool {
  padding: 2px 9px;
  border: 1px solid var(--color-base-600);
  border-radius: 99px;
  font-size: 12px;
  color: var(--color-base-400);
}
.hunt {
  display: grid;
  grid-template-columns: repeat(13, 1fr);
  gap: 6px;
  margin: 12px 0;
}
.slot {
  display: grid;
  aspect-ratio: 1;
  place-items: center;
  border: 1px dashed var(--color-base-600);
  border-radius: 8px;
  background: rgb(7 6 12 / 0.5);
}
.slot.f {
  border: 1px solid rgba(224, 138, 60, 0.45);
  background: radial-gradient(circle, rgba(224, 138, 60, 0.18), transparent 70%);
}
.hw-unfound {
  filter: brightness(0) invert(0.18);
}
.hw-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px 4px 8px;
  border: 1px solid rgba(224, 138, 60, 0.5);
  border-radius: 99px;
  background: linear-gradient(90deg, rgba(58, 31, 92, 0.75), rgba(20, 16, 33, 0.8));
  box-shadow: 0 0 18px rgba(224, 138, 60, 0.18);
  font-weight: 600;
  font-size: 13px;
  color: #f3f5fb;
}
</style>
