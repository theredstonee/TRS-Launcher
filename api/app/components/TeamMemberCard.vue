<script setup lang="ts">
// Karte eines Team-Mitglieds auf der öffentlichen Team-Seite (§26.1): 3D-Skin, Name, Rolle, Titel, Discord, Links.
const props = withDefaults(defineProps<{ member: PublicTeamMember, role: TeamRole, big?: boolean }>(), { big: false })
const { t, lang } = useTeamText()

const title = computed(() => inLang(props.member.titles, lang.value) ?? null)
const copied = ref(false)
let copiedTimer: ReturnType<typeof setTimeout> | null = null

async function copyDiscord() {
  const name = props.member.discord
  if (!name) return
  try {
    await navigator.clipboard.writeText(name)
    copied.value = true
    if (copiedTimer) clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => (copied.value = false), 1800)
  } catch {
    // Zwischenablage gesperrt: Name steht ohnehin sichtbar da.
  }
}
onBeforeUnmount(() => {
  if (copiedTimer) clearTimeout(copiedTimer)
})

/** Nur https-Links (prüft auch der Server) – alles andere wird nicht verlinkt. */
const safeLinks = computed(() => props.member.links.filter((l) => /^https:\/\//i.test(l.url)))
const host = (url: string) => {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return ''
  }
}
</script>

<template>
  <article class="member card" :class="{ big }" :style="{ '--role': role.color }">
    <div class="stage">
      <SkinView3d :uuid="member.uuid" :name="member.name" :skin="member.skin" :cape="member.cape" :height="big ? 330 : 250" :label="`${member.name} – ${t.team.turn}`" />
    </div>
    <div class="body">
      <h3 class="heading truncate text-lg text-base-50" :class="{ 'text-2xl': big }">{{ member.name }}</h3>
      <div class="mt-1.5 flex flex-wrap items-center justify-center gap-1.5">
        <RoleBadge :role="role" :small="!big" />
      </div>
      <p v-if="title" class="mt-2 text-sm font-semibold text-base-200">{{ title }}</p>
      <div v-if="member.discord || safeLinks.length" class="mt-3 flex flex-wrap items-center justify-center gap-1.5">
        <button
          v-if="member.discord"
          type="button"
          class="chip"
          :aria-label="copied ? t.team.copied : t.team.copyDiscord.replace('{name}', member.discord)"
          :title="t.team.copyDiscord.replace('{name}', member.discord)"
          @click="copyDiscord"
        >
          <SiteIcon :name="copied ? 'check' : 'discord'" class="size-3.5" />
          <span class="max-w-[10rem] truncate">{{ copied ? t.team.copied : member.discord }}</span>
        </button>
        <a v-for="l in safeLinks" :key="l.url" :href="l.url" class="chip" target="_blank" rel="noopener noreferrer nofollow" :title="host(l.url)">
          <SiteIcon name="link" class="size-3.5" />
          <span class="max-w-[9rem] truncate">{{ l.label }}</span>
        </a>
      </div>
    </div>
  </article>
</template>

<style scoped>
.member {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-top: 3px solid var(--role);
  background-image:
    radial-gradient(120% 60% at 50% 0%, color-mix(in srgb, var(--role) 16%, transparent), transparent 70%);
}
.stage {
  position: relative;
}
.stage::after {
  content: '';
  position: absolute;
  inset: auto 18% 10px;
  height: 14px;
  border-radius: 50%;
  background: radial-gradient(closest-side, rgb(0 0 0 / 0.45), transparent);
  pointer-events: none;
}
.body {
  padding: 0.25rem 1rem 1.1rem;
  text-align: center;
}
.member.big .body {
  padding-bottom: 1.4rem;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-900);
  color: var(--color-base-200);
  border-radius: 999px;
  padding: 0.2rem 0.65rem;
  font-size: 0.78rem;
  transition: border-color 0.15s, color 0.15s;
}
.chip:hover,
.chip:focus-visible {
  border-color: color-mix(in srgb, var(--role) 60%, var(--color-base-600));
  color: var(--color-base-50);
}
</style>
