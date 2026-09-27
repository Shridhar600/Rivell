import { describe, expect, it } from 'vitest'
import { parseReviewDiagnostics } from '../api/diagnostics'
import type { DiagnosticsCheck, DiagnosticsRun, ReviewDiagnostics } from '../api/diagnostics'
import {
  diagnosticsBackgroundWorkerStatement,
  diagnosticsCheckState,
  diagnosticsCheckSummary,
  diagnosticsCountRows,
  diagnosticsEnvironmentStatement,
  diagnosticsFinalizationStatement,
  diagnosticsRedlineStatement,
  diagnosticsReviewFailureStatement,
  diagnosticsRunDuration,
  diagnosticsRunState,
  diagnosticsStallLines,
} from './diagnostics'

const RAW_INTERNAL =
  'IllegalStateException:Review snapshot missing@com.rivell.workbench.review.ReviewService.require:81'

function check(overrides: Partial<DiagnosticsCheck> = {}): DiagnosticsCheck {
  return {
    key: 'PRESENCE:CONFIDENTIALITY',
    status: 'COMPLETED',
    signalCount: 1,
    degradedReason: 'NONE',
    startedAt: '2026-09-27T09:00:00Z',
    completedAt: '2026-09-27T09:00:30Z',
    durationMillis: 30000,
    ...overrides,
  }
}

function run(overrides: Partial<DiagnosticsRun> = {}): DiagnosticsRun {
  return {
    runId: 'run-1',
    runNumber: 1,
    status: 'COMPLETED',
    trigger: 'INITIAL',
    pipelineVersion: 'rivell-review-1',
    startedAt: '2026-09-27T09:00:00Z',
    completedAt: '2026-09-27T09:02:00Z',
    runDurationSeconds: 120,
    failureDetail: 'NONE',
    semanticsMatchFrozenSnapshot: true,
    checks: [check()],
    ...overrides,
  }
}

function toWireRun(value: DiagnosticsRun): Record<string, unknown> {
  return {
    runId: value.runId,
    runNumber: value.runNumber,
    status: value.status,
    trigger: value.trigger,
    pipelineVersion: value.pipelineVersion,
    startedAt: value.startedAt,
    completedAt: value.completedAt,
    ageSeconds: value.runDurationSeconds,
    failureReason: value.failureDetail === 'RECORDED' ? RAW_INTERNAL : null,
    environmentMatchesSnapshot: value.semanticsMatchFrozenSnapshot,
    checks: value.checks.map((entry) => ({
      key: entry.key,
      status: entry.status,
      signalCount: entry.signalCount,
      degradedReason: entry.degradedReason === 'RECORDED' ? RAW_INTERNAL : null,
      startedAt: entry.startedAt,
      completedAt: entry.completedAt,
      durationMillis: entry.durationMillis,
    })),
  }
}

function diagnostics(wireOverrides: Record<string, unknown> = {}): ReviewDiagnostics {
  return parseReviewDiagnostics({
    reviewId: 'r-1',
    contractId: 'c-1',
    contractVersionNumber: 1,
    reviewStatus: 'COMPLETED',
    backgroundWorkerEnabled: true,
    analysis: toWireRun(run()),
    findings: { total: 2, blocking: 1, reviewRequired: 1, informational: 0 },
    decisions: { accepted: 1, edited: 0, rejected: 1, escalated: 0 },
    finalization: { finalized: true, changeSetId: 'cs-1', finalizedAt: null, approvedChanges: 2 },
    redline: {
      status: 'COMPLETED',
      outcome: 'REDLINE_GENERATED',
      failureCode: null,
      changeCount: 2,
      downloadAvailable: true,
      ageSeconds: 10,
    },
    stalled: [],
    ...wireOverrides,
  })
}

describe('diagnostics state labels', () => {
  it('labels run and check states with an honest tone', () => {
    expect(diagnosticsRunState(run()).tone).toBe('positive')
    expect(diagnosticsRunState(run({ status: 'FAILED' })).tone).toBe('critical')
    expect(diagnosticsRunState(run({ status: 'UNKNOWN' })).tone).toBe('unknown')
    expect(diagnosticsRunState(null).label).toMatch(/no analysis run/i)

    expect(diagnosticsCheckState(check({ status: 'DEGRADED' })).tone).toBe('caution')
    expect(diagnosticsCheckState(check({ status: 'SKIPPED' })).label).toBe('Skipped')
    expect(diagnosticsCheckState(check({ status: 'UNKNOWN' })).tone).toBe('unknown')
  })

  it('summarises check outcomes by count', () => {
    const summary = diagnosticsCheckSummary(
      run({
        checks: [
          check({ status: 'COMPLETED' }),
          check({ status: 'COMPLETED' }),
          check({ status: 'DEGRADED' }),
          check({ status: 'FAILED' }),
        ],
      }),
    )

    expect(summary).toBe('4 planned checks: 2 completed, 1 degraded, 1 failed.')
  })

  it('phrases the run duration only when the run has completed', () => {
    expect(diagnosticsRunDuration(run({ runDurationSeconds: 120 }))).toBe('The run took 2 minutes.')
    expect(diagnosticsRunDuration(run({ runDurationSeconds: 45 }))).toBe('The run took 45 seconds.')
    expect(diagnosticsRunDuration(run({ completedAt: null, runDurationSeconds: 0 }))).toBeNull()
    expect(diagnosticsRunDuration(null)).toBeNull()
  })
})

