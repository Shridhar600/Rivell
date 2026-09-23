import type { ParseStatus } from '../api/contracts'
import { warningLabel } from '../lib/warnings'

function WarningList({ warnings }: { warnings: string[] }) {
  return (
    <ul className="parse-notice__warnings">
      {warnings.map((warning) => (
        <li key={warning}>{warningLabel(warning)}</li>
      ))}
    </ul>
  )
}

export function ParseNotice({
  status,
  warnings,
}: {
  status: ParseStatus
  warnings: string[]
}) {
  if (status === 'FAILED') {
    return (
      <section
        className="state state--error parse-notice"
        role="alert"
        aria-labelledby="parse-notice-heading"
      >
        <h2 id="parse-notice-heading">Document parsing failed</h2>
        <p>The original file remains available to view or download.</p>
        {warnings.length > 0 ? <WarningList warnings={warnings} /> : null}
      </section>
    )
  }

  if (status === 'PARSING') {
    return (
      <p className="state" role="status">
        Parsing is still in progress for this document.
      </p>
    )
  }

  if (warnings.length === 0) return null

  return (
    <section
      className="state state--warning parse-notice"
      role="status"
      aria-labelledby="parse-notice-heading"
    >
      <h2 id="parse-notice-heading">Parsed with limitations</h2>
      <p>The structural view may not represent every part of the source document.</p>
      <WarningList warnings={warnings} />
    </section>
  )
}
