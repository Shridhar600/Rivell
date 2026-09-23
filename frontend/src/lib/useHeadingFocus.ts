import { useEffect, useRef } from 'react'

export function useHeadingFocus<T extends HTMLElement>(ready: unknown) {
  const ref = useRef<T>(null)

  useEffect(() => {
    if (ready) ref.current?.focus()
  }, [ready])

  return ref
}
