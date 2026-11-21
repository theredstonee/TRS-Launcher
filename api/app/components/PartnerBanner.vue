<script setup lang="ts">
// Hinweis auf der Download-Seite nach einem Werbe-Link (/go/<slug>, §44.3): „Empfohlen von <Partner> – Code <CODE>“.
// Klicks auf Download-Knöpfe ([data-dl]) zählen als Download für Partner/Kampagne aus der Adresse.
const route = useRoute()
const { fill } = useLang()
const { p } = usePartnerText()
const slug = computed(() => (typeof route.query.partner === 'string' && /^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$/.test(route.query.partner) ? route.query.partner : ''))
const campaign = computed(() => (typeof route.query.c === 'string' && /^[a-z0-9-]{1,24}$/.test(route.query.c) ? route.query.c : ''))
const partner = ref<PartnerCard | null>(null)

function onClick(e: MouseEvent) {
  if (!partner.value || !(e.target instanceof Element) || !e.target.closest('[data-dl]')) return
  const url = `/v1/partners/${encodeURIComponent(partner.value.slug)}/download${campaign.value ? `?c=${encodeURIComponent(campaign.value)}` : ''}`
  try {
    if (!navigator.sendBeacon?.(url)) void fetch(url, { method: 'POST', keepalive: true, credentials: 'omit' }).catch(() => {})
  } catch {
    // Zählen ist nur Beiwerk.
  }
}

onMounted(async () => {
  if (!slug.value) return
  try {
    partner.value = await apiFetch<PartnerCard>(`/v1/partners/${encodeURIComponent(slug.value)}`)
    document.addEventListener('click', onClick, true)
  } catch {
    partner.value = null
  }
})
onBeforeUnmount(() => document.removeEventListener('click', onClick, true))
</script>

<template>
  <aside v-if="partner" class="partner-banner" role="note">
    <PartnerLogo :logo="partner.logo" :name="partner.name" :size="40" />
    <div class="min-w-0 flex-1">
      <p class="font-semibold text-base-50">
        {{ fill(p.banner.recommended, { name: partner.name }) }}<template v-if="partner.code"> – <code class="banner-code">{{ fill(p.banner.code, { code: partner.code }) }}</code></template>
      </p>
      <p class="text-sm text-base-400">{{ p.banner.text }}</p>
    </div>
  </aside>
</template>

<style scoped>
.partner-banner {
  display: flex;
  align-items: center;
  gap: 0.9rem;
  margin-top: 1.5rem;
  max-width: 42rem;
  border-radius: 0.8rem;
  border: 1px solid var(--color-base-800);
  border-left: 3px solid var(--color-lamp-400);
  background: var(--color-base-900);
  padding: 0.75rem 1rem;
}
.banner-code {
  font-family: var(--font-mono);
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--color-lamp-300);
}
</style>
