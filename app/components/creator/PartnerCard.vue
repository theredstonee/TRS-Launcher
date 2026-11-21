<script setup lang="ts">
import { currentLocale } from '~/utils/i18n'
import { partnerDashboardUrl, partnerSwitchAt, partnerText } from '~/utils/partner'

// Creator-Seite als Fan: unterstützter Partner (§44.11) neben dem Creator – Logo,
// seit wann, Wechselsperre, „Nicht mehr unterstützen“, eigene Partner-Kosmetik und
// der Link zum Partner-Dashboard auf der Website für Mitglieder.
const store = usePartnerStore()
const toasts = useToasts()

const me = computed(() => store.me)
const partner = computed(() => me.value?.support.partner ?? null)
const switchAt = computed(() => partnerSwitchAt(me.value?.support.switchAvailableAt))
const description = computed(() => (partner.value ? partnerText(partner.value, currentLocale.value) : ''))
const owned = computed(() => me.value?.owned ?? [])
const member = computed(() => me.value?.member ?? [])

const confirmEnd = ref(false)
const ending = ref(false)
async function endSupport() {
  const name = partner.value?.name ?? ''
  ending.value = true
  try {
    await store.endSupport()
    confirmEnd.value = false
    toasts.ok(t('partner.ended', { name }))
  } catch (e) {
    toasts.error(e)
  } finally {
    ending.value = false
  }
}

function openDashboard(slug: string) {
  void backend.openExternalUrl(partnerDashboardUrl(member.value, slug)).catch((e) => toasts.error(e))
}
</script>

<template>
  <section class="card p-4" :aria-label="t('partner.title')" data-testid="partner-card">
    <h2 class="section-title">{{ t('partner.title') }}</h2>

    <p v-if="!me && store.loading" class="mt-3 text-sm text-base-400">{{ t('common.status.loading') }}</p>
    <p v-else-if="store.error && !me" class="mt-3 text-sm text-base-400">{{ store.error }}</p>

    <template v-else-if="partner">
      <div class="mt-3 flex items-start gap-3">
        <span class="grid size-11 shrink-0 place-items-center overflow-hidden rounded-lg bg-base-850">
          <img v-if="partner.logo" :src="partner.logo" :alt="partner.name" class="size-full object-cover" draggable="false" />
          <SocialIcon v-else name="cube" class="size-6 text-base-300" />
        </span>
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-2">
            <span class="text-base font-semibold text-base-50" data-testid="partner-name">{{ partner.name }}</span>
            <span class="badge bg-[#10324a]/70 px-1.5 py-0 text-[10px] text-[#8fd3ff]">{{ t(`partner.categories.${partner.category}`) }}</span>
          </div>
          <p class="text-xs text-base-400">
            <template v-if="me?.support.startedAt">{{ t('partner.since', { date: formatShortDate(me.support.startedAt) }) }} · </template>
            <span data-testid="partner-switch">{{ switchAt ? t('partner.switchFrom', { date: formatShortDate(switchAt) }) : t('partner.switchNow') }}</span>
          </p>
          <p v-if="description" class="mt-1 line-clamp-2 text-xs text-base-300">{{ description }}</p>
        </div>
      </div>
      <div class="mt-3 flex flex-wrap items-center gap-2">
        <button class="btn btn-ghost px-3 py-1.5 text-xs" data-testid="partner-end-support" @click="confirmEnd = true">{{ t('partner.end') }}</button>
        <span class="text-[11px] text-base-400">{{ t('partner.endHint') }}</span>
      </div>
    </template>
    <p v-else class="mt-3 text-sm text-base-400">{{ t('partner.nobody') }}</p>

    <div v-if="owned.length" class="mt-3 border-t border-base-800 pt-3">
      <p class="text-xs font-medium text-base-200">{{ t('partner.ownedTitle') }}</p>
      <ul class="mt-2 flex flex-wrap gap-2" data-testid="partner-owned">
        <li v-for="o in owned" :key="`${o.kind}:${o.id}`" class="chip gap-1.5">
          <SocialIcon :name="o.kind === 'cape' ? 'skins' : 'crown'" class="size-3.5" />
          <span class="font-medium text-base-50">{{ o.name }}</span>
          <span v-if="o.partner && o.partner.slug !== partner?.slug" class="text-base-400">{{ t('partner.itemBy', { name: o.partner.name }) }}</span>
        </li>
      </ul>
      <p class="mt-2 text-xs text-base-400">
        {{ t('partner.ownedHint') }}
        <NuxtLink to="/skins" class="text-base-200 underline hover:text-base-50">{{ t('creator.fan.openSkins') }}</NuxtLink>
      </p>
    </div>

    <div v-if="member.length" class="mt-3 flex flex-wrap gap-2 border-t border-base-800 pt-3">
      <button v-for="m in member" :key="m.slug" class="btn btn-ghost px-3 py-1.5 text-xs" data-testid="partner-dashboard" @click="openDashboard(m.slug)">
        {{ member.length > 1 ? t('partner.dashboardOf', { name: m.name }) : t('partner.dashboard') }}
        <SocialIcon name="external" class="size-3.5" />
      </button>
    </div>

    <BaseDialog v-if="confirmEnd && partner" :title="t('partner.endTitle', { name: partner.name })" @close="confirmEnd = false">
      <p class="text-sm text-base-200">{{ t('partner.endText', { name: partner.name }) }}</p>
      <p class="mt-2 text-xs text-base-400">
        {{ switchAt ? t('partner.endSwitchFrom', { name: partner.name, date: formatShortDate(switchAt) }) : t('partner.endSwitchNow', { name: partner.name }) }}
      </p>
      <template #actions>
        <button class="btn btn-ghost" @click="confirmEnd = false">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-danger" :disabled="ending" data-testid="partner-end-confirm" @click="endSupport">{{ t('partner.end') }}</button>
      </template>
    </BaseDialog>
  </section>
</template>
