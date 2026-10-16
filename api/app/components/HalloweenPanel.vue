<script setup lang="ts">
// Halloween-Karte (Startseite): Kürbis-Suche n/13 mit Mondlauf und nächstem Rätsel, „Süßes oder Saures“ mit Eimer-
// Animation, Abzeichen und – nach allen 13 Kürbissen – die einmalige Wahl der Jäger-Flügel. Ohne Anmeldung: Einladung
// zum Anmelden. Alles lädt erst im Browser.
import { PUMPKIN_TOTAL } from '#shared/halloween'
import type { HalloweenReward } from '~/composables/useHalloween'

const hw = useHalloween()
const t = hw.t
const lang = hw.lang

const shaking = ref(false)
const sour = ref(false)
const busy = ref(false)
const result = ref<{ result: 'sweet' | 'sour', reward?: HalloweenReward } | null>(null)
const pickWing = ref<string | null>(null)
const choosing = ref(false)

onMounted(() => {
  void hw.refresh()
})

const me = computed(() => hw.me.value)
const count = computed(() => me.value?.pumpkins.found.length ?? 0)
const hint = computed(() => me.value?.pumpkins.nextHint?.[lang.value] ?? me.value?.pumpkins.nextHint?.en ?? null)
const lastResult = computed(() => result.value ?? (me.value?.trick.usedToday ? { result: me.value.trick.result!, reward: me.value.trick.reward } : null))
const nextTime = computed(() => {
  const n = me.value?.trick.next
  if (!n) return ''
  return new Date(n).toLocaleTimeString(lang.value, { hour: '2-digit', minute: '2-digit' })
})
const wings = computed(() => me.value?.wings ?? null)
const huntDone = computed(() => count.value >= PUMPKIN_TOTAL)

async function knock() {
  if (busy.value || me.value?.trick.usedToday) return
  busy.value = true
  sour.value = false
  result.value = null
  shaking.value = true
  const [r] = await Promise.all([hw.knock(), new Promise((ok) => setTimeout(ok, 900))])
  shaking.value = false
  if (r) {
    result.value = r
    sour.value = r.result === 'sour'
  }
  busy.value = false
}

async function confirmWing() {
  if (!pickWing.value || choosing.value) return
  choosing.value = true
  await hw.chooseWings(pickWing.value)
  choosing.value = false
  pickWing.value = null
}

function rewardName(r: HalloweenReward): string {
  return r.name[lang.value] ?? r.name.en
}
</script>

