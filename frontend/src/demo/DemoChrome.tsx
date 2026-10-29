import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { createPortal } from 'react-dom'
import { router } from '../app/router'
import { guideFor, type GuideStep } from './guide'
import { subscribeDemoRoute } from './route'
import {
  getDemoState,
  resetDemo,
  setGuideHidden,
  subscribeDemo,
  type DemoState,
} from './state'
import { getDemoIds, getDemoPreparedFilename } from './transport'

const BLOCKED_EVENT = 'rivell-demo-blocked'
const DOWNLOAD_EVENT = 'rivell-demo-download'
const FINALIZED_EVENT = 'rivell-demo-finalized'
const XLSX_MEDIA_TYPE =
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
const PREPARED_FILE_ERROR = 'The prepared playbook could not be loaded.'
const DOWNLOAD_NOTICE =
  'This redline was prepared in advance. It does not include changes you make in the demo.'

function appHref(path: string): string {
  const base = import.meta.env.BASE_URL.replace(/\/$/, '')
  return `${base}${path === '/' ? '/' : path}`
}

function useDemoState(): DemoState {
  return useSyncExternalStore(subscribeDemo, getDemoState, getDemoState)
}

function useRoute(): string {
  return useSyncExternalStore(
    subscribeDemoRoute,
    () => `${window.location.pathname}${window.location.search}`,
    () => '/',
  )
}

function useAddDialog(open: boolean, route: string): HTMLElement | null {
  const [dialog, setDialog] = useState<HTMLElement | null>(null)
  useEffect(() => {
    if (!open) return
    let alive = true
    const find = () => {
      if (alive) setDialog(document.querySelector<HTMLElement>('dialog.rv-dlg[open]'))
    }
    const frame = window.requestAnimationFrame(find)
    const observer = new MutationObserver(find)
    observer.observe(document.body, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ['open'],
    })
    return () => {
      alive = false
      window.cancelAnimationFrame(frame)
      observer.disconnect()
    }
  }, [open, route])
  return open ? dialog : null
}

async function setPreparedFile(onProblem: (message: string) => void): Promise<void> {
  const filename = getDemoPreparedFilename()
  try {
    const response = await fetch(
      `${import.meta.env.BASE_URL}demo-data/files/${encodeURIComponent(filename)}`)
    if (!response.ok) {
      onProblem(PREPARED_FILE_ERROR)
      return
    }
    const blob = await response.blob()
    const file = new File([blob], filename, {
      type: blob.type || XLSX_MEDIA_TYPE,
      lastModified: Date.now(),
    })
    const input = document.querySelector<HTMLInputElement>(
      'dialog.rv-dlg[open] input[type="file"], input[type="file"][aria-label="Source file"]')
    if (!input) {
      onProblem(PREPARED_FILE_ERROR)
      return
    }
    const transfer = new DataTransfer()
    transfer.items.add(file)
    input.files = transfer.files
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
    // The prepared file is a playbook. Choosing that kind here keeps the dialog ready even when
    // the visitor opened it from the top bar rather than the guide's preset link.
    const labels = Array.from(document.querySelectorAll('dialog.rv-dlg[open] label'))
      .filter((node): node is HTMLLabelElement => node instanceof HTMLLabelElement)
    const playbook = labels.find((label) => {
      const choice = label.querySelector('input[type="radio"]')
      return choice !== null && label.textContent.trim().startsWith('Playbook')
    })
    const radio = playbook?.querySelector('input[type="radio"]') as HTMLInputElement | null
    if (radio && !radio.checked) playbook?.click()
  } catch {
    onProblem(PREPARED_FILE_ERROR)
  }
}

function Banner() {
  return (
    <div className="rv-demo-bar" role="region" aria-label="Demo mode">
      <p>Demo mode. Your changes stay in this browser and are not saved.</p>
      <button
        type="button"
        className="rv-demo-bar__restart"
        onClick={() => {
          const confirmed = window.confirm(
            'Restart the demo? Decisions and knowledge changes made in this browser will be cleared.')
          if (!confirmed) return
          resetDemo()
          window.location.assign(import.meta.env.BASE_URL)
        }}
      >
        Restart the demo
      </button>
    </div>
  )
}

function GuideCard({
  step,
  hidden,
  inDialog,
  onHide,
  onPreparedFile,
  busy,
}: {
  step: GuideStep
  hidden: boolean
  inDialog: boolean
  onHide: () => void
  onPreparedFile: () => void
  busy: boolean
}) {
  if (hidden) {
    return (
      <button
        type="button"
        className={`rv-demo-guide__open${inDialog ? ' rv-demo-guide__open--dialog' : ''}`}
        onClick={() => setGuideHidden(false)}
        aria-label="Show the guide"
      >
        Guide
      </button>
    )
  }
  return (
    <section
      className={`rv-demo-guide${inDialog ? ' rv-demo-guide--dialog' : ''}`}
      aria-label="Demo guide"
    >
      <div className="rv-demo-guide__head">
        <h2>{step.title}</h2>
        <button type="button" className="rv-demo-guide__hide" onClick={onHide}>
          Hide guide
        </button>
      </div>
      <p>{step.text}</p>
      {step.note ? <p className="rv-demo-guide__note">{step.note}</p> : null}
      {step.action === 'prepared-file' ? (
        <button
          type="button"
          className="rv-btn rv-btn--primary rv-btn--compact"
          onClick={onPreparedFile}
          disabled={busy}
        >
          Use the prepared NDA playbook
        </button>
      ) : step.link ? (
        <a
          className="rv-btn rv-btn--compact"
          href={appHref(step.link.to)}
          onClick={(event) => {
            if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return
            event.preventDefault()
            void router.navigate(step.link!.to)
          }}
        >
          {step.link.label}
        </a>
      ) : null}
    </section>
  )
}

