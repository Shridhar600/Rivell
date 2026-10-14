import { describe, expect, it } from 'vitest'
import type { HumanDecision, HumanReview } from '../api/workflow'
import { humanizeCode } from './domainLabels'
import {
  acceptEligibility,
  blockerLabel,
  changeActionLabel,
  changeLanguageProvenanceLabel,
  changeSetCounts,
  changeTargetLabel,
  classifyDecisionConflict,
  decisionActionLabel,
  decisionConflictNotice,
  decisionStateLabel,
  defaultEditAction,
  editActionsForSubject,
  editLanguageRequired,
  humanReviewCountRows,
  humanStatusLabel,
  validateDecisionForm,
} from './workflow'

const decision = (overrides: Partial<HumanDecision> = {}): HumanDecision => ({
  id: 'd-1',
  findingId: 'f-1',
  revision: 1,
  action: 'ACCEPT',
  approvedAction: 'REPLACE',
  approvedLanguage: 'Approved text.',
  languageSource: 'APPROVED_DRAFTING',
  approvedDraftingEvidenceId: 'e-drafting',
  comment: null,
  actorId: 'u-1',
  decidedAt: '2026-09-26T09:00:00Z',
  clientRequestId: null,
  supersedesDecisionId: null,
  ...overrides,
})

const humanReview = (overrides: Partial<HumanReview> = {}): HumanReview => ({
  reviewId: 'r-1',
  status: 'IN_PROGRESS',
  analysisStatus: 'COMPLETED',
  actionableFindings: 2,
  resolvedFindings: 1,
  remainingFindings: 1,
  accepted: 1,
  edited: 0,
  rejected: 0,
  escalated: 0,
  informationalFindings: 0,
  informationalDecided: 0,
  readyToFinalize: false,
  blockers: [],
  changeSetId: null,
  finalizedBy: null,
  finalizedAt: null,
  ...overrides,
})

describe('workflow labels', () => {
  it('names the four human actions and current decision states in product language', () => {
    expect(decisionActionLabel('ACCEPT')).toBe('Accept recommendation')
    expect(decisionActionLabel('EDIT')).toBe('Edit change')
    expect(decisionActionLabel('REJECT')).toBe('Reject recommendation')
    expect(decisionActionLabel('ESCALATE')).toBe('Escalate for legal review')
    expect(decisionStateLabel(null)).toBe('No decision')
    expect(decisionStateLabel(decision({ action: 'ACCEPT' }))).toBe('Accepted')
    expect(decisionStateLabel(decision({ action: 'EDIT' }))).toBe('Edited')
    expect(decisionStateLabel(decision({ action: 'REJECT' }))).toBe('Rejected')
    expect(decisionStateLabel(decision({ action: 'ESCALATE' }))).toBe(
      'Escalated (unresolved)',
    )
  })

  it('names human review statuses distinctly from machine analysis', () => {
    expect(humanStatusLabel('NOT_STARTED')).toBe('Human review not started')
    expect(humanStatusLabel('IN_PROGRESS')).toBe('Human review in progress')
    expect(humanStatusLabel('READY_FOR_COMPLETION')).toBe('Ready to finalize')
    expect(humanStatusLabel('COMPLETED')).toBe('Human review completed')
    expect(humanStatusLabel('UNKNOWN')).toBe('Human review state unknown')
  })

  it('explains finalization blockers with safe product copy', () => {
    expect(blockerLabel('UNRESOLVED_BLOCKING_FINDINGS')).toContain('Blocking findings')
    expect(blockerLabel('ACTIVE_ESCALATION')).toContain('escalated')
    expect(blockerLabel('APPROVED_CHANGE_CONFLICT')).toContain('same contract location')
    expect(blockerLabel('ANALYSIS_DEGRADED')).toMatch(/produced no trustworthy result/)
    expect(blockerLabel('SOMETHING_NEW')).toBe('The review cannot be finalized yet.')
  })

  it('distinguishes change action types and language provenance', () => {
    expect(changeActionLabel('REPLACE')).toBe('Replace clause')
    expect(changeActionLabel('INSERT')).toBe('Insert provision')
    expect(changeActionLabel('DELETE')).toBe('Delete clause')
    expect(changeLanguageProvenanceLabel('APPROVED_DRAFTING')).toBe(
      'Approved organizational language',
    )
    expect(changeLanguageProvenanceLabel('GENERATIVE')).toBe(
      'AI-proposed, human-approved for this contract',
    )
    expect(changeLanguageProvenanceLabel('HUMAN_EDIT')).toBe('Human-authored')
    expect(changeLanguageProvenanceLabel('NONE')).toBe('No replacement text')
    expect(changeLanguageProvenanceLabel('UNKNOWN')).toBe('Language source unavailable')
  })
})