<template>
  <section class="hw-panel card" data-hw-frame :aria-label="t.panelTitle">
    <header class="flex items-center gap-2.5">
      <HalloweenSprite name="moonbat" class="h-5 w-[22px]" />
      <h2 class="hw-title">{{ t.panelTitle }}</h2>
    </header>
    <p class="hw-sub">{{ t.until }}</p>

    <ClientOnly>
      <div v-if="!hw.meLoaded.value" class="mt-4">
        <MoonBar :progress="null" />
      </div>

      <div v-else-if="!me" class="mt-3">
        <p class="font-semibold text-[#f3f5fb]">{{ t.loginTitle }}</p>
        <p class="mt-1 text-sm text-base-400">{{ t.loginText }}</p>
        <NuxtLink :to="hw.loginUrl()" class="btn hw-btn mt-3">{{ t.login }}</NuxtLink>
        <p class="mt-3 text-xs text-base-400">{{ t.rewardLine }}</p>
      </div>

      <div v-else class="mt-3 grid gap-4">
        <div>
          <div class="flex items-baseline justify-between gap-2 text-sm">
            <span class="font-semibold text-[#f3f5fb]">{{ t.hunt }}</span>
            <span class="text-base-400 tabular-nums">{{ t.huntCount.replace('{n}', String(count)) }}</span>
          </div>
          <MoonBar class="mt-1" :progress="count / PUMPKIN_TOTAL" />
          <ul class="mt-1 flex flex-wrap gap-0.5" aria-hidden="true">
            <li v-for="i in PUMPKIN_TOTAL" :key="i">
              <HalloweenSprite name="pumpkin" class="size-4" :class="{ dim: i > count }" />
            </li>
          </ul>
          <p v-if="hint" class="mt-2 text-xs text-base-400">{{ t.hintLabel }}</p>
          <p v-if="hint" class="hw-riddle">„{{ hint }}“</p>
          <p v-else class="mt-2 text-sm text-[#ffd27a]">{{ t.huntDone }}</p>
        </div>

        <div v-if="huntDone && wings" class="hw-wings">
          <p class="font-semibold text-[#f3f5fb]">{{ t.wingsTitle }}</p>
          <template v-if="wings.choice">
            <p class="mt-1 text-sm text-base-400">{{ t.wingsChosen.replace('{name}', t.wingNames[wings.choice] ?? wings.choice) }}</p>
          </template>
          <template v-else-if="wings.canChoose">
            <p class="mt-1 text-xs text-base-400">{{ t.wingsLead }}</p>
            <ul class="mt-2 grid grid-cols-4 gap-1.5">
              <li v-for="id in wings.options" :key="id">
                <button type="button" class="hw-wing" :class="{ on: pickWing === id }" :aria-pressed="pickWing === id" @click="pickWing = id">
                  <img :src="`/v1/cosmetics/${id}/card-night.png`" alt="" loading="lazy" class="aspect-square w-full object-contain" />
                  <span>{{ t.wingNames[id] ?? id }}</span>
                </button>
              </li>
            </ul>
            <div v-if="pickWing" class="mt-2 flex flex-wrap gap-2">
              <button type="button" class="btn hw-btn" :disabled="choosing" @click="confirmWing">{{ t.wingsConfirm }}</button>
              <button type="button" class="btn btn-ghost" @click="pickWing = null">{{ t.wingsCancel }}</button>
            </div>
          </template>
          <p v-else class="mt-1 text-sm text-base-400">{{ t.wingsPending }}</p>
        </div>

        <div>
          <p class="font-semibold text-[#f3f5fb]">{{ t.trickTitle }}</p>
          <div class="mt-2 flex items-center gap-4">
            <div class="hw-bucket" :class="{ shake: shaking, sour }">
              <HalloweenSprite name="pumpkin" class="size-16" />
              <span class="bubble" :class="{ show: sour && !shaking }">{{ t.bubble }}</span>
            </div>
            <div class="min-w-0 flex-1">
              <p v-if="shaking" class="text-sm text-base-400">{{ t.knocking }}</p>
              <div v-else-if="lastResult" class="hw-prize">
                <template v-if="lastResult.result === 'sweet' && lastResult.reward">
                  <CapeThumb v-if="lastResult.reward.kind === 'cape'" :texture="`/v1/capes/${lastResult.reward.id}.png`" :scale="8" :frames="24" :frame-time-ms="250" :width="30" />
                  <img v-else-if="lastResult.reward.kind === 'sticker'" :src="`/v1/stickers/${lastResult.reward.id}.png`" alt="" class="size-10 [image-rendering:pixelated]" />
                  <HalloweenSprite v-else name="ghost" class="size-10" />
                  <div class="min-w-0">
                    <p class="kind">{{ t.sweet }} · {{ t.kinds[lastResult.reward.kind] }}</p>
                    <p class="nm">{{ rewardName(lastResult.reward) }}</p>
                    <p class="text-xs text-base-400">{{ t.sweetText }}</p>
                  </div>
                </template>
                <template v-else>
                  <HalloweenSprite name="ghost" class="size-10" />
                  <div class="min-w-0">
                    <p class="kind">{{ t.sour }}</p>
                    <p class="text-xs text-base-400">{{ t.sourText }}</p>
                  </div>
                </template>
              </div>
              <p v-else class="text-xs text-base-400">{{ t.trickLead }}</p>
              <button v-if="!me.trick.usedToday && !result" type="button" class="btn hw-btn mt-2" :disabled="busy || !me.active" @click="knock">{{ t.knock }}</button>
              <p v-else-if="me.active" class="mt-2 text-xs text-base-400">{{ t.nextAt.replace('{time}', nextTime) }}</p>
              <p v-if="!me.active" class="mt-2 text-xs text-base-400">{{ t.inactive }}</p>
            </div>
          </div>
          <p class="mt-2 text-xs text-base-400">{{ t.rewardLine }}</p>
        </div>

        <ul v-if="me.badges.length" class="flex flex-wrap gap-2">
          <li v-for="b in me.badges" :key="b" class="hw-badge" :class="{ pk: b === 'pumpkinhunter2026' }">
            <img :src="`/v1/badges/${b}.png`" alt="" class="h-4 [image-rendering:pixelated]" />{{ t.badges[b] ?? b }}
          </li>
        </ul>
      </div>
    </ClientOnly>
  </section>
</template>

