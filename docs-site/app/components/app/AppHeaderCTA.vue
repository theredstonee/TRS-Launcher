<script setup lang="ts">
// Rechts im Kopf: Website, Download, GitHub, Discord (Sprache und Farbschema folgen danach von Docus).
import { DISCORD_URL, REPO_URL } from '~/composables/useTrsLinks'

const { text, website, download } = useTrsLinks()
</script>

<template>
  <div class="flex items-center gap-1">
    <UButton
      :to="website"
      :label="text.website"
      icon="i-lucide-house"
      color="neutral"
      variant="ghost"
      class="hidden lg:inline-flex"
    />
    <UButton
      :to="download"
      :label="text.download"
      icon="i-lucide-download"
      color="primary"
      variant="solid"
      class="hidden sm:inline-flex"
    />
    <UButton
      :to="REPO_URL"
      target="_blank"
      rel="noopener"
      icon="i-simple-icons-github"
      color="neutral"
      variant="ghost"
      aria-label="GitHub"
    />
    <UButton
      :to="DISCORD_URL"
      target="_blank"
      rel="noopener"
      icon="i-simple-icons-discord"
      color="neutral"
      variant="ghost"
      aria-label="Discord"
      class="hidden sm:inline-flex"
    />
  </div>
</template>
