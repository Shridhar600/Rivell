import { useEffect, useRef, type ReactNode } from 'react'
import { CloseIcon } from './WorkspaceIcons'

interface Props {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
}

/** The canonical right-hand sheet: evidence, decision history and review notes open here. */
export function WorkspaceSheet({ open, title, onClose, children }: Props) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const returnFocus = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (!open) return
    returnFocus.current = document.activeElement as HTMLElement | null
    closeRef.current?.focus()
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== 'Escape') return
      event.preventDefault()
      onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      returnFocus.current?.focus?.()
    }
  }, [open, onClose])

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
        className="rv-ws-sheet"
        data-open={open ? '1' : undefined}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        aria-hidden={!open}
      >
        <div className="rv-ws-sheet-h">
          <span>{title}</span>
          <button type="button" ref={closeRef} className="rv-iconbtn" aria-label="Close" onClick={onClose}>
            <CloseIcon />
          </button>
        </div>
        <div className="rv-ws-sheet-b">{open ? children : null}</div>
        <div className="rv-ws-sheet-f">
          <button type="button" className="rv-btn" onClick={onClose}>
            Close
          </button>
        </div>
      </aside>
    </>
  )
}
