<script setup lang="ts">
import { actionableParties, conflictSentence, partyKey, partyName, type ConflictParty } from '~/utils/modConflicts'

// Mod-Konflikt-Helfer: erklärt, welche Mods in den installierten Versionen nicht
// zusammenpassen, und bietet je Mod eine Lösung an. Nach jeder Aktion wird neu
// geprüft; ist alles gelöst, schließt sich der Dialog (Starten im Hinweis).
const store = useModConflictsStore()
const instances = useInstancesStore()
const games = useGamesStore()

const instanceName = computed(() => {
  const id = store.instanceId
  return instances.items.find((i) => i.id === id)?.name ?? id ?? ''
})
const conflicts = computed(() => store.report?.conflicts ?? [])
const running = computed(() => (store.instanceId ? games.state(store.instanceId).phase !== 'idle' : false))
const anyBusy = computed(() => store.isBusy('all') || store.loading)

function busy(p: ConflictParty) {
  return store.isBusy('fit', p) || store.isBusy('disable', p) || store.isBusy('remove', p) || store.isBusy('all')
}
</script>

<template>
  <BaseDialog v-if="store.isOpen" :title="t('modConflicts.title')" wide @close="store.close()">
    <p class="-mt-1 mb-3 text-xs text-base-400">
      <span class="font-medium text-base-200">{{ instanceName }}</span>
    </p>

    <div class="max-h-[62vh] space-y-3 overflow-y-auto pr-1" data-testid="mod-conflicts">
      <p v-if="store.report && !store.resolved" class="text-sm leading-relaxed text-base-200">
        {{ store.origin === 'launch' ? t('modConflicts.introLaunch') : t('modConflicts.intro') }}
      </p>
      <p v-if="!store.report && store.loading" class="text-sm text-base-400">{{ t('modConflicts.checking') }}</p>
      <section v-if="store.resolved" class="rounded-xl border border-ok/40 bg-base-900 p-4 text-sm text-base-50">
        {{ t('modConflicts.none') }}
      </section>

      <section
        v-for="(c, i) in conflicts"
        :key="`${c.text}-${i}`"
        class="rounded-xl border border-redstone-600/40 bg-redstone-900/30 p-4"
        data-conflict
      >
        <p class="text-sm font-medium leading-relaxed text-base-50">{{ conflictSentence(c) }}</p>
        <ul class="mt-3 space-y-2">
          <li
            v-for="p in actionableParties(c)"
            :key="partyKey(p)"
            class="rounded-lg bg-base-900/80 px-2.5 py-2 ring-1 ring-base-700"
          >
            <div class="flex flex-wrap items-center gap-2">
              <ModIcon :src="p.iconUrl" :name="p.name" :size="24" />
              <span class="text-sm text-base-50">{{ partyName(p) }}</span>
              <span v-if="p.version" class="font-mono text-[11px] text-base-400">{{ p.version }}</span>
              <span class="flex-1" />
              <button
                type="button"
                class="btn btn-primary px-2.5 py-1 text-xs"
                :disabled="!p.adjustable || busy(p) || running"
                :title="p.adjustable ? t('modConflicts.actions.findHint') : t('modConflicts.actions.findUnsupported')"
                data-testid="conflict-find"
                @click="store.findVersion(p)"
              >
                {{ store.isBusy('fit', p) ? t('modConflicts.working') : t('modConflicts.actions.find') }}
              </button>
              <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="busy(p) || running" data-testid="conflict-disable" @click="store.disable(p)">
                {{ store.isBusy('disable', p) ? t('modConflicts.working') : t('modConflicts.actions.disable') }}
              </button>
              <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="busy(p) || running" @click="store.confirmRemove = p">
                {{ store.isBusy('remove', p) ? t('modConflicts.working') : t('modConflicts.actions.remove') }}
              </button>
              <button v-if="p.projectId" type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="store.openPage(p)">
                {{ t('modConflicts.actions.page') }}
              </button>
            </div>
            <p v-if="store.notes[partyKey(p)]" class="mt-1.5 text-xs text-lamp-300" role="status">{{ store.notes[partyKey(p)] }}</p>
          </li>
        </ul>
      </section>

      <!-- Entfernen bestätigen -->
      <section v-if="store.confirmRemove" class="rounded-xl border border-lamp-400/40 bg-lamp-900/60 p-4" role="alertdialog" :aria-label="t('modConflicts.removeTitle')">
        <h3 class="text-sm font-semibold text-lamp-300">{{ t('modConflicts.removeTitle') }}</h3>
        <p class="mt-1 text-sm text-base-50">{{ t('modConflicts.removeText', { name: store.confirmRemove.name }) }}</p>
        <div class="mt-3 flex justify-end gap-2">
          <button type="button" class="btn btn-ghost px-3 py-1.5 text-xs" @click="store.confirmRemove = null">{{ t('common.actions.cancel') }}</button>
          <button type="button" class="btn btn-primary px-3 py-1.5 text-xs" @click="store.remove(store.confirmRemove)">{{ t('modConflicts.actions.remove') }}</button>
        </div>
      </section>

      <p v-if="store.report && !store.resolved" class="flex items-start gap-1.5 text-[11px] leading-relaxed text-base-400">
        <svg viewBox="0 0 24 24" class="mt-px size-3.5 shrink-0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l9 16H3zM12 10v4m0 3v.01" /></svg>
        {{ t('modConflicts.anywayHint') }}
      </p>
    </div>

    <template #actions>
      <template v-if="store.resolved">
        <button type="button" class="btn btn-ghost" @click="store.close()">{{ t('common.actions.close') }}</button>
        <button type="button" class="btn btn-primary" :disabled="running" @click="store.launch()">{{ t('modConflicts.launch') }}</button>
      </template>
      <template v-else>
        <button type="button" class="btn btn-ghost" :disabled="anyBusy || running || !store.report" data-testid="conflict-anyway" @click="store.launchAnyway()">
          {{ t('modConflicts.anyway') }}
        </button>
        <button type="button" class="btn btn-ghost" :disabled="anyBusy || running || !conflicts.length" data-testid="conflict-disable-all" @click="store.disableAllAndLaunch()">
          {{ t('modConflicts.disableAll') }}
        </button>
        <button type="button" class="btn btn-primary" @click="store.close()">{{ t('common.actions.close') }}</button>
      </template>
    </template>
  </BaseDialog>
</template>
