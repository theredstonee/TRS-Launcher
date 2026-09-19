<template>
  <div class="flex h-full flex-col">
    <TitleBar />
    <div class="flex min-h-0 flex-1">
      <SideNav />
      <main class="min-w-0 flex-1 overflow-y-auto bg-base-950">
        <slot />
      </main>
    </div>
  </div>
</template>
