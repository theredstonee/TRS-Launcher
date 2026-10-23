<script setup lang="ts">
// Erst-Hinweis zur Start-Animation (Stil wie „Flackern & Rauschen“ zu Halloween): einmal, nachdem die erste
// Animation gelaufen ist – nie als Sperre davor. Fragt, ob die kurze 4-s-Animation bei jedem Start laufen soll
// (Tagesstart- und Update-Fassung laufen immer) und bietet die ruhige Fassung ohne Flackern an.
import { shouldShowIntroHint } from '~/utils/intro/select'

const settings = useSettingsStore()
const events = useEventsStore()
const calm = ref(false)
const saving = ref(false)

// Der Halloween-Hinweis sitzt an derselben Stelle – erst er, dann dieser.
const halloweenHintOpen = computed(() => events.lookHalloween && settings.current?.ui.halloweenFlickerHintSeen !== true)
const open = computed(
  () =>
    !halloweenHintOpen.value &&
    shouldShowIntroHint({
      loaded: !!settings.current,
      seen: settings.current?.ui.startIntroHintSeen,
      playedThisSession: introPlayedThisSession.value,
    }),
)

watch(open, (on) => {
  if (on) calm.value = settings.current?.ui.startIntroFlicker === false
})

async function choose(keep: boolean) {
  const base = settings.current
  if (!base || saving.value) return
  saving.value = true
  try {
    await settings.save({ ...base, ui: { ...base.ui, startIntro: keep, startIntroFlicker: !calm.value, startIntroHintSeen: true } })
  } catch (e) {
    useToasts().error(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Transition name="intro-hint">
    <section v-if="open" class="intro-hint card" role="dialog" :aria-label="t('startIntro.hint.title')" data-testid="start-intro-hint">
      <div class="flex items-start gap-2.5">
        <div class="text-[22px] leading-none" aria-hidden="true">🎬</div>
        <div class="min-w-0">
          <p class="font-semibold text-base-50">{{ t('startIntro.hint.title') }}</p>
          <p class="mt-0.5 text-xs text-base-400">{{ t('startIntro.hint.text') }}</p>
          <label class="mt-2 flex cursor-pointer items-center gap-2 text-xs text-base-300">
            <input v-model="calm" type="checkbox" class="size-3.5 accent-redstone-500" data-testid="start-intro-hint-calm" />
            {{ t('startIntro.hint.calm') }}
          </label>
          <div class="mt-2.5 flex flex-wrap gap-2">
            <button type="button" class="btn btn-primary h-8 px-3 text-sm" :disabled="saving" data-testid="start-intro-hint-keep" @click="choose(true)">{{ t('startIntro.hint.keep') }}</button>
            <button type="button" class="btn btn-ghost h-8 px-3 text-sm" :disabled="saving" data-testid="start-intro-hint-off" @click="choose(false)">{{ t('startIntro.hint.off') }}</button>
          </div>
        </div>
      </div>
    </section>
  </Transition>
</template>

<style scoped>
.intro-hint {
  position: fixed;
  left: 88px;
  top: 54px;
  z-index: 45;
  width: min(380px, calc(100vw - 32px));
  padding: 14px 16px;
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.6);
}
:root.is-mobile .intro-hint {
  left: 16px;
  top: calc(var(--safe-top) + 64px);
}
.intro-hint-enter-active,
.intro-hint-leave-active {
  transition:
    opacity 0.4s ease,
    transform 0.4s ease;
}
.intro-hint-enter-from,
.intro-hint-leave-to {
  opacity: 0;
  transform: translateY(10px);
}
</style>
