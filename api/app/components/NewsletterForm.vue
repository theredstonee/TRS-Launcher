<script setup lang="ts">
// Newsletter-Anmeldung (§40.2): Adresse + Sprache der Seite → POST /v1/newsletter/subscribe. Die Antwort ist immer
// gleich (Double-Opt-in-Mail, wenn nötig); wir zeigen danach „schau in dein Postfach“. Honigtopf-Feld gegen Bots.
const props = withDefaults(defineProps<{ variant?: 'footer' | 'page' }>(), { variant: 'footer' })
const { n, lang } = useNewsletterText()
const lp = useLocalePath()
const uid = useId()

const email = ref('')
const website = ref('')
const state = ref<'idle' | 'busy' | 'sent'>('idle')
const error = ref('')

const valid = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email.value.trim()) && email.value.length <= 254)

async function submit() {
  if (state.value === 'busy') return
  error.value = ''
  if (!valid.value) {
    error.value = n.value.form.errors.invalid_email!
    return
  }
  state.value = 'busy'
  try {
    await apiFetch('/v1/newsletter/subscribe', { method: 'POST', body: { email: email.value.trim(), lang: lang.value, ...(website.value ? { website: website.value } : {}) } })
    state.value = 'sent'
  } catch (e) {
    state.value = 'idle'
    const code = apiCode(e)
    error.value = n.value.form.errors[code] ?? n.value.form.errors.generic!
  }
}
</script>

<template>
  <div class="nl-form" :class="`nl-${props.variant}`">
    <p v-if="props.variant === 'footer'" class="text-xs font-semibold tracking-[0.18em] text-base-400 uppercase">{{ n.form.title }}</p>
    <p v-if="props.variant === 'footer'" class="mt-2 max-w-xs text-sm text-base-400">{{ n.form.lead }}</p>
    <p v-if="state === 'sent'" role="status" class="nl-sent mt-3">
      <SiteIcon name="check" class="size-4 shrink-0" />{{ n.form.sent }}
    </p>
    <form v-else class="mt-3 flex flex-wrap gap-2" novalidate @submit.prevent="submit">
      <label :for="`${uid}-email`" class="sr-only">{{ n.form.label }}</label>
      <input
        :id="`${uid}-email`"
        v-model="email"
        type="email"
        inputmode="email"
        autocomplete="email"
        maxlength="254"
        required
        class="field min-w-0 flex-1"
        :placeholder="n.form.placeholder"
        :aria-invalid="!!error"
        :aria-describedby="error ? `${uid}-err` : `${uid}-hint`"
      />
      <!-- Honigtopf: für Menschen unsichtbar -->
      <input v-model="website" type="text" name="website" tabindex="-1" autocomplete="off" class="nl-hp" aria-hidden="true" />
      <button type="submit" class="btn btn-primary" :disabled="state === 'busy'">
        <SiteIcon name="mail" class="size-4" />{{ state === 'busy' ? n.form.sending : n.form.submit }}
      </button>
    </form>
    <p v-if="error" :id="`${uid}-err`" role="alert" class="mt-2 text-sm text-redstone-300">{{ error }}</p>
    <p :id="`${uid}-hint`" class="mt-2 text-xs text-base-600">
      {{ n.form.hint }} <NuxtLink :to="lp('/privacy')" class="underline hover:text-base-300">{{ n.form.privacy }}</NuxtLink>
    </p>
  </div>
</template>

<style scoped>
.nl-hp {
  position: absolute;
  left: -10000px;
  width: 1px;
  height: 1px;
  opacity: 0;
}
.nl-sent {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  font-size: 0.875rem;
  color: var(--color-base-200);
}
.nl-sent :deep(svg) {
  margin-top: 0.15rem;
  color: var(--color-redstone-400);
}
.nl-page .field {
  height: 3rem;
  font-size: 1rem;
}
.nl-page .btn {
  height: 3rem;
}
</style>
