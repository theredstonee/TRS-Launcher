<script setup lang="ts">
import { DOMAIN_SUFFIX, type SubdomainStatus, validSubdomain } from '~/utils/craftinghosting'

// Eigene Adresse ändern (gleiche Live-Prüfung wie beim Anlegen).
const store = useCraftingHostingStore()
const current = computed(() => store.server?.domain?.slice(0, -DOMAIN_SUFFIX.length) ?? null)
const value = ref(current.value ?? '')
const status = ref<SubdomainStatus>('idle')
/** API ohne eigene Adressen (dns_unavailable). */
const unavailable = ref(false)

watch(current, (c) => (value.value = c ?? ''))

const changed = computed(() => value.value !== (current.value ?? ''))
const canSave = computed(() => changed.value && validSubdomain(value.value) && (status.value === 'available' || status.value === 'error') && !store.busy)

async function save() {
  if (canSave.value) await store.setSubdomain(value.value)
}
</script>

<template>
  <section v-if="!unavailable" class="card p-4" data-testid="hosting-address-card" aria-labelledby="ch-address-title">
    <h3 id="ch-address-title" class="text-sm font-semibold text-base-50">{{ t('craftinghosting.address.title') }}</h3>
    <p class="mt-0.5 mb-3 text-xs text-base-400">{{ current ? t('craftinghosting.address.changeHint') : t('craftinghosting.address.none') }}</p>
    <form class="flex flex-wrap items-start gap-2" @submit.prevent="save">
      <CraftinghostingSubdomainField
        v-model="value"
        input-id="ch-address"
        class="min-w-64 flex-1"
        :current="current"
        @status="(s) => (status = s)"
        @unavailable="unavailable = true"
      />
      <button type="submit" class="btn btn-primary" :disabled="!canSave" data-testid="hosting-address-save">
        {{ store.busy === 'address' ? t('craftinghosting.address.saving') : t('craftinghosting.address.change') }}
      </button>
    </form>
  </section>
</template>