export function DemoChrome() {
  const demo = useDemoState()
  const route = useRoute()
  const [pathname, search] = useMemo(() => {
    const queryAt = route.indexOf('?')
    const raw = queryAt === -1 ? route : route.slice(0, queryAt)
    const base = import.meta.env.BASE_URL.replace(/\/$/, '')
    const path = base && raw.startsWith(base) ? raw.slice(base.length) || '/' : raw
    return [path, queryAt === -1 ? '' : route.slice(queryAt)]
  }, [route])
  const [toast, setToast] = useState<string | null>(null)
  const [download, setDownload] = useState<string | null>(null)
  const [preparedBusy, setPreparedBusy] = useState(false)
  const toastTimer = useRef<number | null>(null)
  const downloadTimer = useRef<number | null>(null)
  const addOpen = new URLSearchParams(search).get('add') !== null
  const dialogHost = useAddDialog(addOpen, route)
  const ids = getDemoIds()
  const step = guideFor({ pathname, search, stage: demo.stage, ids })
  const workspace = pathname.includes('/analysis/')
  const compilation = /^\/knowledge\/compilations\//.test(pathname)

  useEffect(() => {
    const root = document.documentElement
    if (workspace) root.dataset.demoWorkspace = 'true'
    else delete root.dataset.demoWorkspace
    if (compilation) root.dataset.demoCompilation = 'true'
    else delete root.dataset.demoCompilation
    return () => {
      delete root.dataset.demoWorkspace
      delete root.dataset.demoCompilation
    }
  }, [workspace, compilation])

  const showToast = useCallback((message: string) => {
    setToast(message)
    if (toastTimer.current !== null) window.clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToast(null), 4000)
  }, [])

  useEffect(() => {
    const onBlocked = (event: Event) => {
      const detail = (event as CustomEvent<{ message?: string }>).detail
      showToast(detail?.message || 'Not available in the demo.')
    }
    const onDownload = () => {
      setDownload(DOWNLOAD_NOTICE)
      if (downloadTimer.current !== null) window.clearTimeout(downloadTimer.current)
      downloadTimer.current = window.setTimeout(() => setDownload(null), 6000)
    }
    // The guide carries the prepared-result sentence, but a visitor can hide it. The finalized
    // event makes the same sentence visible even then, on any route.
    const onFinalized = () => {
      // When the guide is visible it carries the same sentence. The notice is the fallback for
      // the visitor who collapsed it, so they still learn the result was prepared in advance.
      if (!getDemoState().guideHidden) return
      setDownload(DOWNLOAD_NOTICE)
      if (downloadTimer.current !== null) window.clearTimeout(downloadTimer.current)
      downloadTimer.current = window.setTimeout(() => setDownload(null), 8000)
    }
    window.addEventListener(BLOCKED_EVENT, onBlocked)
    window.addEventListener(DOWNLOAD_EVENT, onDownload)
    window.addEventListener(FINALIZED_EVENT, onFinalized)
    return () => {
      window.removeEventListener(BLOCKED_EVENT, onBlocked)
      window.removeEventListener(DOWNLOAD_EVENT, onDownload)
      window.removeEventListener(FINALIZED_EVENT, onFinalized)
      if (toastTimer.current !== null) window.clearTimeout(toastTimer.current)
      if (downloadTimer.current !== null) window.clearTimeout(downloadTimer.current)
    }
  }, [showToast])

  // The add dialog is modal and sits in the browser's top layer. A guide card outside it would be
  // unreachable, so on that step the card (or its collapsed button) is portalled into the dialog.
  const guide = (
    <GuideCard
      step={step}
      hidden={demo.guideHidden}
      inDialog={Boolean(dialogHost)}
      busy={preparedBusy}
      onHide={() => setGuideHidden(true)}
      onPreparedFile={() => {
        if (preparedBusy) return
        setPreparedBusy(true)
        void setPreparedFile(showToast).finally(() => setPreparedBusy(false))
      }}
    />
  )

  const toastNode = toast ? (
    <div className="rv-demo-toast" role="status">
      {toast}
    </div>
  ) : null

  return (
    <>
      <Banner />
      {download ? (
        <div className="rv-demo-download" role="status">
          {download}
        </div>
      ) : null}
      {dialogHost
        ? createPortal(<>{guide}{toastNode}</>, dialogHost)
        : <>{guide}{toastNode}</>}
    </>
  )
}

