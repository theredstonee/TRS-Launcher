import { gsap } from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { SplitText } from 'gsap/SplitText'
import Lenis from 'lenis'

// Scroll-Effekte der Inhaltsseiten: sanftes Scrollen (Lenis, nur Mausrad – Touch bleibt nativ) im Takt von GSAP
// plus dezente Einblendungen. Wird nur von plugins/scroll-fx.client.ts geladen (eigener Chunk).
//
// Markierungen in den Vorlagen:
//   data-fx="rise"        Block steigt beim ersten Sichtbarwerden leicht auf
//   data-fx="stagger"     die direkten Kinder steigen nacheinander auf
//   h2.heading            Abschnittstitel: Wörter steigen auf (SplitText, danach wieder zurückgebaut)
//   data-fx-hero          Hero: Inhalt und Szene gleiten beim Wegscrollen langsamer mit, Inhalt blendet aus
//   data-fx-showcase      Bühne auf der Startseite: am Desktop angeheftet, Bilder blenden ineinander
//
// Versteckt wird nur unter html.fx-ready (main.css) – ohne JS, für Suchmaschinen und bei
// „Bewegung reduzieren“ bleibt alles sofort sichtbar.

gsap.registerPlugin(ScrollTrigger, SplitText)

const IN = 'data-fx-in'
const EASE = 'power2.out'
const ITEMS = '[data-fx="rise"], [data-fx="stagger"] > *'
/** Elemente, in denen das Mausrad nie die Seite glätten soll (Dialoge, Menü). */
const PREVENT = 'dialog, [role="dialog"], [aria-modal="true"], #site-menu'

export interface ScrollFx {
  /** Lenis einschalten (bleibt über Seitenwechsel zwischen Inhaltsseiten bestehen). */
  enableSmooth(): void
  /** Lenis aus (Seite ohne Effekte, z. B. Admin). */
  disableSmooth(): void
  /** Effekte für die aktuelle Seite aufbauen (vorher Abgebautes wird ersetzt). */
  mount(root: HTMLElement): void
  /** Effekte der Seite abbauen: Trigger, Tweens, zerlegte Titel, Klassen. */
  unmount(): void
  /** Sprungziel per Lenis anfahren (Kopfzeile wird berücksichtigt); false = nicht zuständig. */
  scrollToHash(hash: string): boolean
}