describe('diagnostics statements', () => {
  it('describes finalization from the frozen change set', () => {
    expect(diagnosticsFinalizationStatement(diagnostics())).toMatch(/finalized with 2 approved changes/)
    expect(
      diagnosticsFinalizationStatement(
        diagnostics({
          finalization: { finalized: false, changeSetId: null, finalizedAt: null, approvedChanges: 0 },
        }),
      ),
    ).toMatch(/not been finalized/)
  })

  it('reuses the redline wording and never invents an artifact', () => {
    expect(diagnosticsRedlineStatement(diagnostics())).toMatch(/can be downloaded/)
    expect(
      diagnosticsRedlineStatement(
        diagnostics({
          redline: {
            status: 'COMPLETED',
            outcome: 'REDLINE_GENERATED',
            failureCode: null,
            changeCount: 2,
            downloadAvailable: false,
            ageSeconds: 10,
          },
        }),
      ),
    ).toMatch(/no document is available/)
    expect(
      diagnosticsRedlineStatement(
        diagnostics({
          redline: {
            status: 'FAILED',
            outcome: null,
            failureCode: 'APPROVED_SEMANTICS_DRIFT',
            changeCount: 1,
            downloadAvailable: false,
            ageSeconds: 10,
          },
        }),
      ),
    ).toMatch(/semantics changed after/i)
    expect(
      diagnosticsRedlineStatement(
        diagnostics({
          redline: {
            status: 'COMPLETED',
            outcome: 'NO_CHANGES',
            failureCode: null,
            changeCount: 0,
            downloadAvailable: false,
            ageSeconds: 10,
          },
        }),
      ),
    ).toMatch(/without producing document changes/)
    expect(diagnosticsRedlineStatement(diagnostics({ redline: null }))).toMatch(/no redline execution/i)
  })

  it('states what the environment comparison actually covers', () => {
    expect(diagnosticsEnvironmentStatement(diagnostics())).toBe(
      'The contract semantics still match the snapshot this review was created against.',
    )
    expect(
      diagnosticsEnvironmentStatement(
        diagnostics({ analysis: toWireRun(run({ semanticsMatchFrozenSnapshot: false })) }),
      ),
    ).toMatch(/no longer match/)
    expect(
      diagnosticsEnvironmentStatement(
        diagnostics({ analysis: toWireRun(run({ semanticsMatchFrozenSnapshot: null })) }),
      ),
    ).toMatch(/could not determine/)
    expect(diagnosticsEnvironmentStatement(diagnostics({ analysis: null }))).toBeNull()
    for (const statement of [
      diagnosticsEnvironmentStatement(diagnostics()),
      diagnosticsEnvironmentStatement(diagnostics({ analysis: toWireRun(run({ semanticsMatchFrozenSnapshot: null })) })),
    ]) {
      expect(statement).not.toMatch(/model|embedding|environment matches/i)
    }
  })

  it('reports the worker state because it is the only worker signal in the api', () => {
    expect(diagnosticsBackgroundWorkerStatement(diagnostics())).toMatch(/worker is running/)
    expect(
      diagnosticsBackgroundWorkerStatement(diagnostics({ backgroundWorkerEnabled: false })),
    ).toMatch(/not running/)
    expect(
      diagnosticsBackgroundWorkerStatement(diagnostics({ backgroundWorkerEnabled: null })),
    ).toMatch(/could not determine/)
  })

  it('admits a recorded review failure without exposing it', () => {
    expect(diagnosticsReviewFailureStatement(diagnostics())).toBeNull()
    const withFailure = diagnostics({
      reviewStatus: 'FAILED',
      reviewFailure: RAW_INTERNAL,
    })

    expect(diagnosticsReviewFailureStatement(withFailure)).toMatch(/internal detail/)
    expect(diagnosticsReviewFailureStatement(withFailure)).not.toMatch(/IllegalState|com\.rivell/)
  })

  it('lists counts without qualifying them with the run they came from', () => {
    const rows = diagnosticsCountRows(diagnostics())

    expect(rows.map((row) => row.label)).toContain('Findings recorded')
    expect(rows.find((row) => row.label === 'Findings recorded')?.value).toBe('2')
    for (const row of rows) expect(row.value).not.toMatch(/run|latest|completed/i)
  })

  it('passes the server stall reasons through unchanged', () => {
    expect(diagnosticsStallLines(diagnostics({ stalled: ['No analysis run has been created.'] }))).toEqual([
      'No analysis run has been created.',
    ])
  })

  it('cannot surface a raw internal string through any exported statement', () => {
    const withInternals = parseReviewDiagnostics({
      reviewId: 'r-1',
      contractId: 'c-1',
      contractVersionNumber: 1,
      reviewStatus: 'FAILED',
      reviewFailure: RAW_INTERNAL,
      backgroundWorkerEnabled: false,
      analysis: toWireRun(run({ failureDetail: 'RECORDED', checks: [check({ degradedReason: 'RECORDED' })] })),
    })
    const rendered = [
      diagnosticsRunState(withInternals.analysis).label,
      diagnosticsCheckSummary(withInternals.analysis as DiagnosticsRun),
      diagnosticsEnvironmentStatement(withInternals),
      diagnosticsBackgroundWorkerStatement(withInternals),
      diagnosticsReviewFailureStatement(withInternals),
      diagnosticsRedlineStatement(withInternals),
      diagnosticsFinalizationStatement(withInternals),
      ...diagnosticsStallLines(withInternals),
    ].join(' ')

    expect(rendered).not.toMatch(/IllegalState|com\.rivell|require:81/)
  })
})
