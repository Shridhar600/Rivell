import type { ParseStatus } from '../api/contracts'

const WARNING_LABELS: Record<string, string> = {
  SOME_BODY_CONTENT_COULD_NOT_BE_REPRESENTED:
    'Some document content could not be represented in the structural view.',
  HEADERS_OR_FOOTERS_NOT_REPRESENTED:
    'Headers or footers contain content that is not represented.',
  FOOTNOTES_NOT_REPRESENTED:
    'Footnotes contain content that is not represented.',
  UNSUPPORTED_NESTED_TABLE:
    'A nested table could not be fully represented.',
}

function warningLabel(warning: string): string {
  return WARNING_LABELS[warning] ?? warning
}

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
