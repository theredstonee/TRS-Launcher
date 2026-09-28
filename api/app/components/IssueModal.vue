<script setup lang="ts">
// Issue als großes Fenster über Board/Liste (§28): alles wie auf der Einzelseite (voten, folgen, kommentieren,
// Team-Felder), dazu „Ganze Seite öffnen“. Schließen mit Esc, Klick daneben oder der Zurück-Taste (die Adresse
// trägt ?issue=<nr>, siehe IssueExplorer).
import type { IssueDetail } from '#shared/issues'

const props = defineProps<{ nr: number }>()
const emit = defineEmits<{ close: [], changed: [issue: IssueDetail] }>()
const { it } = useIssueText()
const lp = useLocalePath()
const box = shallowRef<HTMLElement | null>(null)
let before: Element | null = null

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    emit('close')
  }
  // Tab im Fenster halten.
  if (e.key === 'Tab' && box.value) {
    const f = [...box.value.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])')].filter((x) => x.offsetParent !== null)
    if (!f.length) return
    const first = f[0]!
    const last = f[f.length - 1]!
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault()
      first.focus()
    }
  }
}
onMounted(() => {
  before = document.activeElement
  document.addEventListener('keydown', onKey)
  document.documentElement.style.overflow = 'hidden'
  void nextTick(() => box.value?.focus())
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKey)
  document.documentElement.style.overflow = ''
  if (before instanceof HTMLElement) before.focus()
})
</script>

<template>
  <Teleport to="body">
    <div class="backdrop" @click.self="emit('close')">
      <div ref="box" class="dialog" role="dialog" aria-modal="true" :aria-labelledby="`issue-title-${props.nr}`" tabindex="-1" data-testid="issue-modal">
        <div class="bar">
          <span class="text-sm text-base-400 tabular-nums">#{{ props.nr }}</span>
          <NuxtLink :to="lp(`/issues/${props.nr}`)" class="btn btn-ghost ml-auto px-2.5 py-1.5 text-xs" data-testid="modal-full-page">
            <SiteIcon name="external" class="size-3.5" />{{ it.modal.fullPage }}
          </NuxtLink>
          <button type="button" class="btn-icon" :aria-label="it.modal.close" :title="`${it.modal.close} (Esc)`" @click="emit('close')">
            <SiteIcon name="close" class="size-4" />
          </button>
        </div>
        <div class="body">
          <IssueDetailView :nr="props.nr" modal @changed="emit('changed', $event)" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: grid;
  place-items: start center;
  padding: 2.5rem 1rem;
  overflow-y: auto;
  background: rgb(5 5 8 / 0.72);
  backdrop-filter: blur(3px);
  animation: fade 0.15s ease-out;
}
.dialog {
  width: min(72rem, 100%);
  border-radius: 1rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-950);
  box-shadow: 0 30px 80px -20px rgb(0 0 0 / 0.9), 0 0 0 1px rgb(255 255 255 / 0.02) inset;
  outline: none;
  animation: pop 0.18s ease-out;
}
.bar {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.6rem 0.75rem 0.6rem 1.25rem;
  border-bottom: 1px solid var(--color-base-800);
  border-radius: 1rem 1rem 0 0;
  background: color-mix(in srgb, var(--color-base-950) 92%, transparent);
  backdrop-filter: blur(6px);
}
.body {
  padding: 1.25rem 1.25rem 1.5rem;
}
@keyframes fade {
  from { opacity: 0; }
}
@keyframes pop {
  from { opacity: 0; transform: translateY(8px) scale(0.99); }
}
@media (max-width: 639px) {
  .backdrop {
    padding: 0;
  }
  .dialog {
    min-height: 100%;
    border-radius: 0;
    border: 0;
  }
  .bar {
    top: 0;
    border-radius: 0;
  }
  .body {
    padding: 1rem;
  }
}
@media (prefers-reduced-motion: reduce) {
  .backdrop,
  .dialog {
    animation: none;
  }
}
</style>
