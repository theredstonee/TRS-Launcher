<script lang="ts" setup>
// Vorschaubild (Open Graph) einer Doku-Seite im TRS-Stil – wird beim Generieren als PNG gerendert (1200×600).
const { title, description, headline } = defineProps<{ title?: string, description?: string, headline?: string }>()

// Am Wortende kürzen statt mitten im Wort.
const short = computed(() => {
  const d = (description ?? '').trim()
  if (d.length <= 200) return d
  const cut = d.slice(0, 200)
  return `${cut.slice(0, cut.lastIndexOf(' ') > 100 ? cut.lastIndexOf(' ') : 200)} …`
})
</script>

<template>
  <div class="w-full h-full flex flex-col justify-between bg-[#111116] px-[80px] py-[64px]">
    <div class="absolute top-0 right-0 w-[760px] h-[600px] bg-[radial-gradient(circle_at_top_right,rgba(224,40,30,0.28)_0%,rgba(224,40,30,0.08)_40%,transparent_70%)]" />
    <div class="absolute bottom-[96px] left-0 w-full h-[4px] bg-[#b31a12]" />

    <div class="flex items-center">
      <img src="/icon.png" width="64" height="64" class="w-[64px] h-[64px] mr-[20px]">
      <span class="text-[34px] font-bold text-white">TRS Launcher</span>
      <span class="ml-[16px] rounded-[8px] bg-[#3a0d09] px-[14px] py-[4px] text-[24px] font-bold text-[#ff8f85]">Docs</span>
    </div>

    <div class="flex-1 flex flex-col justify-center">
      <p v-if="headline" class="uppercase text-[24px] font-bold m-0 mb-5 tracking-[0.06em] text-[#ffb84d]">
        {{ headline }}
      </p>
      <h1 v-if="title" class="m-0 mb-6 text-[56px] font-bold text-white leading-[1.1] w-full max-w-[960px]">
        {{ title?.slice(0, 60) }}
      </h1>
      <p v-if="description" class="m-0 text-[28px] text-[#c8c8d8] leading-[1.4] w-full max-w-[960px]">
        {{ short }}
      </p>
    </div>

    <div class="flex text-[20px] text-[#8b8ba2]">
      trs-launcher.theredstonee.de/docs
    </div>
  </div>
</template>
