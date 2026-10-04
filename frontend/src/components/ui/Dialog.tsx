import { useEffect, useRef, type ReactNode } from 'react'

interface DialogProps {
  /** Id of the heading that names the dialog. */
  labelledBy: string
  /** Called for Escape and for a click on the backdrop. The dialog never closes itself. */
  onClose: () => void
  /** 'sheet' is the side drawer. */
  variant?: 'dialog' | 'sheet'
  className?: string
  children: ReactNode
}

/**
 * A modal built on the native dialog element, so focus stays inside, the page behind it is inert,
 * Escape works, and focus returns to the control that opened it. The caller owns whether it is
 * mounted. A caller that must not be dismissed mid-request simply ignores `onClose`.
 */
export function Dialog({ labelledBy, onClose, variant = 'dialog', className, children }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const close = useRef(onClose)
  useEffect(() => {
    close.current = onClose
  })

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (!dialog.open && typeof dialog.showModal === 'function') dialog.showModal()
    const onCancel = (event: Event) => {
      event.preventDefault()
      close.current()
    }
    // A click that lands on the dialog element itself, not on anything inside it, is a click on its backdrop.
    const onBackdrop = (event: MouseEvent) => {
      if (event.target === dialog) close.current()
    }
    dialog.addEventListener('cancel', onCancel)
    dialog.addEventListener('click', onBackdrop)
    return () => {
      dialog.removeEventListener('cancel', onCancel)
      dialog.removeEventListener('click', onBackdrop)
      if (dialog.open && typeof dialog.close === 'function') dialog.close()
    }
  }, [])

  const classes = ['rv-dlg', variant === 'sheet' && 'rv-dlg--sheet', className].filter(Boolean).join(' ')
  return (
    <dialog ref={ref} className={classes} aria-labelledby={labelledBy}>
      {children}
    </dialog>
  )
}
