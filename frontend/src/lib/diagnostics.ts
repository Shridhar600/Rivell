import type {
  DiagnosticsCheck,
  DiagnosticsCheckStatus,
  DiagnosticsRun,
  ReviewDiagnostics,
} from '../api/diagnostics'
import { redlineFailureTitle } from './redline'

/**
 * This module maps server states to labels and factual statements. It deliberately returns no
 * severity, colour or emphasis signal: how a state should read visually is a product-design
 * decision, and an unmapped tone invented here would constrain it.
 */

const CHECK_LABELS: Record<DiagnosticsCheckStatus, string> = {
  PENDING: 'Not started',
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  DEGRADED: 'Degraded',
  FAILED: 'Failed',
  SKIPPED: 'Skipped',
  UNKNOWN: 'State not recognised',
}

const RUN_LABELS: Record<DiagnosticsRun['status'], string> = {
  RUNNING: 'Analysis run in progress',
  COMPLETED: 'Analysis run completed',
  FAILED: 'Analysis run failed',
  UNKNOWN: 'Analysis run state not recognised',
}

export function diagnosticsRunStateLabel(run: DiagnosticsRun | null): string {
  if (run === null) return 'No analysis run has been created for this review.'
  return RUN_LABELS[run.status]
}

export function diagnosticsCheckStateLabel(check: DiagnosticsCheck): string {
  return CHECK_LABELS[check.status]
}

export function diagnosticsCheckSummary(run: DiagnosticsRun): string {
  const counts = new Map<DiagnosticsCheckStatus, number>()
  for (const check of run.checks) {
    counts.set(check.status, (counts.get(check.status) ?? 0) + 1)
  }
  const parts: string[] = []
  for (const status of ['COMPLETED', 'DEGRADED', 'FAILED', 'SKIPPED', 'PENDING', 'RUNNING'] as const) {
    const count = counts.get(status) ?? 0
    if (count === 0) continue
    parts.push(`${count} ${CHECK_LABELS[status].toLowerCase()}`)
  }
  if (parts.length === 0) return 'No planned checks have been recorded for this run.'
  return `${run.checks.length} planned ${run.checks.length === 1 ? 'check' : 'checks'}: ${parts.join(', ')}.`
}

export function diagnosticsRunDuration(run: DiagnosticsRun | null): string | null {
  if (run === null || run.completedAt === null) return null
  const seconds = run.runDurationSeconds
  if (seconds === null) return null
  if (seconds < 60) return `The run took ${seconds} seconds.`
  if (seconds < 3600) return `The run took ${Math.round(seconds / 60)} minutes.`
  return `The run took ${Math.round(seconds / 3600)} hours.`
}

export function diagnosticsCountRows(
  diagnostics: ReviewDiagnostics,
): { label: string; value: string }[] {
  return [
    { label: 'Findings recorded', value: String(diagnostics.findings.total) },
    { label: 'Blocking', value: String(diagnostics.findings.blocking) },
    { label: 'Requiring review', value: String(diagnostics.findings.reviewRequired) },
    // The server counts findings and decisions from the latest completed run, which is not
    // necessarily the run reported above, so a single note covers both blocks.
    { label: 'Accepted decisions', value: String(diagnostics.decisions.accepted) },
    { label: 'Edited decisions', value: String(diagnostics.decisions.edited) },
    { label: 'Rejected decisions', value: String(diagnostics.decisions.rejected) },
    { label: 'Escalated decisions', value: String(diagnostics.decisions.escalated) },
  ]
}

export function diagnosticsFinalizationStatement(diagnostics: ReviewDiagnostics): string {
  const { finalization } = diagnostics
  if (!finalization.finalized) {
    return 'This review has not been finalized, so no approved change set has been frozen.'
  }
  const changes = finalization.approvedChanges
  return `This review was finalized with ${changes} approved ${changes === 1 ? 'change' : 'changes'}.`
}

export function diagnosticsRedlineStatement(diagnostics: ReviewDiagnostics): string {
  const redline = diagnostics.redline
  if (redline === null) return 'No redline execution has been recorded for this review.'
  if (redline.status === 'FAILED') {
    const reason = redline.failureCode === null ? null : redlineFailureTitle(redline.failureCode)
    return reason ?? 'The recorded redline execution failed.'
  }
  if (redline.outcome === 'NO_CHANGES') {
    return 'The redline execution completed without producing document changes.'
  }
  if (redline.outcome === 'REDLINE_GENERATED') {
    return redline.downloadAvailable
      ? `A redline was produced for ${redline.changeCount} approved ${redline.changeCount === 1 ? 'change' : 'changes'} and can be downloaded.`
      : `A redline execution was recorded for ${redline.changeCount} approved ${redline.changeCount === 1 ? 'change' : 'changes'}, but no document is available.`
  }
  return 'The redline execution state is not recognised.'
}

/**
 * The server builds every stall reason from a fixed literal plus an integer, so the text is
 * passed through unchanged. If a future backend change puts an exception string here, this
 * function is the place that has to notice.
 */
export function diagnosticsStallLines(diagnostics: ReviewDiagnostics): string[] {
  return diagnostics.stalled
}

export function diagnosticsEnvironmentStatement(diagnostics: ReviewDiagnostics): string | null {
  const run = diagnostics.analysis
  if (run === null) return null
  if (run.semanticsMatchFrozenSnapshot === null) {
    return 'Rivell could not determine whether this review’s contract semantics still match the snapshot it was created against.'
  }
  return run.semanticsMatchFrozenSnapshot
    ? 'The contract semantics still match the snapshot this review was created against.'
    : 'The contract semantics no longer match the snapshot this review was created against.'
}

export function diagnosticsBackgroundWorkerStatement(diagnostics: ReviewDiagnostics): string {
  return diagnostics.backgroundWorkerEnabled
    ? 'The background analysis worker is running.'
    : 'The background analysis worker is not running, so queued analysis is not being picked up.'
}

export function diagnosticsReviewFailureStatement(diagnostics: ReviewDiagnostics): string | null {
  if (diagnostics.reviewFailureDetail === 'NONE') return null
  return 'Rivell recorded a failure reason for this review. It is not shown here because it contains internal detail.'
}
