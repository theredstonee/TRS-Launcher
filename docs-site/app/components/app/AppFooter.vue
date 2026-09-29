<script setup lang="ts">
// Fußzeile im Stil der Website: Name, Hinweis „kein offizielles Minecraft-Produkt“, Links zu Website, Impressum,
// Datenschutz, GitHub und Discord.
import { DISCORD_URL, IMPRINT_URL, REPO_URL } from '~/composables/useTrsLinks'

const { text, website, download, privacy } = useTrsLinks()
const { forced: forcedColorMode } = useDocusColorMode()
const icon = useBaseUrl('/icon.png')
const year = new Date().getFullYear()
</script>

<template>
  <footer class="trs-footer mt-16 border-t border-default">
    <div class="trs-dust-line" aria-hidden="true" />
    <UContainer class="grid gap-8 py-10 sm:grid-cols-[1.6fr_1fr]">
      <div>
        <div class="flex items-center gap-2.5 text-highlighted">
          <img :src="icon" alt="" width="28" height="28" class="pixelated size-7">
          <span class="trs-display text-lg">{{ text.tagline }}</span>
        </div>
        <p class="mt-3 max-w-md text-xs text-dimmed">
          {{ text.notAffiliated }}
        </p>
      </div>
      <nav class="flex flex-wrap content-start gap-x-5 gap-y-2 text-sm" aria-label="Links">
        <ULink :to="website" class="text-muted hover:text-highlighted">{{ text.website }}</ULink>
        <ULink :to="download" class="text-muted hover:text-highlighted">{{ text.download }}</ULink>
        <ULink :to="REPO_URL" target="_blank" rel="noopener" class="text-muted hover:text-highlighted">GitHub</ULink>
        <ULink :to="DISCORD_URL" target="_blank" rel="noopener" class="text-muted hover:text-highlighted">Discord</ULink>
        <ULink :to="IMPRINT_URL" rel="noopener" class="text-muted hover:text-highlighted">{{ text.imprint }}</ULink>
        <ULink :to="privacy" class="text-muted hover:text-highlighted">{{ text.privacy }}</ULink>
      </nav>
    </UContainer>
    <div class="border-t border-default">
      <UContainer class="flex flex-wrap items-center justify-between gap-2 py-3 text-xs text-dimmed">
        <span>© {{ year }} TheRedstonee · GPL-3.0</span>
        <ClientOnly v-if="!forcedColorMode">
          <UColorModeButton size="sm" />
        </ClientOnly>
      </UContainer>
    </div>
  </footer>
</template>
