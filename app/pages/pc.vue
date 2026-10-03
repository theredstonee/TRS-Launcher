<script setup lang="ts">
import { invoke, isTauri } from '@tauri-apps/api/core'
import type { RemotePeer, StatusInstance } from '~/utils/remote'

// „PC“ am Handy: gekoppelte PCs (gleiches TRS-Konto) mit ihren Instanzen – Spielen/Beenden, laufende Aufgaben,
// Modpack per Code auf dem PC installieren. Koppeln per QR-Code (Kamera) oder eingetipptem Code. Was erlaubt ist,
// entscheidet der PC (Einstellungen → Fernbedienung); ausgegraute Knöpfe zeigen das.
const remote = useRemoteStore()
const trs = useTrsStore()
const toasts = useToasts()
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const loadError = ref<string | null>(null)
const pairOpen = ref(false)
const codeInput = ref('')
const pairing = ref(false)
const pairError = ref<string | null>(null)
const scanning = ref(false)
const packCodes = ref<Record<string, string>>({})
const busy = ref<Record<string, boolean>>({})
const removing = ref<string | null>(null)

const normalized = computed(() => normalizePairCode(codeInput.value))
/** QR-Scanner gibt es nur in der Handy-App (Kamera-Plugin). */
const canScan = computed(() => isTauri() && remote.role === 'phone')

