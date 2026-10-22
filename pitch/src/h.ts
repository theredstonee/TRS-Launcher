// Small HTML builders for the slides. All text is our own constant copy (no user input), so building HTML strings is safe.

export type Lang = 'de' | 'en'
export type T = { de: string; en: string }

let lang: Lang = 'de'
export function setLang(l: Lang) {
  lang = l
}
export function getLang(): Lang {
  return lang
}
/** Pick the current language. */
export function t(x: T | string): string {
  return typeof x === 'string' ? x : x[lang]
}

export const A = (name: string) => `assets/${name}`
export const img = (name: string) => A(`${name}.webp`)

const st = (o: Record<string, string | number | undefined>) =>
  Object.entries(o)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}:${v}`)
    .join(';')

/** Animated element: cls = '' (up) | pop | l | r | z | f | w */
export function a(inner: string, d: number, cls = '', extra = '', style = ''): string {
  return `<div class="a ${cls} ${extra}" style="--d:${d}ms;${style}">${inner}</div>`
}

export function head(kicker: T, title: T, lead?: T, d = 0): string {
  return (
    a(`<div class="kicker">${t(kicker)}</div>`, d, 'l') +
    a(`<h2>${t(title)}</h2>`, d + 120) +
    (lead ? a(`<p class="lead">${t(lead)}</p>`, d + 300) : '')
  )
}

export function points(items: T[], d: number, step = 140): string {
  return `<ul class="pts">${items.map((x, i) => `<li class="a l" style="--d:${d + i * step}ms"><span>${t(x)}</span></li>`).join('')}</ul>`
}

export function chips(items: (T | string)[], d: number, step = 90, cls: (i: number) => string = () => ''): string {
  return `<div class="chips">${items.map((x, i) => `<span class="chip a pop ${cls(i)}" style="--d:${d + i * step}ms">${t(x)}</span>`).join('')}</div>`
}

export interface Callout {
  x: number
  y: number
  label: T
  d: number
  left?: boolean
}
function callouts(list: Callout[] = []): string {
  return list
    .map(
      (c) =>
        `<div class="co${c.left ? ' left' : ''}" style="left:${c.x}%;top:${c.y}%"><div class="a pop" style="--d:${c.d}ms"><b></b><span>${t(c.label)}</span></div></div>`,
    )
    .join('')
}

export interface KB {
  /** start / end scale */
  s0?: number
  s1?: number
  x0?: string
  y0?: string
  x1?: string
  y1?: string
  /** transform origin in % */
  ox?: number
  oy?: number
}
function kbStyle(k: KB = {}): string {
  return st({
    '--s0': k.s0,
    '--s1': k.s1,
    '--x0': k.x0,
    '--y0': k.y0,
    '--x1': k.x1,
    '--y1': k.y1,
    '--ox': k.ox !== undefined ? `${k.ox}%` : undefined,
    '--oy': k.oy !== undefined ? `${k.oy}%` : undefined,
  })
}

/** Image with Ken Burns + callouts (callouts move with the image). */
export function shot(name: string, alt: T | string, kb?: KB, cos?: Callout[]): string {
  return `<div class="kb"><div class="kbi" style="${kbStyle(kb)}"><img src="${img(name)}" alt="${t(alt)}" decoding="async">${callouts(cos)}</div></div>`
}

/** Launcher window frame around a screenshot. */
export function win(name: string, alt: T | string, kb?: KB, cos?: Callout[], title = 'TRS Launcher'): string {
  return `<div class="win"><div class="win-bar"><img src="${A('icon.png')}" alt=""><span>${title}</span><i></i><i></i></div>${shot(name, alt, kb, cos)}</div>`
}

/** Game screen frame. */
export function game(name: string, alt: T | string, tag?: T | string, kb?: KB, cos?: Callout[]): string {
  return `<div class="game" style="position:relative">${shot(name, alt, kb, cos)}${tag ? `<div class="tag">${t(tag)}</div>` : ''}</div>`
}

export function phone(name: string, alt: T | string): string {
  return `<div class="phone"><div class="scr"><img src="${img(name)}" alt="${t(alt)}"></div></div>`
}

/** Absolutely positioned visual block: entrance (outer .a) → slow 3D drift (inner) → content. */
export function vis(
  inner: string,
  pos: { left?: number; top?: number; right?: number; bottom?: number; width: number; z?: number },
  d: number,
  enter = 'r',
  drift?: { ry0?: number; ry1?: number; rx0?: number; rx1?: number } | null,
  float = false,
): string {
  const ps = st({
    left: pos.left !== undefined ? `${pos.left}px` : undefined,
    top: pos.top !== undefined ? `${pos.top}px` : undefined,
    right: pos.right !== undefined ? `${pos.right}px` : undefined,
    bottom: pos.bottom !== undefined ? `${pos.bottom}px` : undefined,
    width: `${pos.width}px`,
    'z-index': pos.z,
  })
  const ds = drift
    ? st({
        '--ry0': drift.ry0 !== undefined ? `${drift.ry0}deg` : undefined,
        '--ry1': drift.ry1 !== undefined ? `${drift.ry1}deg` : undefined,
        '--rx0': drift.rx0 !== undefined ? `${drift.rx0}deg` : undefined,
        '--rx1': drift.rx1 !== undefined ? `${drift.rx1}deg` : undefined,
      })
    : ''
  let body = inner
  if (float) body = `<div class="float">${body}</div>`
  if (drift) body = `<div class="drift" style="${ds}">${body}</div>`
  return `<div class="vis a ${enter}" style="--d:${d}ms;${ps}">${body}</div>`
}

/** Text column on the left. */
export function col(inner: string, top = 150, width = 800, left = 110): string {
  return `<div class="col" style="top:${top}px;width:${width}px;left:${left}px">${inner}</div>`
}

/**
 * Animated number. The count-up is drawn by CSS (::before from data-v, aria-hidden); the final value sits next to it
 * as visually hidden text, so screen readers, copy & paste and text extraction never see an in-between value.
 */
export function count(to: number, dur: number, delay: number): string {
  const final = new Intl.NumberFormat(lang === 'de' ? 'de-DE' : 'en-US').format(to)
  return `<span class="count" aria-hidden="true" data-to="${to}" data-dur="${dur}" data-delay="${delay}" data-v="${final}"></span><span class="sr">${final}</span>`
}

export const CHECK = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12.5l5 5L20 6.5" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="square"/></svg>'
