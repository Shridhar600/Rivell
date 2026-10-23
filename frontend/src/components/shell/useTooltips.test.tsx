import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import { useTooltips } from './useTooltips'

function Harness() {
  useTooltips()
  return (
    <button type="button" title="Hide sidebar [">
      <span>Toggle</span>
    </button>
  )
}

function pointer(type: string, target: Element, related: Element | null = null) {
  const event = new MouseEvent(type, { bubbles: true, relatedTarget: related })
  Object.defineProperty(event, 'pointerType', { value: 'mouse' })
  target.dispatchEvent(event)
}

afterEach(() => vi.useRealTimers())

describe('app tooltip', () => {
  it('shows the title after a short hover, with the key hint apart, and gives the title back on leave', () => {
    vi.useFakeTimers()
    render(<Harness />)
    const button = screen.getByRole('button')
    const tip = document.querySelector('.rv-tip') as HTMLElement

    pointer('pointerover', button.firstElementChild!)
    expect(tip).not.toHaveAttribute('data-open')
    act(() => vi.advanceTimersByTime(500))
    expect(tip).toHaveAttribute('data-open')
    expect(tip.firstChild?.textContent).toBe('Hide sidebar')
    expect(tip.querySelector('kbd')).toHaveTextContent('[')
    // The browser's own tooltip is held back only while ours shows.
    expect(button).not.toHaveAttribute('title')

    pointer('pointerout', button, document.body)
    expect(tip).not.toHaveAttribute('data-open')
    expect(button).toHaveAttribute('title', 'Hide sidebar [')
  })

  it('keeps a title React wrote while the tip showed, instead of restoring the old one', () => {
    vi.useFakeTimers()
    render(<Harness />)
    const button = screen.getByRole('button')
    pointer('pointerover', button)
    act(() => vi.advanceTimersByTime(500))
    button.setAttribute('title', 'Show sidebar [')
    pointer('pointerout', button, document.body)
    expect(button).toHaveAttribute('title', 'Show sidebar [')
  })

  it('never shows for a touch, and hides on a click', () => {
    vi.useFakeTimers()
    render(<Harness />)
    const button = screen.getByRole('button')
    const tip = document.querySelector('.rv-tip') as HTMLElement
    const touch = new MouseEvent('pointerover', { bubbles: true })
    Object.defineProperty(touch, 'pointerType', { value: 'touch' })
    button.dispatchEvent(touch)
    act(() => vi.advanceTimersByTime(500))
    expect(tip).not.toHaveAttribute('data-open')

    pointer('pointerover', button)
    act(() => vi.advanceTimersByTime(500))
    button.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }))
    expect(tip).not.toHaveAttribute('data-open')
    expect(button).toHaveAttribute('title', 'Hide sidebar [')
  })
})
