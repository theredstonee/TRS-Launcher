<script setup lang="ts">
// Meldungen der Kürbis-Suche (Halloween 2026). Eigenes Bauteil, weil sie zum Event gehören, nicht zum Look:
// sie erscheinen auch, wenn jemand das Event-Design abgeschaltet hat (§32.6).
const hw = useHalloween()
const visible = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

watch(() => hw.toast.value?.at, () => {
  if (!hw.toast.value) return
  visible.value = true
  clearTimeout(timer)
  timer = setTimeout(() => {
    visible.value = false
  }, hw.toast.value.link ? 6000 : 3600)
})
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <ClientOnly>
    <Transition name="toast">
      <div v-if="visible && hw.toast.value" class="hw-toast" role="status">
        <HalloweenSprite name="pumpkin" class="size-8 shrink-0" />
        <div>
          <p class="font-semibold text-[#f3f5fb]">{{ hw.toast.value.title }}</p>
          <p class="text-sm text-base-400">{{ hw.toast.value.text }}</p>
          <NuxtLink v-if="hw.toast.value.link" :to="hw.toast.value.link.to" class="mt-1 inline-block text-sm font-semibold text-[#b9a2ea] underline">{{ hw.toast.value.link.label }}</NuxtLink>
        </div>
      </div>
    </Transition>
  </ClientOnly>
</template>

<style scoped>
.hw-toast {
  position: fixed;
  right: 16px;
  bottom: 16px;
  z-index: 50;
  display: flex;
  gap: 12px;
  align-items: center;
  max-width: min(340px, calc(100vw - 32px));
  padding: 12px 16px;
  border: 1px solid #3b3358;
  border-left: 3px solid #e08a3c;
  border-radius: 10px;
  background: rgb(20 16 33 / 0.95);
}
</style>