<style scoped>
.hw-panel {
  position: relative;
  width: min(100%, 22rem);
  padding: 1rem;
  background: rgb(20 16 33 / 0.86);
  border-color: #2a2440;
  backdrop-filter: blur(6px);
}
.hw-title {
  font-family: "IM Fell English SC", Georgia, serif;
  font-size: 1.25rem;
  color: #f3f5fb;
}
.hw-sub {
  font-family: "Cormorant Garamond", Georgia, serif;
  font-style: italic;
  font-size: 1.05rem;
  color: #b9a2ea;
}
.hw-riddle {
  font-family: "Cormorant Garamond", Georgia, serif;
  font-style: italic;
  font-size: 1.1rem;
  line-height: 1.35;
  color: #b9a2ea;
}
.dim {
  filter: brightness(0) invert(0.2);
}
.hw-btn {
  background: linear-gradient(180deg, rgb(58 31 92 / 0.55), rgb(20 16 33 / 0.85));
  border: 1px solid #4a4068;
  color: #f3f5fb;
}
.hw-btn:not(:disabled):hover {
  border-color: #cfd5e3;
  box-shadow: 0 0 22px rgb(224 138 60 / 0.28), 0 0 18px rgb(207 213 227 / 0.18);
}
.hw-bucket {
  position: relative;
  display: grid;
  place-items: center;
  flex: none;
  width: 5rem;
  height: 5rem;
}
.hw-bucket :deep(canvas) {
  filter: drop-shadow(0 0 22px rgb(224 138 60 / 0.35));
}
.hw-bucket.shake :deep(canvas) {
  animation: shake 0.12s linear 7;
}
.hw-bucket.sour :deep(canvas) {
  animation: sour 1.4s ease forwards;
}
@keyframes shake {
  25% { transform: rotate(-6deg); }
  75% { transform: rotate(6deg); }
}
@keyframes sour {
  20% { transform: scale(1.1, 0.8); }
  45% { transform: scale(0.9, 1.12) rotate(-8deg); }
  70% { transform: rotate(6deg); }
  100% { transform: none; filter: drop-shadow(0 0 22px rgb(159 214 230 / 0.35)) hue-rotate(60deg) saturate(0.6); }
}
.bubble {
  position: absolute;
  top: -4px;
  right: -10px;
  padding: 2px 8px;
  border-radius: 10px;
  background: #f3f5fb;
  color: #1a1426;
  font-family: var(--font-display);
  font-size: 0.8rem;
  font-weight: 600;
  transform: scale(0);
  transition: transform 0.3s cubic-bezier(0.2, 0.9, 0.3, 1.4);
}
.bubble.show {
  transform: scale(1);
}
.hw-prize {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.6rem;
  border: 1px solid #3b3358;
  border-radius: 12px;
  background: rgb(7 6 12 / 0.5);
  animation: rise 0.5s ease;
}
.hw-prize .kind {
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #b9a2ea;
}
.hw-prize .nm {
  font-family: "IM Fell English SC", Georgia, serif;
  font-size: 1.1rem;
  color: #f3f5fb;
}
@keyframes rise {
  from { transform: translateY(10px); opacity: 0; }
}
.hw-wing {
  display: grid;
  gap: 0.25rem;
  width: 100%;
  padding: 0.4rem;
  border: 1px solid #3b3358;
  border-radius: 10px;
  background: rgb(7 6 12 / 0.45);
  color: #cfd5e3;
  height: 100%;
  align-content: start;
  font-size: 0.68rem;
  line-height: 1.15;
  text-align: center;
  overflow-wrap: anywhere;
  hyphens: auto;
}
.hw-wing.on {
  border-color: #cfd5e3;
  box-shadow: 0 0 18px rgb(207 213 227 / 0.25);
}
.hw-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.25rem 0.75rem 0.25rem 0.5rem;
  border: 1px solid rgb(207 213 227 / 0.4);
  border-radius: 999px;
  background: linear-gradient(90deg, rgb(58 31 92 / 0.75), rgb(20 16 33 / 0.8));
  box-shadow: 0 0 18px rgb(185 162 234 / 0.18);
  font-size: 0.8rem;
  font-weight: 600;
  color: #f3f5fb;
}
.hw-badge.pk {
  border-color: rgb(224 138 60 / 0.5);
  box-shadow: 0 0 18px rgb(224 138 60 / 0.18);
}
@media (prefers-reduced-motion: reduce) {
  .hw-bucket.shake :deep(canvas), .hw-bucket.sour :deep(canvas), .hw-prize { animation: none; }
}
</style>