export function createScrollFx(): ScrollFx {
  let mm: gsap.MatchMedia | null = null
  let smoothMm: gsap.MatchMedia | null = null
  let lenis: Lenis | null = null

  // Lenis bleibt beim Seitenwechsel bestehen: so springt der Router ohne natives Smooth-Scrolling
  // (html.lenis → scroll-behavior: auto) nach oben bzw. zum Anker, und Lenis übernimmt die Position.
  function enableSmooth() {
    if (smoothMm) return
    smoothMm = gsap.matchMedia()
    smoothMm.add('(prefers-reduced-motion: no-preference)', () => {
      const smooth = new Lenis({
        autoRaf: false,
        smoothWheel: true,
        syncTouch: false,
        allowNestedScroll: true,
        stopInertiaOnNavigate: true,
        prevent: (node) => node.matches(PREVENT),
      })
      lenis = smooth
      smooth.on('scroll', ScrollTrigger.update)
      const tick = (time: number) => smooth.raf(time * 1000)
      gsap.ticker.add(tick)
      gsap.ticker.lagSmoothing(0)
      return () => {
        gsap.ticker.remove(tick)
        gsap.ticker.lagSmoothing(500, 33)
        smooth.destroy()
        if (lenis === smooth) lenis = null
      }
    })
  }

  function disableSmooth() {
    smoothMm?.revert()
    smoothMm = null
  }

  function mount(root: HTMLElement) {
    unmount()
    mm = gsap.matchMedia()
    mm.add(
      { motion: '(prefers-reduced-motion: no-preference)', desktop: '(min-width: 1024px)' },
      (ctx) => {
        const { motion, desktop } = ctx.conditions as { motion: boolean, desktop: boolean }
        if (!motion) return
        return setup(root, desktop, ctx)
      },
    )
  }

  function unmount() {
    mm?.revert()
    mm = null
  }

  function setup(root: HTMLElement, desktop: boolean, ctx: gsap.Context): () => void {
    const html = document.documentElement
    const cleanups: (() => void)[] = []
    lenis?.resize()

    // --- Bühne zuerst: sie ändert das Layout, danach wird gemessen ------------------------
    const showcase = desktop ? root.querySelector<HTMLElement>('[data-fx-showcase]') : null
    if (showcase) cleanups.push(pinShowcase(showcase))

    // --- Hero --------------------------------------------------------------------------------
    const hero = root.querySelector<HTMLElement>('[data-fx-hero]')
    if (hero) {
      const scroll = { trigger: hero, start: 'top top', end: 'bottom top', scrub: true, invalidateOnRefresh: true }
      gsap.set(hero, { '--hero-shift': '0px' })
      gsap.to(hero, { '--hero-shift': () => `${Math.round(hero.offsetHeight * 0.16)}px`, ease: 'none', scrollTrigger: scroll })
      const content = hero.querySelector('[data-fx-hero-content]')
      if (content) gsap.to(content, { opacity: 0.12, ease: 'none', scrollTrigger: { ...scroll } })
    }

    // --- Einblendungen ----------------------------------------------------------------------
    const items = [...root.querySelectorAll<HTMLElement>(ITEMS)].filter((el) => !el.hasAttribute(IN))
    const inItem = (el: Element) => el.parentElement?.closest(`${ITEMS}, [data-fx-panel]`) != null
    const heads = [...root.querySelectorAll<HTMLElement>('h2.heading')].filter((h) => !h.hasAttribute(IN) && !inItem(h))
    for (const h of heads) h.setAttribute('data-fx-h', '')

    // Was beim Aufbau schon im Bild ist (oder darüber), bleibt einfach sichtbar – kein Aufblitzen.
    const fold = window.innerHeight * 0.92
    for (const el of [...items, ...heads]) if (el.getBoundingClientRect().top < fold) el.setAttribute(IN, '')
    html.classList.add('fx-ready')
    cleanups.push(() => html.classList.remove('fx-ready'))

    const reveal = (batch: Element[]) => {
      const els = batch.filter((el) => !el.hasAttribute(IN))
      if (!els.length) return
      for (const el of els) el.setAttribute(IN, '')
      ctx.add(() => {
        gsap.fromTo(
          els,
          { opacity: 0, y: 20 },
          // Große Gruppen nicht ewig staffeln: höchstens ~0,4 s Versatz insgesamt.
          { opacity: 1, y: 0, duration: 0.7, ease: EASE, stagger: Math.min(0.08, 0.4 / els.length), overwrite: true, clearProps: 'opacity,transform' },
        )
      })
    }
    /** Übersprungen (schon oberhalb): ohne Animation zeigen. */
    const show = (batch: Element[]) => {
      for (const el of batch) el.setAttribute(IN, '')
    }
    const pending = items.filter((el) => !el.hasAttribute(IN))
    // Auch beim Überspringen (Ende-Taste, Sprungmarke) zeigen: onLeave/onEnterBack statt nur onEnter.
    if (pending.length) ScrollTrigger.batch(pending, { start: 'top 92%', onEnter: reveal, onLeave: show, onEnterBack: reveal })

    const splitIn = (h: HTMLElement) => {
      if (h.hasAttribute(IN)) return
      h.setAttribute(IN, '')
      // Nur reiner Text wird zerlegt – Vue ersetzt ihn beim Sprachwechsel per textContent.
      const plain = h.childNodes.length === 1 && h.firstChild?.nodeType === Node.TEXT_NODE
      ctx.add(() => {
        if (!plain) {
          gsap.fromTo(h, { opacity: 0, y: 16 }, { opacity: 1, y: 0, duration: 0.6, ease: EASE, clearProps: 'opacity,transform' })
          return
        }
        const split = SplitText.create(h, { type: 'words' })
        gsap.fromTo(
          split.words,
          { opacity: 0, y: 16 },
          { opacity: 1, y: 0, duration: 0.6, ease: EASE, stagger: 0.045, onComplete: () => split.revert() },
        )
      })
    }
    for (const h of heads) {
      if (h.hasAttribute(IN)) continue
      ScrollTrigger.create({ trigger: h, start: 'top 90%', onEnter: () => splitIn(h), onLeave: () => h.setAttribute(IN, ''), onEnterBack: () => splitIn(h) })
    }

    // --- Nachmessen, wenn Bilder/Schriften nachladen oder sich die Höhe ändert ---------------
    let timer: ReturnType<typeof setTimeout> | undefined
    let lastHeight = root.offsetHeight
    const refresh = () => {
      clearTimeout(timer)
      timer = setTimeout(() => {
        lastHeight = root.offsetHeight
        ScrollTrigger.refresh()
        lenis?.resize()
      }, 150)
    }
    const resize = new ResizeObserver(() => {
      if (root.offsetHeight !== lastHeight) refresh()
    })
    resize.observe(root)
    // Später eingefügte Elemente (Filter, nachgeladene Listen) einfach zeigen – nie unsichtbar stehen lassen.
    const added = new MutationObserver((records) => {
      for (const r of records) {
        for (const node of r.addedNodes) {
          if (!(node instanceof HTMLElement)) continue
          if (node.matches(ITEMS)) node.setAttribute(IN, '')
          for (const el of node.querySelectorAll(ITEMS)) el.setAttribute(IN, '')
        }
      }
    })
    added.observe(root, { childList: true, subtree: true })
    if (document.readyState !== 'complete') window.addEventListener('load', refresh, { once: true })
    void document.fonts?.ready.then(refresh)
    cleanups.push(() => {
      clearTimeout(timer)
      resize.disconnect()
      added.disconnect()
      window.removeEventListener('load', refresh)
      for (const h of heads) h.removeAttribute('data-fx-h')
    })

    ScrollTrigger.refresh()
    return () => {
      for (const fn of cleanups.reverse()) fn()
    }
  }

  /** Bühne anheften: Texte und Bilder liegen übereinander und blenden beim Scrollen ineinander. */
  function pinShowcase(section: HTMLElement): () => void {
    const pin = section.querySelector<HTMLElement>('[data-fx-pin]') ?? section
    const texts = [...section.querySelectorAll<HTMLElement>('[data-fx-panel-text]')]
    const shots = [...section.querySelectorAll<HTMLElement>('[data-fx-panel-shot]')]
    const steps = [...section.querySelectorAll<HTMLElement>('[data-fx-step]')]
    const n = Math.min(texts.length, shots.length)
    if (n < 2) return () => {}

    section.setAttribute('data-fx-pinned', '')
    for (const p of section.querySelectorAll('[data-fx-panel]')) p.setAttribute(IN, '')

    gsap.set(texts.slice(1), { opacity: 0, y: 18 })
    gsap.set(shots.slice(1), { opacity: 0, scale: 1.04 })
    gsap.set(steps.slice(1), { opacity: 0.3 })

    // Je Bild: stehen lassen (HOLD), dann überblenden (FADE). Ohne Einrasten (snap) – das hielte kleine Scrollbewegungen fest.
    const HOLD = 1
    const FADE = 0.45
    const at = (i: number) => (i - 1) * (HOLD + FADE) + HOLD
    const tl = gsap.timeline({
      defaults: { duration: FADE, ease: 'power1.inOut' },
      scrollTrigger: {
        trigger: pin,
        // Mitte der Bühne in die Mitte der Fläche unter der Kopfzeile (64 px).
        start: 'center center+=32',
        end: () => `+=${Math.round(window.innerHeight * 0.6 * (n - 1))}`,
        pin: true,
        scrub: 0.6,
        anticipatePin: 1,
        invalidateOnRefresh: true,
        onUpdate: () => setActive(),
      },
    })
    for (let i = 1; i < n; i++) {
      // Texte nacheinander (nie zwei halb sichtbar übereinander), Bilder echt überblendet.
      tl.to(texts[i - 1]!, { opacity: 0, y: -18, duration: FADE * 0.45 }, at(i))
        .to(texts[i]!, { opacity: 1, y: 0, duration: FADE * 0.55 }, at(i) + FADE * 0.45)
        .to(shots[i - 1]!, { opacity: 0, scale: 0.98 }, at(i))
        .to(shots[i]!, { opacity: 1, scale: 1 }, at(i))
      if (steps[i]) tl.to(steps[i]!, { opacity: 1, duration: FADE * 0.6 }, at(i))
    }
    tl.to({}, { duration: HOLD })

    // Nur der sichtbare Text ist anklickbar; Tastatur-Fokus auf einem verdeckten Text fährt dorthin.
    let active = -1
    function setActive() {
      const t = tl.time()
      let idx = 0
      for (let i = 1; i < n; i++) if (t >= at(i) + FADE / 2) idx = i
      if (idx === active) return
      active = idx
      texts.forEach((el, i) => el.toggleAttribute('data-active', i === idx))
    }
    setActive()

    const onFocus = (e: FocusEvent) => {
      const i = texts.findIndex((el) => el.contains(e.target as Node))
      const st = tl.scrollTrigger
      if (i < 0 || i === active || !st) return
      const time = i === 0 ? 0 : at(i) + FADE
      const top = st.start + (st.end - st.start) * (time / tl.duration())
      if (lenis) lenis.scrollTo(top, { immediate: true })
      else window.scrollTo(0, top)
    }
    section.addEventListener('focusin', onFocus)

    return () => {
      section.removeEventListener('focusin', onFocus)
      section.removeAttribute('data-fx-pinned')
      for (const el of texts) el.removeAttribute('data-active')
    }
  }

  function scrollToHash(hash: string): boolean {
    if (!lenis) return false
    let id = hash.replace(/^#/, '')
    try {
      id = decodeURIComponent(id)
    } catch {
      return false
    }
    const el = id ? document.getElementById(id) : null
    if (!el) return false
    // Ziele mit scroll-margin (scroll-mt-24) rechnet Lenis selbst ein, sonst Platz für die Kopfzeile lassen.
    const margin = Number.parseFloat(getComputedStyle(el).scrollMarginTop) || 0
    const header = document.querySelector('.site-header')?.getBoundingClientRect().height ?? 0
    lenis.scrollTo(el, { offset: margin ? 0 : -(header + 16) })
    return true
  }

  return { enableSmooth, disableSmooth, mount, unmount, scrollToHash }
}
