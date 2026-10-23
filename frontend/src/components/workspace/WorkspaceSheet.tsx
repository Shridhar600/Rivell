import { useEffect, useRef, type ReactNode } from 'react'
import { CloseIcon } from './WorkspaceIcons'

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"])'

interface Props {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  /** Footer actions shown before Close, such as the deep link to the full record. */
  actions?: ReactNode
}

/** The canonical right-hand sheet: evidence, decision history and review notes open here. */
export function WorkspaceSheet({ open, title, onClose, children, actions }: Props) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const sheetRef = useRef<HTMLElement>(null)
  const returnFocus = useRef<HTMLElement | null>(null)
  // The latest onClose, read at key time: the effect must run only when the sheet opens or closes,
  // or every re-render of the page behind would move focus back to Close.
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  })

  useEffect(() => {
    if (!open) return
    returnFocus.current = document.activeElement as HTMLElement | null
    closeRef.current?.focus()
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Tab') {
        keepFocusInside(event)
        return
      }
      if (event.key !== 'Escape') return
      event.preventDefault()
      onCloseRef.current()
    }
    // The sheet is modal: Tab and Shift+Tab cycle through its own controls.
    function keepFocusInside(event: KeyboardEvent) {
      const sheet = sheetRef.current
      if (!sheet) return
      const focusable = Array.from(sheet.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((element) => !element.closest('[inert]'))
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (!first || !last) return
      const active = document.activeElement
      if (event.shiftKey ? active === first || !sheet.contains(active) : active === last || !sheet.contains(active)) {
        event.preventDefault()
        ;(event.shiftKey ? last : first).focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      returnFocus.current?.focus?.()
    }
  }, [open])

  return (
    <>
      <button
        type="button"
        className="rv-ws-scrim"
        data-open={open ? '1' : undefined}
        aria-label="Close panel"
        tabIndex={-1}
        onClick={onClose}
      />
      <aside
        ref={sheetRef}
        className="rv-ws-sheet"
        data-open={open ? '1' : undefined}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        aria-hidden={!open}
      >
        <div className="rv-ws-sheet-h">
          <span>{title}</span>
          <button type="button" ref={closeRef} className="rv-iconbtn" aria-label="Close" title="Close panel" onClick={onClose}>
            <CloseIcon />
          </button>
        </div>
        <div className="rv-ws-sheet-b">{open ? children : null}</div>
        <div className="rv-ws-sheet-f">
          {open ? actions : null}
          <button type="button" className="rv-btn" onClick={onClose}>
            Close
          </button>
        </div>
      </aside>
    </>
  )
}
