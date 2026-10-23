import { useEffect, useRef } from 'react'
import { Button } from '../ui/Button'

interface Props {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  isFinalizing: boolean
  error: string | null
  /** Lines of the summary. Counts come from the server's human-review state. */
  summary: string[]
  contractLabel: string
}

/**
 * The canonical Finalize dialog. It is only ever opened when the server says the review is ready,
 * and the server still has the last word: a refusal is shown here, not swallowed.
 */
export function FinalizeDialog({ open, onClose, onConfirm, isFinalizing, error, summary, contractLabel }: Props) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog ref={ref} className="rv-ws-dialog" aria-labelledby="rv-ws-fin-title" onClose={onClose}>
      <div className="rv-ws-dlg">
        <h2 id="rv-ws-fin-title">Finalize this review?</h2>
        <p>
          Your decisions become the approved changes for {contractLabel}. You can&apos;t edit them after this.
        </p>
        <ul>
          {summary.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
        <p>After you finalize, you generate the redline separately.</p>
        {error ? (
          <p className="rv-ws-error" role="alert">
            {error}
          </p>
        ) : null}
        <div className="rv-ws-dlg-r">
          <Button onClick={onClose}>Keep reviewing</Button>
          <Button variant="primary" disabled={isFinalizing} onClick={onConfirm}>
            {isFinalizing ? 'Finalizing…' : 'Finalize review'}
          </Button>
        </div>
      </div>
    </dialog>
  )
}
