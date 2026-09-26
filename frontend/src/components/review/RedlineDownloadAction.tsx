import { useState } from 'react'
import type { RedlineExecution } from '../../api/redline'
import {
  downloadRedline,
  isRedlineForbiddenError,
} from '../../api/redline'

function saveFile(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

export function RedlineDownloadAction({
  reviewId,
  execution,
}: {
  reviewId: string
  execution: RedlineExecution
}) {
  const [isDownloading, setDownloading] = useState(false)
  const [downloadError, setDownloadError] = useState<string | null>(null)
  const canDownload =
    execution.downloadAvailable &&
    execution.status === 'COMPLETED' &&
    execution.outcome === 'REDLINE_GENERATED'
  if (!canDownload) return null

  async function onDownload() {
    setDownloading(true)
    setDownloadError(null)
    try {
      const download = await downloadRedline(reviewId)
      saveFile(download.blob, execution.artifactFilename ?? download.filename)
    } catch (error) {
      setDownloadError(
        isRedlineForbiddenError(error)
          ? 'You do not have permission to download this redline.'
          : 'The redline was generated, but the file could not be downloaded. Try again.',
      )
    } finally {
      setDownloading(false)
    }
  }

  return (
    <div className="redline__download">
      <button
        type="button"
        className="button"
        disabled={isDownloading}
        onClick={() => void onDownload()}
      >
        Download redlined DOCX
      </button>
      {isDownloading ? <span role="status">Preparing download…</span> : null}
      {downloadError ? (
        <p className="state state--warning" role="alert">
          {downloadError}
        </p>
      ) : null}
    </div>
  )
}