describe('accept eligibility', () => {
  it('allows ACCEPT only for executable recommendations with language', () => {
    expect(
      acceptEligibility({
        proposedAction: 'USE_APPROVED_FALLBACK',
        proposedLanguage: 'Approved text.',
      }),
    ).toEqual({ eligible: true })
    expect(
      acceptEligibility({
        proposedAction: 'CONSIDER_GENERATED_LANGUAGE',
        proposedLanguage: 'Generated text.',
      }),
    ).toEqual({ eligible: true })
  })

  it('refuses ACCEPT for non-executable recommendations and explains why', () => {
    for (const proposedAction of [
      'ADD_REQUIRED_PROVISION',
      'REVIEW_AMBIGUITY',
      'ESCALATE_TO_HUMAN',
      'NO_ACTION',
    ]) {
      const result = acceptEligibility({ proposedAction, proposedLanguage: null })
      expect(result.eligible).toBe(false)
      if (!result.eligible) {
        expect(result.reason).toContain('Edit')
        expect(result.reason).toContain('Reject')
      }
    }
  })

  it('refuses ACCEPT when an executable recommendation carries no language', () => {
    const result = acceptEligibility({
      proposedAction: 'USE_APPROVED_FALLBACK',
      proposedLanguage: null,
    })
    expect(result.eligible).toBe(false)
  })
})

describe('edit constraints', () => {
  it('maps subject kind to the allowed and default edit actions', () => {
    expect(editActionsForSubject('CLAUSE')).toEqual(['REPLACE', 'DELETE'])
    expect(editActionsForSubject('ABSENCE')).toEqual(['INSERT'])
    expect(defaultEditAction('CLAUSE')).toBe('REPLACE')
    expect(defaultEditAction('ABSENCE')).toBe('INSERT')
  })

  it('requires language for REPLACE/INSERT and forbids it for DELETE', () => {
    expect(editLanguageRequired('REPLACE')).toBe(true)
    expect(editLanguageRequired('INSERT')).toBe(true)
    expect(editLanguageRequired('DELETE')).toBe(false)
  })
})

describe('decision form validation', () => {
  it('requires a rationale for REJECT and ESCALATE', () => {
    expect(
      validateDecisionForm({ action: 'REJECT', comment: '' }).comment,
    ).toContain('comment')
    expect(
      validateDecisionForm({ action: 'ESCALATE', comment: '   ' }).comment,
    ).toContain('comment')
    expect(validateDecisionForm({ action: 'REJECT', comment: 'Reason.' }).comment).toBeUndefined()
  })

  it('requires language for REPLACE and INSERT edits and forbids it for DELETE', () => {
    expect(
      validateDecisionForm({ action: 'EDIT', approvedAction: 'REPLACE', approvedLanguage: '' })
        .approvedLanguage,
    ).toContain('language')
    expect(
      validateDecisionForm({ action: 'EDIT', approvedAction: 'INSERT', approvedLanguage: ' ' })
        .approvedLanguage,
    ).toContain('language')
    expect(
      validateDecisionForm({
        action: 'EDIT',
        approvedAction: 'REPLACE',
        approvedLanguage: 'Text.',
      }).approvedLanguage,
    ).toBeUndefined()
    expect(
      validateDecisionForm({ action: 'EDIT', approvedAction: 'DELETE', approvedLanguage: '' })
        .approvedLanguage,
    ).toBeUndefined()
  })

  it('enforces the contract limits without trimming legal text', () => {
    expect(
      validateDecisionForm({ action: 'REJECT', comment: 'x'.repeat(2001) }).comment,
    ).toContain('2000')
    expect(
      validateDecisionForm({
        action: 'EDIT',
        approvedAction: 'REPLACE',
        approvedLanguage: 'x'.repeat(20001),
      }).approvedLanguage,
    ).toContain('20000')
  })
})