async function load() {
  if (!trs.enabled) return
  loading.value = true
  loadError.value = null
  try {
    await remote.loadPcs()
    if (!remote.pcs.length) pairOpen.value = true
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

watch(() => trs.enabled, (on) => on && void load(), { immediate: true })

// Code aus einem gescannten Link (`trs-launcher://remote-pair/…`): Feld füllen, gekoppelt wird erst nach dem Tipp.
watch(
  () => route.query.code,
  (code) => {
    const c = typeof code === 'string' ? normalizePairCode(code) : null
    if (!c) return
    codeInput.value = c
    pairOpen.value = true
    void router.replace({ query: {} })
  },
  { immediate: true },
)

async function pair() {
  const code = normalized.value
  if (!code) {
    pairError.value = t('remote.pc.invalidCode')
    return
  }
  pairing.value = true
  pairError.value = null
  try {
    const pc = await remote.pair(code)
    toasts.ok(t('remote.pc.paired', { name: pc.name }))
    codeInput.value = ''
    pairOpen.value = false
  } catch (e) {
    pairError.value = errorMessage(e)
  } finally {
    pairing.value = false
  }
}

/** Kamera öffnen und den QR-Code des PCs lesen (Plugin `barcode-scanner`, nur Android/iOS). */
async function scan() {
  if (scanning.value) return
  scanning.value = true
  pairError.value = null
  try {
    let perm = await invoke<{ camera?: string }>('plugin:barcode-scanner|check_permissions').catch(() => null)
    if (perm?.camera !== 'granted') perm = await invoke<{ camera?: string }>('plugin:barcode-scanner|request_permissions').catch(() => null)
    if (perm && perm.camera !== 'granted') {
      pairError.value = t('remote.pc.cameraDenied')
      return
    }
    const r = await invoke<{ content?: unknown }>('plugin:barcode-scanner|scan', { windowed: false, formats: ['QR_CODE'] })
    const c = typeof r?.content === 'string' ? normalizePairCode(r.content) : null
    if (!c) {
      pairError.value = t('remote.pc.notAPairCode')
      return
    }
    codeInput.value = c
    await pair()
  } catch {
    pairError.value = t('remote.pc.scanFailed')
  } finally {
    scanning.value = false
  }
}

async function command(pc: RemotePeer, type: 'launch_instance' | 'stop_instance', instance: StatusInstance) {
  const key = `${pc.id}:${instance.id}`
  if (busy.value[key]) return
  busy.value[key] = true
  try {
    await remote.send(pc.id, type, { instanceId: instance.id })
    toasts.info(t(type === 'launch_instance' ? 'remote.pc.launchSent' : 'remote.pc.stopSent', { name: instance.name, pc: pc.name }))
  } catch (e) {
    toasts.error(e)
  } finally {
    // Kurz gesperrt lassen – der Stand vom PC kommt gleich.
    setTimeout(() => (busy.value[key] = false), 3000)
  }
}

async function installPack(pc: RemotePeer) {
  const code = normalizePackCode(packCodes.value[pc.id] ?? '')
  if (!code) {
    toasts.error(t('packs.code.invalid'))
    return
  }
  const key = `${pc.id}:pack`
  busy.value[key] = true
  try {
    await remote.send(pc.id, 'install_pack_code', { code })
    toasts.info(t('remote.pc.installSent', { code, pc: pc.name }))
    packCodes.value[pc.id] = ''
  } catch (e) {
    toasts.error(e)
  } finally {
    busy.value[key] = false
  }
}

async function removePc(pc: RemotePeer) {
  removing.value = pc.id
  try {
    await remote.removePc(pc.id)
  } catch (e) {
    toasts.error(e)
  } finally {
    removing.value = null
  }
}

/** Zustand eines Knopfs: wartet der PC noch auf den Befehl oder läuft er? */
function waiting(pc: RemotePeer, instance: StatusInstance): boolean {
  if (busy.value[`${pc.id}:${instance.id}`]) return true
  const last = remote.lastFor(pc.id, instance.id)
  return !!last && (last.state === 'pending' || last.state === 'running') && Date.now() - last.at < 60_000
}

function loaderLine(i: StatusInstance): string {
  return [i.version, i.loader && i.loader !== 'vanilla' ? i.loader : null].filter(Boolean).join(' · ')
}
</script>

<template>
  <div class="mx-auto w-full max-w-3xl p-6 mobile:p-4">
    <PageHeader :title="t('remote.pc.title')" :subtitle="t('remote.pc.subtitle')">
      <button v-if="trs.enabled && remote.pcs.length && !pairOpen" type="button" class="btn btn-ghost px-3 py-1.5 text-xs" @click="pairOpen = true">
        {{ t('remote.pc.pairAnother') }}
      </button>
    </PageHeader>

    <TrsGate what="remote">
      <!-- Koppeln -->
      <section v-if="pairOpen" class="card mb-5 p-4" data-testid="remote-pair-form">
        <h2 class="text-sm font-semibold text-base-50">{{ t('remote.pc.pairTitle') }}</h2>
        <p class="mt-1 text-xs leading-relaxed text-base-400">{{ t('remote.pc.pairIntro') }}</p>
        <button v-if="canScan" type="button" class="btn btn-primary mt-3 w-full justify-center py-2.5" :disabled="scanning || pairing" @click="scan">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.qr" /></svg>
          {{ scanning ? t('remote.pc.scanning') : t('remote.pc.scan') }}
        </button>
        <form class="mt-3 flex gap-2" @submit.prevent="pair">
          <input
            v-model="codeInput"
            class="field display min-w-0 flex-1 tracking-[0.2em] uppercase"
            maxlength="80"
            placeholder="XXX-XXX"
            spellcheck="false"
            autocomplete="off"
            autocapitalize="characters"
            :aria-label="t('remote.pc.codeLabel')"
            :disabled="pairing"
            data-testid="remote-code-input"
          />
          <button type="submit" class="btn btn-primary shrink-0" :disabled="pairing || !codeInput.trim()">{{ pairing ? t('remote.pc.pairing') : t('remote.pc.pair') }}</button>
        </form>
        <p v-if="pairError" role="alert" class="mt-2 text-sm text-redstone-300">{{ pairError }}</p>
        <p class="mt-3 text-xs text-base-500">{{ t('remote.pc.sameAccount') }}</p>
        <button v-if="remote.pcs.length" type="button" class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" @click="pairOpen = false">{{ t('common.actions.cancel') }}</button>
      </section>

      <div v-if="loading && !remote.pcsLoaded" class="space-y-3">
        <div v-for="i in 2" :key="i" class="skeleton h-32" />
      </div>
      <p v-else-if="loadError" role="alert" class="text-sm text-redstone-300">{{ loadError }}</p>

      <!-- Gekoppelte PCs -->
      <section v-for="pc in remote.pcs" :key="pc.id" class="card mb-5 p-4" data-testid="remote-pc">
        <header class="flex items-center gap-3">
          <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-base-800 text-base-200">
            <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.monitor" /></svg>
          </span>
          <div class="min-w-0 flex-1">
            <p class="truncate font-semibold text-base-50">{{ pc.name }}</p>
            <p class="flex items-center gap-1.5 text-xs" :class="remote.isOnline(pc) ? 'text-emerald-400' : 'text-base-400'">
              <span class="size-2 rounded-full" :class="remote.isOnline(pc) ? 'bg-emerald-400' : 'bg-base-600'" />
              {{ remote.isOnline(pc) ? t('remote.pc.online') : t('remote.pc.offline') }}
            </p>
          </div>
          <button type="button" class="btn btn-ghost px-2.5 py-1.5 text-xs" :disabled="removing === pc.id" @click="removePc(pc)">{{ t('remote.pc.remove') }}</button>
        </header>

        <p v-if="!remote.isOnline(pc)" class="mt-3 rounded-md border border-base-800 bg-base-900 px-3 py-2 text-xs leading-relaxed text-base-400">
          {{ t('remote.pc.offlineHint') }}
        </p>

        <template v-else-if="pc.status">
          <!-- Laufende Aufgaben (Installationen, Spielstart) -->
          <ul v-if="pc.status.tasks.length" class="mt-4 space-y-2">
            <li v-for="(task, i) in pc.status.tasks" :key="i" class="rounded-lg bg-base-900 px-3 py-2">
              <div class="mb-1.5 flex items-center justify-between gap-2 text-xs">
                <span class="truncate text-base-200">{{ task.title }}</span>
                <span v-if="task.progress !== null" class="shrink-0 text-base-400 tabular-nums">{{ Math.round(task.progress * 100) }} %</span>
              </div>
              <RedstoneWire :percent="(task.progress ?? 0) * 100" :indeterminate="task.progress === null" :segments="20" />
            </li>
          </ul>

          <!-- Instanzen -->
          <h3 class="mt-4 mb-2 text-xs font-semibold tracking-wider text-base-400 uppercase">{{ t('remote.pc.instances') }}</h3>
          <p v-if="!pc.status.allow.launch" class="mb-2 text-xs text-base-500">{{ t('remote.pc.launchOff') }}</p>
          <ul v-if="pc.status.instances.length" class="divide-y divide-base-800">
            <li v-for="inst in pc.status.instances" :key="inst.id" class="flex items-center gap-3 py-2.5">
              <span class="block size-10 shrink-0 overflow-hidden rounded-lg">
                <PixelIdenticon :seed="inst.iconHash ?? inst.id" :letter="inst.name.slice(0, 1)" />
              </span>
              <div class="min-w-0 flex-1">
                <p class="truncate text-sm font-medium text-base-50">{{ inst.name }}</p>
                <p class="truncate text-xs text-base-400">
                  {{ loaderLine(inst) }}
                  <span v-if="inst.running" class="ml-1 font-semibold text-lamp-300">· {{ t('remote.pc.running') }}</span>
                </p>
              </div>
              <button
                v-if="inst.running"
                type="button"
                class="btn btn-ghost min-h-11 shrink-0 px-3"
                :disabled="!pc.status.allow.launch || waiting(pc, inst)"
                :aria-label="t('remote.pc.stopNamed', { name: inst.name })"
                @click="command(pc, 'stop_instance', inst)"
              >
                <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><path :d="icons.stop" /></svg>
                {{ t('remote.pc.stop') }}
              </button>
              <button
                v-else
                type="button"
                class="btn btn-primary min-h-11 shrink-0 px-3"
                :disabled="!pc.status.allow.launch || waiting(pc, inst)"
                :aria-label="t('remote.pc.playNamed', { name: inst.name })"
                data-testid="remote-play"
                @click="command(pc, 'launch_instance', inst)"
              >
                <svg viewBox="0 0 24 24" class="size-4" fill="currentColor"><path :d="icons.play" /></svg>
                {{ waiting(pc, inst) ? t('remote.pc.sending') : t('remote.pc.play') }}
              </button>
            </li>
          </ul>
          <p v-else class="text-sm text-base-400">{{ t('remote.pc.noInstances') }}</p>

          <!-- Modpack per Code auf dem PC installieren -->
          <h3 class="mt-5 mb-2 text-xs font-semibold tracking-wider text-base-400 uppercase">{{ t('remote.pc.installTitle') }}</h3>
          <p v-if="!pc.status.allow.install" class="text-xs text-base-500">{{ t('remote.pc.installOff') }}</p>
          <form v-else class="flex gap-2" @submit.prevent="installPack(pc)">
            <input
              v-model="packCodes[pc.id]"
              class="field display min-w-0 flex-1 tracking-wider uppercase"
              maxlength="120"
              placeholder="TRS-XXXX-XXXX"
              spellcheck="false"
              autocomplete="off"
              autocapitalize="characters"
              :aria-label="t('packs.code.label')"
            />
            <button type="submit" class="btn btn-primary shrink-0" :disabled="busy[`${pc.id}:pack`] || !(packCodes[pc.id] ?? '').trim()">
              {{ t('remote.pc.install') }}
            </button>
          </form>
          <p class="mt-2 text-xs text-base-500">{{ t('remote.pc.installHint') }}</p>
        </template>
        <p v-else class="mt-3 text-sm text-base-400">{{ t('remote.pc.noStatus') }}</p>
      </section>

      <RedstoneEmpty v-if="remote.pcsLoaded && !remote.pcs.length && !pairOpen" :title="t('remote.pc.emptyTitle')" :text="t('remote.pc.emptyText')" />
    </TrsGate>
  </div>
</template>
