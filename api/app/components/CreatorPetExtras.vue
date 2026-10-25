<script setup lang="ts">
// Begleiter-Gefolge (§35.13) im Creator-Dashboard (Reiter „Haustier“): zeigt die vom Team zugeordneten weiteren
// Begleiter und den Schalter „Gefolge zeigen“ (`PUT /v1/creator/pet/extras`, nur der Creator selbst).
import { sameOriginPath, type PetExtraInfo } from '~/utils/creators'

/** `endpoint`: `/v1/creator/pet/extras`, im Team-Modus mit `?as=` (das Dashboard baut den Pfad). */
const props = defineProps<{ extras: PetExtraInfo[], show: boolean, helper: boolean, endpoint?: string }>()
const emit = defineEmits<{ changed: [show: boolean] }>()
const { lang, fill } = useLang()
const { api } = useAccount()

const TEXTS = {
  en: {
    title: 'Your entourage',
    lead: 'The team gave you these extra companions. They walk with you in addition to your pet – only with you, not with your fans.',
    toggle: 'Show entourage',
    hint: 'When off, only your pet walks with you.',
    helper: 'Only the creator can change this.',
    scale: 'size ×{n}',
    unavailable: 'currently not available',
    failed: 'Could not save: {error}',
  },
  de: {
    title: 'Dein Gefolge',
    lead: 'Diese zusätzlichen Begleiter hat dir das Team gegeben. Sie laufen zusätzlich zu deinem Haustier mit – nur bei dir, nicht bei deinen Fans.',
    toggle: 'Gefolge zeigen',
    hint: 'Aus: Nur dein Haustier läuft mit.',
    helper: 'Nur der Creator kann das ändern.',
    scale: 'Größe ×{n}',
    unavailable: 'gerade nicht verfügbar',
    failed: 'Speichern fehlgeschlagen: {error}',
  },
  es: {
    title: 'Tu séquito',
    lead: 'El equipo te ha dado estos acompañantes extra. Caminan contigo además de tu mascota – solo contigo, no con tus fans.',
    toggle: 'Mostrar séquito',
    hint: 'Si está desactivado, solo te acompaña tu mascota.',
    helper: 'Solo el creador puede cambiar esto.',
    scale: 'tamaño ×{n}',
    unavailable: 'no disponible ahora',
    failed: 'No se pudo guardar: {error}',
  },
} as const
const x = computed(() => TEXTS[lang.value as keyof typeof TEXTS] ?? TEXTS.en)
const busy = ref(false)
const error = ref('')

async function toggle(show: boolean) {
  busy.value = true
  error.value = ''
  try {
    const r = await api<{ showExtras: boolean }>(props.endpoint ?? '/v1/creator/pet/extras', { method: 'PUT', body: { show } })
    emit('changed', r.showExtras)
  } catch (e) {
    error.value = fill(x.value.failed, { error: e instanceof Error ? e.message : String(e) })
    emit('changed', !show)
  } finally {
    busy.value = false
  }
}
const scaleText = (n: number) => fill(x.value.scale, { n: String(n).replace('.', lang.value === 'en' ? '.' : ',') })
</script>

<template>
  <div class="extras mt-6">
    <h3 class="text-sm font-semibold text-base-200">{{ x.title }}</h3>
    <p class="mt-1 text-xs text-base-400">{{ x.lead }}</p>
    <ul class="mt-3 flex flex-wrap gap-3">
      <li v-for="p in props.extras" :key="p.cosmeticId" class="extra" :data-off="!props.show || !p.available">
        <span class="extra-pic grid place-items-center text-base-500">
          <img v-if="p.card" :src="sameOriginPath(p.card)" alt="" width="80" height="80" loading="lazy" />
          <SiteIcon v-else name="star" class="size-6" />
        </span>
        <span class="mt-1 block max-w-[80px] text-center text-xs leading-tight text-base-200">{{ p.name }}</span>
        <span v-if="p.petScale !== 1" class="block text-center text-[11px] text-base-400">{{ scaleText(p.petScale) }}</span>
        <span v-if="!p.available" class="block text-center text-[11px] text-lamp-300">{{ x.unavailable }}</span>
      </li>
    </ul>
    <label class="mt-4 flex items-center gap-2 text-sm text-base-200">
      <input type="checkbox" class="accent-redstone-500" :checked="props.show" :disabled="busy || props.helper" @change="toggle(($event.target as HTMLInputElement).checked)" />
      {{ x.toggle }}
    </label>
    <p class="mt-1 text-xs text-base-500">{{ props.helper ? x.helper : x.hint }}</p>
    <p v-if="error" class="mt-2 text-xs text-redstone-300" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.extra {
  border-radius: 0.6rem;
  background: var(--color-base-950);
  padding: 0.4rem;
  transition: opacity 0.2s;
}
.extra[data-off='true'] {
  opacity: 0.45;
}
.extra-pic {
  width: 80px;
  height: 80px;
  overflow: hidden;
  border-radius: 0.4rem;
  background: var(--color-base-900);
}
/* Die Karte zeigt Spieler + Begleiter: auf den Begleiter (unten Mitte) zoomen. */
.extra-pic img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scale(1.8);
  transform-origin: 56% 82%;
}
</style>