describe('decision conflict classification', () => {
  it('reports a finalized review before anything else', () => {
    expect(
      classifyDecisionConflict({
        attemptedAction: 'ACCEPT',
        expectedVersion: 1,
        currentDecision: decision({ revision: 2 }),
        decisionStateKnown: true,
        humanReview: humanReview({ status: 'COMPLETED' }),
        analysisComplete: true,
      }),
    ).toBe('FINALIZED')
  })

  it('reports a stale revision when the server moved on', () => {
    expect(
      classifyDecisionConflict({
        attemptedAction: 'EDIT',
        expectedVersion: 1,
        currentDecision: decision({ revision: 2 }),
        decisionStateKnown: true,
        humanReview: humanReview(),
        analysisComplete: true,
      }),
    ).toBe('STALE')
    expect(
      classifyDecisionConflict({
        attemptedAction: 'EDIT',
        expectedVersion: 0,
        currentDecision: decision({ revision: 1 }),
        decisionStateKnown: true,
        humanReview: humanReview(),
        analysisComplete: true,
      }),
    ).toBe('STALE')
  })

  it('reports incomplete analysis and non-executable ACCEPT distinctly', () => {
    expect(
      classifyDecisionConflict({
        attemptedAction: 'EDIT',
        expectedVersion: 0,
        currentDecision: null,
        decisionStateKnown: true,
        humanReview: humanReview({ status: 'NOT_STARTED' }),
        analysisComplete: false,
      }),
    ).toBe('ANALYSIS_INCOMPLETE')
    expect(
      classifyDecisionConflict({
        attemptedAction: 'ACCEPT',
        expectedVersion: 0,
        currentDecision: null,
        decisionStateKnown: true,
        humanReview: humanReview(),
        analysisComplete: true,
      }),
    ).toBe('NOT_ACTIONABLE')
  })

  it('falls back to an unknown conflict instead of guessing', () => {
    expect(
      classifyDecisionConflict({
        attemptedAction: 'EDIT',
        expectedVersion: 0,
        currentDecision: null,
        decisionStateKnown: true,
        humanReview: humanReview(),
        analysisComplete: true,
      }),
    ).toBe('UNKNOWN')
  })

  it('never claims not-actionable when the decision state is unknown', () => {
    expect(
      classifyDecisionConflict({
        attemptedAction: 'ACCEPT',
        expectedVersion: 0,
        currentDecision: null,
        decisionStateKnown: false,
        humanReview: humanReview(),
        analysisComplete: true,
      }),
    ).toBe('UNKNOWN')
  })

  it('describes each conflict with truthful, safe copy', () => {
    expect(decisionConflictNotice('STALE')).toContain('changed')
    expect(decisionConflictNotice('FINALIZED')).toContain('finalized')
    expect(decisionConflictNotice('ANALYSIS_INCOMPLETE')).toContain('analysis')
    expect(decisionConflictNotice('NOT_ACTIONABLE')).toContain('Edit')
    expect(decisionConflictNotice('UNKNOWN')).toContain('not saved')
  })
})

describe('change set view-model', () => {
  it('counts changes by action type', () => {
    expect(
      changeSetCounts([
        { actionType: 'REPLACE' },
        { actionType: 'REPLACE' },
        { actionType: 'INSERT' },
        { actionType: 'DELETE' },
      ]),
    ).toEqual({ total: 4, replace: 2, insert: 1, delete: 1 })
  })

  it('labels clause targets from the semantics map and absence targets by concept', () => {
    const regionLabels = new Map([['node-6', '6. Limitation of Liability']])
    expect(
      changeTargetLabel(
        { targetKind: 'CLAUSE', targetHeadingNodeId: 'node-6', absenceConcept: null },
        regionLabels,
        humanizeCode,
      ),
    ).toBe('6. Limitation of Liability')
    expect(
      changeTargetLabel(
        { targetKind: 'ABSENCE', targetHeadingNodeId: null, absenceConcept: 'CONFIDENTIALITY_OBLIGATIONS' },
        regionLabels,
        humanizeCode,
      ),
    ).toBe('Missing provision: Confidentiality obligations')
    expect(
      changeTargetLabel(
        { targetKind: 'CLAUSE', targetHeadingNodeId: null, absenceConcept: null },
        regionLabels,
        humanizeCode,
      ),
    ).toBe('Clause')
  })

  it('summarises the human review counts for progress display', () => {
    const rows = humanReviewCountRows(
      humanReview({ actionableFindings: 3, resolvedFindings: 2, remainingFindings: 1 }),
    )
    expect(rows).toContainEqual({ label: 'Findings needing a decision', value: 3 })
    expect(rows).toContainEqual({ label: 'Decided', value: 2 })
    expect(rows).toContainEqual({ label: 'Still open', value: 1 })
  })
})
