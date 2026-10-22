import { useEffect } from 'react'

const SHOW_DELAY = 450
const TIP_ID = 'rv-tip'
const GAP = 6
const EDGE = 8

/** A short keyboard hint at the end of a title, such as "[", "]" or "⌘K", is shown as a key. */
function splitHint(title: string): [string, string | null] {
  const match = /^(.*\S)\s+([[\]/]|⌘\S{1,2})$/.exec(title)
  return match ? [match[1]!, match[2]!] : [title, null]
}

/**
 * One tooltip for the whole app. Any element with a `title` gets it on mouse hover or keyboard
 * focus, after a short delay, styled like the rest of Rivell. The browser's own tooltip is held
 * back only while ours is showing: the `title` is restored as soon as it hides, so the attribute
 * stays the source of truth for assistive technology and tests.
 */
export function useTooltips(): void {
  useEffect(() => {
    const tip = document.createElement('div')
    tip.className = 'rv-tip'
    tip.id = TIP_ID
    tip.setAttribute('role', 'tooltip')
    document.body.append(tip)

    let target: HTMLElement | null = null
    let timer: number | undefined

    function hide() {
      window.clearTimeout(timer)
      timer = undefined
      tip.removeAttribute('data-open')
      if (target) describe(target, false)
      if (target?.dataset.rvTitle !== undefined) {
        // React may have written a new title while ours showed; that one wins.
        if (target.getAttribute('title') === null) target.setAttribute('title', target.dataset.rvTitle)
        delete target.dataset.rvTitle
      }
      target = null
    }

    // While the tip shows it describes its element, next to any description the element already has.
    function describe(element: HTMLElement, on: boolean) {
      const ids = (element.getAttribute('aria-describedby') ?? '').split(' ').filter((id) => id && id !== TIP_ID)
      if (on) ids.push(TIP_ID)
      if (ids.length) element.setAttribute('aria-describedby', ids.join(' '))
      else element.removeAttribute('aria-describedby')
    }

    function show(element: HTMLElement, holdTitle: boolean) {
      const title = element.getAttribute('title')
      if (!title || !element.isConnected) return
      const [text, hint] = splitHint(title)
      tip.replaceChildren(document.createTextNode(text))
      if (hint) {
        const key = document.createElement('kbd')
        key.textContent = hint
        tip.append(key)
      }
      // Only a mouse hover would also raise the browser's tooltip. On keyboard focus the title stays,
      // as it may be the element's only description.
      if (holdTitle) {
        element.dataset.rvTitle = title
        element.removeAttribute('title')
      }

      const box = element.getBoundingClientRect()
      tip.style.left = '0px'
      tip.style.top = '0px'
      tip.setAttribute('data-open', '')
      describe(element, true)
      const size = tip.getBoundingClientRect()
      const below = box.bottom + GAP + size.height <= window.innerHeight - EDGE
      const top = below ? box.bottom + GAP : box.top - GAP - size.height
      const left = Math.min(window.innerWidth - EDGE - size.width, Math.max(EDGE, box.left + box.width / 2 - size.width / 2))
      tip.style.left = `${Math.round(left)}px`
      tip.style.top = `${Math.round(top)}px`
      tip.dataset.side = below ? 'below' : 'above'
    }

    function arm(element: HTMLElement, holdTitle: boolean) {
      if (element === target) return
      hide()
      target = element
      timer = window.setTimeout(() => {
        if (target === element) show(element, holdTitle)
      }, SHOW_DELAY)
    }

    // An element can leave the page while its tip shows (a route change, a closed panel).
    const watcher = window.setInterval(() => {
      if (target && !target.isConnected) hide()
    }, 300)

    function titled(node: EventTarget | null): HTMLElement | null {
      return node instanceof Element ? (node.closest('[title]') as HTMLElement | null) : null
    }

    function onPointerOver(event: PointerEvent) {
      if (event.pointerType !== 'mouse') return
      const element = titled(event.target)
      if (element) arm(element, true)
      else if (target && !target.contains(event.target as Node)) hide()
    }
    function onPointerOut(event: PointerEvent) {
      if (target && !target.contains(event.relatedTarget as Node | null)) hide()
    }
    function onFocusIn(event: FocusEvent) {
      const element = titled(event.target)
      if (element && element === event.target && element.matches(':focus-visible')) arm(element, false)
    }

    document.addEventListener('pointerover', onPointerOver)
    document.addEventListener('pointerout', onPointerOut)
    document.addEventListener('focusin', onFocusIn)
    document.addEventListener('focusout', hide)
    document.addEventListener('pointerdown', hide, true)
    document.addEventListener('keydown', hide, true)
    window.addEventListener('scroll', hide, true)
    window.addEventListener('blur', hide)
    return () => {
      window.clearInterval(watcher)
      hide()
      tip.remove()
      document.removeEventListener('pointerover', onPointerOver)
      document.removeEventListener('pointerout', onPointerOut)
      document.removeEventListener('focusin', onFocusIn)
      document.removeEventListener('focusout', hide)
      document.removeEventListener('pointerdown', hide, true)
      document.removeEventListener('keydown', hide, true)
      window.removeEventListener('scroll', hide, true)
      window.removeEventListener('blur', hide)
    }
  }, [])
}
