import type { KeyboardEvent, PointerEvent } from 'react'
import { clampWidth, type WidthLimits } from '../../lib/panelWidth'

const KEY_STEP = 16

interface Props {
  className: string
  label: string
  /** The panel edge the handle sits on. Dragging a right edge rightwards widens; a left edge, leftwards. */
  edge: 'left' | 'right'
  width: number
  limits: WidthLimits
  onResize: (width: number) => void
  onResizing?: (resizing: boolean) => void
}

/** One splitter for every resizable panel: drag, arrow keys, Home and End, double-click to reset. */
export function PanelResizeHandle({ className, label, edge, width, limits, onResize, onResizing }: Props) {
  const sign = edge === 'right' ? 1 : -1

  function onPointerDown(event: PointerEvent<HTMLDivElement>) {
    if (event.button !== 0) return
    event.preventDefault()
    const handle = event.currentTarget
    const startX = event.clientX
    handle.setPointerCapture?.(event.pointerId)
    onResizing?.(true)
    document.documentElement.dataset.resizing = ''
    const move = (moveEvent: globalThis.PointerEvent) => onResize(clampWidth(width + sign * (moveEvent.clientX - startX), limits))
    const stop = () => {
      onResizing?.(false)
      delete document.documentElement.dataset.resizing
      handle.removeEventListener('pointermove', move)
      handle.removeEventListener('pointerup', stop)
      handle.removeEventListener('pointercancel', stop)
    }
    handle.addEventListener('pointermove', move)
    handle.addEventListener('pointerup', stop)
    handle.addEventListener('pointercancel', stop)
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const next =
      event.key === 'ArrowLeft' ? width - sign * KEY_STEP
      : event.key === 'ArrowRight' ? width + sign * KEY_STEP
      : event.key === 'Home' ? limits.min
      : event.key === 'End' ? limits.max
      : null
    if (next === null) return
    event.preventDefault()
    onResize(clampWidth(next, limits))
  }

  return (
    // A focusable separator is the ARIA pattern for a resizable splitter.
    <div
      className={`rv-resize ${className}`}
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuemin={limits.min}
      aria-valuemax={limits.max}
      aria-valuenow={width}
      tabIndex={0}
      title="Drag to resize. Double-click to reset."
      onPointerDown={onPointerDown}
      onKeyDown={onKeyDown}
      onDoubleClick={() => onResize(limits.initial)}
    />
  )
}
