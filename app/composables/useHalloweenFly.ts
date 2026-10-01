import type { Ref } from 'vue'
import { HALLOWEEN_WITCH, WITCH_FRAME_MS } from '~/utils/halloween'
import {
  FLY_BATS_EVERY_MS,
  FLY_WITCH_EVERY_MS,
  createFlySwarm,
  createFlyWitch,
  drawFlyBat,
  drawFlySpark,
  drawWitchSprite,
  flyAlpha,
  flyBatWingUp,
  flyBatY,
  flyGap,
  flyWitchBob,
  flyWitchX,
  stepFlySwarm,
  stepFlyWitch,
  type FlySwarm,
  type FlyWitch,
} from '~/utils/halloweenFly'

/**
 * Hexe und Fledermäuse über dem ganzen Fenster. Eine requestAnimationFrame-Schleife
 * nur, solange etwas fliegt; dazwischen nur die beiden Pausen-Timer.
 * Steht, wenn die Einstellung „Animationen“ das will oder das Fenster verdeckt ist.
 * Höchstens eine Hexe gleichzeitig.
 */
export function useHalloweenFly(canvas: Readonly<Ref<HTMLCanvasElement | null>>) {
  const { reduced } = useMotion()

  let witch: FlyWitch | null = null
  let swarm: FlySwarm | null = null
  let raf = 0
  let last = 0
  let stopped = false
  let witchTimer = 0
  let batTimer = 0
  /** Rest der laufenden Pause, für das Fortsetzen nach dem Verdecken. */
  let witchDelay = 0
  let batDelay = 0
  let witchAt = 0
  let batAt = 0
  /** Eine Pause läuft (oder soll nach dem Verdecken weiterlaufen). Während des Flugs falsch. */
  let witchPaused = false
  let batPaused = false

  const image = new Image()
  image.src = HALLOWEEN_WITCH

  function view(): { w: number; h: number } | null {
    const el = canvas.value
    if (!el) return null
    const w = el.clientWidth
    const h = el.clientHeight
    if (w < 2 || h < 2) return null
    return { w, h }
  }

  function paint(w: number, h: number) {
    const el = canvas.value
    if (!el) return
    const dpr = Math.min(2, window.devicePixelRatio || 1)
    const bw = Math.round(w * dpr)
    const bh = Math.round(h * dpr)
    if (el.width !== bw || el.height !== bh) {
      el.width = bw
      el.height = bh
    }
    const ctx = el.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.imageSmoothingEnabled = false
    ctx.clearRect(0, 0, w, h)

    if (swarm) {
      ctx.save()
      ctx.globalAlpha = flyAlpha(swarm.elapsed, swarm.duration)
      for (const b of swarm.bats) {
        drawFlyBat(ctx, b.x, flyBatY(swarm.elapsed, b), flyBatWingUp(swarm.elapsed, b), b.scale)
      }
      ctx.restore()
    }
    if (witch) {
      ctx.save()
      ctx.globalAlpha = flyAlpha(witch.elapsed, witch.duration)
      for (const s of witch.sparks) drawFlySpark(ctx, s)
      if (image.complete && image.naturalWidth) {
        const frame = Math.floor(witch.elapsed / WITCH_FRAME_MS)
        drawWitchSprite(ctx, image, frame, flyWitchX(witch, w), witch.y + flyWitchBob(witch), witch.scale, witch.dir < 0)
      }
      ctx.restore()
    }
  }

  function clearCanvas() {
    const size = view()
    if (size) paint(size.w, size.h)
  }

  function stopLoop() {
    cancelAnimationFrame(raf)
    raf = 0
    last = 0
  }

  function loop(now: number) {
    if (stopped || document.hidden || reduced.value) {
      stopLoop()
      return
    }
    if (!last) last = now
    const dt = Math.min(40, now - last)
    last = now
    const size = view()
    if (!size) {
      raf = requestAnimationFrame(loop)
      return
    }
    if (witch && !stepFlyWitch(witch, dt, size.w)) {
      witch = null
      // Nächste Hexe erst nach dem Überflug, damit nie zwei gleichzeitig fliegen.
      if (!witchTimer && !witchPaused) armWitch(flyGap(FLY_WITCH_EVERY_MS.min, FLY_WITCH_EVERY_MS.max))
    }
    if (swarm && !stepFlySwarm(swarm, dt)) {
      swarm = null
      if (!batTimer && !batPaused) armBats(flyGap(FLY_BATS_EVERY_MS.min, FLY_BATS_EVERY_MS.max))
    }
    paint(size.w, size.h)
    if (witch || swarm) raf = requestAnimationFrame(loop)
    else stopLoop()
  }

  function kick() {
    if (stopped || document.hidden || reduced.value) return
    if (raf || (!witch && !swarm)) return
    last = 0
    raf = requestAnimationFrame(loop)
  }

  function armWitch(delay: number) {
    window.clearTimeout(witchTimer)
    witchPaused = true
    witchDelay = delay
    witchAt = performance.now()
    witchTimer = window.setTimeout(startWitch, delay)
  }

  function armBats(delay: number) {
    window.clearTimeout(batTimer)
    batPaused = true
    batDelay = delay
    batAt = performance.now()
    batTimer = window.setTimeout(startBats, delay)
  }

  function startWitch() {
    witchTimer = 0
    witchPaused = false
    if (stopped || reduced.value) return
    // Fällig geworden, während das Fenster zu ist: beim Zeigen sofort los.
    if (document.hidden) {
      witchDelay = 0
      witchPaused = true
      return
    }
    const size = view()
    if (!size) {
      armWitch(1000)
      return
    }
    // Nie eine zweite, auch wenn der Timer mitten im Flug käme.
    if (!witch) {
      witch = createFlyWitch(size.w, size.h)
      kick()
    } else if (!witchTimer) {
      armWitch(flyGap(FLY_WITCH_EVERY_MS.min, FLY_WITCH_EVERY_MS.max))
    }
  }

  function startBats() {
    batTimer = 0
    batPaused = false
    if (stopped || reduced.value) return
    if (document.hidden) {
      batDelay = 0
      batPaused = true
      return
    }
    const size = view()
    if (!size) {
      armBats(1000)
      return
    }
    if (!swarm) {
      swarm = createFlySwarm(size.w, size.h)
      kick()
    } else if (!batTimer) {
      armBats(flyGap(FLY_BATS_EVERY_MS.min, FLY_BATS_EVERY_MS.max))
    }
  }

  function disarm() {
    window.clearTimeout(witchTimer)
    window.clearTimeout(batTimer)
    witchTimer = 0
    batTimer = 0
    witchPaused = false
    batPaused = false
  }

  function onHide() {
    if (document.hidden) {
      const now = performance.now()
      if (witchTimer) {
        witchDelay = Math.max(0, witchDelay - (now - witchAt))
        window.clearTimeout(witchTimer)
        witchTimer = 0
        witchPaused = true
      }
      if (batTimer) {
        batDelay = Math.max(0, batDelay - (now - batAt))
        window.clearTimeout(batTimer)
        batTimer = 0
        batPaused = true
      }
      stopLoop()
      return
    }
    if (stopped || reduced.value) return
    kick()
    if (witchPaused && !witchTimer) armWitch(witchDelay)
    if (batPaused && !batTimer) armBats(batDelay)
  }

  function haltFlights() {
    disarm()
    witch = null
    swarm = null
    stopLoop()
    clearCanvas()
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', onHide)
    if (reduced.value) return
    const witchWait = flyGap(FLY_WITCH_EVERY_MS.min, FLY_WITCH_EVERY_MS.max)
    const batWait = flyGap(FLY_BATS_EVERY_MS.min, FLY_BATS_EVERY_MS.max)
    if (document.hidden) {
      witchDelay = witchWait
      batDelay = batWait
      witchPaused = true
      batPaused = true
      return
    }
    armWitch(witchWait)
    armBats(batWait)
  })

  watch(reduced, (on) => {
    if (stopped) return
    if (on) {
      haltFlights()
      return
    }
    const witchWait = flyGap(FLY_WITCH_EVERY_MS.min, FLY_WITCH_EVERY_MS.max)
    const batWait = flyGap(FLY_BATS_EVERY_MS.min, FLY_BATS_EVERY_MS.max)
    if (document.hidden) {
      witchDelay = witchWait
      batDelay = batWait
      witchPaused = true
      batPaused = true
      return
    }
    armWitch(witchWait)
    armBats(batWait)
  })

  onBeforeUnmount(() => {
    stopped = true
    haltFlights()
    document.removeEventListener('visibilitychange', onHide)
  })
}
