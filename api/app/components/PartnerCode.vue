<script setup lang="ts">
// Fester Partner-Code (bis 19 Zeichen), der in jede Box passt: die Schrift richtet sich nach der Breite des
// Behälters (Container-Einheiten) und der Länge des Codes; zur Not bricht er um. `max` = größte Schrift in rem.
withDefaults(defineProps<{ code: string, max?: number }>(), { max: 1.1 })
</script>

<template>
  <span class="pcode-box"><code class="pcode" :style="{ '--len': Math.max(4, code.length), '--max': `${max}rem` }">{{ code }}</code></span>
</template>

<style scoped>
.pcode-box {
  display: block;
  width: 100%;
  min-width: 0;
  container-type: inline-size;
}
.pcode {
  display: block;
  border: 1px dashed var(--color-base-600);
  border-radius: 0.5rem;
  padding: 0.25rem 0.6rem;
  font-family: var(--font-mono);
  font-weight: 700;
  letter-spacing: 0.06em;
  line-height: 1.5;
  text-align: center;
  color: var(--color-lamp-300);
  /* Breite je Zeichen ≈ 0.6em + Sperrung 0.06em, dazu Innenabstand und Rahmen. */
  font-size: clamp(0.68rem, calc((100cqi - 1.4rem) / (var(--len) * 0.7)), var(--max));
  word-break: break-all;
}
</style>
