<script lang="ts" setup>
// Vorschaubild (Open Graph) der Doku-Startseite je Sprache im TRS-Stil (1200×600).
const { title, description } = defineProps<{ title?: string, description?: string }>()

// Am Wortende kürzen statt mitten im Wort.
const short = computed(() => {
  const d = (description ?? '').trim()
  if (d.length <= 170) return d
  const cut = d.slice(0, 170)
  return `${cut.slice(0, cut.lastIndexOf(' ') > 85 ? cut.lastIndexOf(' ') : 170)} …`
})
</script>

<template>
  <div class="w-full h-full flex flex-col items-center justify-center bg-[#111116] px-[90px]">
    <div class="absolute top-0 left-0 w-full h-full bg-[radial-gradient(circle_at_50%_30%,rgba(224,40,30,0.30)_0%,rgba(224,40,30,0.08)_35%,transparent_65%)]" />
    <div class="absolute bottom-[80px] left-0 w-full h-[4px] bg-[#b31a12]" />

    <img src="/icon.png" width="120" height="120" class="w-[120px] h-[120px] mb-[28px]">
    <h1 v-if="title" class="m-0 mb-5 text-[60px] font-bold text-white text-center leading-[1.1]">
      {{ title?.slice(0, 60) }}
    </h1>
    <p v-if="description" class="m-0 text-[28px] text-[#c8c8d8] text-center leading-[1.4] max-w-[980px]">
      {{ short }}
    </p>
    <p class="m-0 mt-[28px] text-[22px] font-bold text-[#ffb84d]">
      The Redstone Launcher by TheRedstonee
    </p>
  </div>
</template>
