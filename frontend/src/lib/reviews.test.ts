import { describe, expect, it } from 'vitest'
import type { FindingDetail, FindingQuality, PlaybookUsed } from '../api/reviews'
import {
  analysisWarningCheckLabel,
  analysisWarningMessage,
  approvalConflictLine,
  approvalSourceLabel,
  approvalSummary,
  checkKeyLabel,
  clauseMarkLabel,
  clauseMarksLegend,
  coverageLimitationSummary,
  decidedByLabel,
  escalateIfLine,
  escalateIfText,
  findingGapText,
  findingIssueLabel,
  findingPlaybookRule,
  findingQuote,
  NO_PLAYBOOK_COVERS,
  observationKindLabel,
  playbooksUsedText,
} from './reviews'

const quality = (overrides: Partial<FindingQuality>): FindingQuality => ({
  sufficiency: null,
  verificationOutcome: null,
  degradationFlags: [],
  category: null,
  flagProbability: null,
  probabilities: {},
  secondaryTopic: false,
  uncoveredProbability: null,
  route: null,
  jevCategory: null,
  secondOpinionOutcome: null,
  secondOpinionCategory: null,
  secondOpinionQuote: null,
  secondOpinionReason: null,
  uncertaintyReason: null,
  foundBy: null,
  spottedSeverity: null,
  spottedReason: null,
  presenceProbability: null,
  ...overrides,
})

describe('decidedByLabel', () => {
  it('says who decided, with the first review’s estimate, and never names a provider', () => {
    expect(decidedByLabel(quality({ route: 'JEV', flagProbability: 0.87 }))).toBe(
      'Decided by Rivell’s first review · 87%',
    )
    expect(decidedByLabel(quality({ route: 'JEV', uncoveredProbability: 0.62 }))).toBe(
      'Decided by Rivell’s first review · 62%',
    )
    expect(decidedByLabel(quality({ route: 'SECOND_OPINION' }))).toBe('Decided with a second opinion')
    expect(decidedByLabel(quality({ route: 'NEEDS_YOUR_LOOK' }))).toBe('Needs your look')
  })

  it('shows nothing for a finding that carries no route', () => {
    expect(decidedByLabel(quality({}))).toBeNull()
    expect(decidedByLabel(null)).toBeNull()
  })
})

describe('clause marks', () => {
  it('says in plain words what Rivell did with each clause', () => {
    expect(clauseMarkLabel('FLAGGED')).toBe('Flagged')
    expect(clauseMarkLabel('CHECKED')).toBe('Checked, no issue found')
    expect(clauseMarkLabel('NOT_COVERED')).toBe('Not covered by your playbook')
    expect(clauseMarkLabel('NOT_ANALYSED')).toBe('Not analysed: the check did not finish')
    expect(clauseMarkLabel('NOT_PLANNED')).toBe('Not reviewed: outside the review plan')
  })

  it('counts the marks for the legend and leaves out a mark no clause has', () => {
    expect(clauseMarksLegend(['FLAGGED', 'CHECKED', 'CHECKED', 'NOT_ANALYSED', 'NOT_PLANNED'])).toBe(
      '1 flagged · 2 checked · 1 not analysed · 1 not reviewed',
    )
    expect(clauseMarksLegend([])).toBe('')
  })
})

describe('a finding a reviewer added', () => {
  it('is listed as added by a reviewer', () => {
    expect(findingIssueLabel({ issueFamily: 'USER_ADDED', observationKind: 'USER_ADDED' })).toBe(
      'Added by a reviewer',
    )
  })
})

describe('playbooks used', () => {
  it('names what the review froze, in the order it froze them', () => {
    expect(playbooksUsedText([{ name: 'Commercial MSA Playbook' }, { name: 'Clause Library' }])).toBe(
      'Commercial MSA Playbook, Clause Library',
    )
  })

  it('states the empty case plainly, so an unusual clause is not read as a policy review', () => {
    expect(NO_PLAYBOOK_COVERS).toBe(
      'No playbook covers this contract type, so this is not a policy review. Rivell flagged only clauses that read as unusual. Read the whole contract yourself.',
    )
    expect(playbooksUsedText([])).toBe(NO_PLAYBOOK_COVERS)
  })

  it('shows nothing for a review that predates the freeze', () => {
    expect(playbooksUsedText(null)).toBeNull()
  })
})

const labelFor = (concept: string) =>
  ({
    LIMITATION_OF_LIABILITY: 'Limitation of liability',
    TERM: 'Term',
    INDEMNIFICATION: 'Indemnification',
    GOVERNING_LAW: 'Governing law',
  })[concept] ?? concept

const secondOpinionQuality = (overrides: Partial<FindingQuality> = {}) =>
  quality({
    route: 'NEEDS_YOUR_LOOK',
    secondOpinionOutcome: 'DISAGREED',
    secondOpinionQuote: 'liability shall not exceed the fees paid',
    secondOpinionReason: 'The cap matches the preferred basis in the position.',
    ...overrides,
  })

const findingText = (overrides: Record<string, unknown>) =>
  ({
    observationText: '',
    concepts: [],
    concept: null,
    secondaryConcept: null,
    absenceConcept: null,
    addedBy: null,
    subjectKind: 'CLAUSE',
    decidedBy: null,
    quality: null,
    evidenceItems: [],
    evidenceItemIds: [],
    positionComparison: null,
    positionLabel: null,
    issueFamily: 'POSITION_NEEDS_FALLBACK',
    ...overrides,
  }) as unknown as FindingDetail

const playbooks: PlaybookUsed[] = [
  {
    artifactId: 'a-playbook',
    artifactType: 'PLAYBOOK',
    name: 'Mutual NDA Negotiation Guide',
    publicationNumber: 2,
  },
]

describe('findingGapText', () => {
  it('reads an old stored disagreement as the sentence Rivell now uses', () => {
    const finding = findingText({
      observationText:
        "Rivell's two reviewers disagree on this clause against the position: one finds an issue, the other does not. Rivell does not decide; please take a look.",
      concepts: ['LIMITATION_OF_LIABILITY'],
      concept: 'LIMITATION_OF_LIABILITY',
    })

    expect(findingGapText(finding, labelFor)).toBe(
      'Rivell is unsure about this clause. Here is the clause and the closest rule.',
    )
  })

  it('reads the previous release’s second-opinion sentences as the new sentence', () => {
    const disagreed = findingText({
      observationText:
        'Rivell is unsure whether this clause meets your position: one check finds an issue and the other does not. Compare the clause with the position below.',
    })
    const unavailable = findingText({
      observationText:
        'Rivell is unsure whether this clause meets your position, and could not get a second check. Compare the clause with the position below.',
    })

    expect(findingGapText(disagreed, labelFor)).toBe(
      'Rivell is unsure about this clause. Here is the clause and the closest rule.',
    )
    expect(findingGapText(unavailable, labelFor)).toBe(
      'Rivell is unsure about this clause, and could not get a second check. Here is the clause and the closest rule.',
    )
  })

  it('replaces the old compliance sentence and names the contract words in plain terms', () => {
    const finding = findingText({
      observationText:
        'The clause deals with this subject, but its wording is ambiguous or depends on something outside the contract, so compliance cannot be decided.',
      concepts: ['LIMITATION_OF_LIABILITY'],
      concept: 'LIMITATION_OF_LIABILITY',
    })

    expect(findingGapText(finding, labelFor)).toBe(
      'The clause depends on something outside the contract, so Rivell cannot tell whether it meets your playbook.',
    )
  })

  it('drops the old human-review sentence from a stored finding', () => {
    const finding = findingText({
      observationText:
        'No playbook position addresses this clause, and it reads as unusual for this kind of contract and against our side; a lawyer should review it.',
    })

    expect(findingGapText(finding, labelFor)).toBe(
      'No playbook position addresses this clause, and it reads as unusual for this kind of contract and against our side.',
    )
  })

  it('never edits the words a reviewer wrote', () => {
    const finding = findingText({
      addedBy: 'u-1',
      observationText: 'A lawyer should review it, and recommend human legal review.',
    })

    expect(findingGapText(finding, labelFor)).toBe(
      'A lawyer should review it, and recommend human legal review.',
    )
  })

  it('reads every old uncertainty sentence in the words Rivell uses now', () => {
    const cases: [string, string][] = [
      [
        'Rivell was not confident enough about this clause to judge it against the position. Please take a look.',
        'Rivell could not check this clause against your playbook. Please check it yourself.',
      ],
      [
        'Rivell could not check this clause because the analysis service was unavailable. Please take a look.',
        'Rivell could not check this clause because the analysis service was unavailable. Please check it yourself.',
      ],
      [
        'The clauses were too long for Rivell to compare, so it did not check how they interact. Please take a look.',
        'There was too much text for Rivell to compare these clauses. Please check how the liability cap and its exceptions fit together.',
      ],
      [
        'Rivell could not tell how the liability carve-out and the indemnification clause interact, so it reports no conflict either way. Please take a look.',
        'Rivell could not check how the liability cap and its exceptions fit together. Please check both clauses yourself.',
      ],
      [
        'Rivell could not reach a clear view of how these clauses interact. Please take a look.',
        'Rivell could not check how these clauses fit together. Please check them yourself.',
      ],
      [
        'Rivell could not check whether every confidentiality obligation survives termination. Please take a look.',
        'Rivell could not check whether every confidentiality obligation survives termination. Please check the termination clause yourself.',
      ],
    ]

    for (const [oldText, newText] of cases) {
      expect(findingGapText(findingText({ observationText: oldText }), labelFor)).toBe(newText)
    }
  })

  it('shows the old ambiguity sentence as the new rule sentence and labels the topic', () => {
    const finding = findingText({
      observationText:
        'Multiple conflicting published policies apply to LIMITATION_OF_LIABILITY; no rule was chosen and the ambiguity is preserved.',
      concepts: ['LIMITATION_OF_LIABILITY'],
      concept: 'LIMITATION_OF_LIABILITY',
    })

    expect(findingGapText(finding, labelFor)).toBe(
      'More than one rule in your playbook applies to Limitation of liability, and they conflict. Rivell did not choose between them.',
    )
  })
})

describe('findingQuote', () => {
  it('uses the second opinion’s words on a needs-your-look item', () => {
    expect(
      findingQuote(
        findingText({ decidedBy: 'NEEDS_YOUR_LOOK', quality: secondOpinionQuality() }),
        '6. Limitation of Liability. The aggregate liability shall not exceed the fees paid.',
      ),
    ).toEqual({ text: 'liability shall not exceed the fees paid', source: 'second-opinion' })
  })

  it('uses the pinned contract words for an ordinary clause finding', () => {
    expect(
      findingQuote(findingText({}), '6. Limitation of Liability. The aggregate liability is capped.'),
    ).toEqual({
      text: '6. Limitation of Liability. The aggregate liability is capped.',
      source: 'contract',
    })
  })

  it('falls back to the frozen contract evidence when the version text is unavailable', () => {
    const finding = findingText({
      evidenceItems: [
        { evidenceId: 'e-1', authorityClass: 'CURRENT_CONTRACT', text: 'The cap is two times the fees.' },
      ],
      evidenceItemIds: ['e-1'],
    })

    expect(findingQuote(finding, null)).toEqual({
      text: 'The cap is two times the fees.',
      source: 'contract',
    })
  })

  it('never quotes policy wording as the contract', () => {
    const finding = findingText({
      evidenceItems: [
        { evidenceId: 'e-1', authorityClass: 'POLICY', text: 'Liability must be capped.' },
      ],
      evidenceItemIds: ['e-1'],
    })

    expect(findingQuote(finding, null)).toBeNull()
  })

  it('shows no quote for an absence finding', () => {
    expect(findingQuote(findingText({ subjectKind: 'ABSENCE' }), 'The clause text.')).toBeNull()
  })
})

describe('findingPlaybookRule', () => {
  const position = {
    contract: [],
    preferred: [
      {
        evidenceItemId: 'e-policy',
        authorityClass: 'POLICY',
        legalConcept: 'LIMITATION_OF_LIABILITY',
        publicationNumber: 2,
        text: 'Liability must be capped at fees paid in the preceding 12 months.',
      },
    ],
    fallback: [
      {
        evidenceItemId: 'e-policy',
        authorityClass: 'POLICY',
        legalConcept: 'LIMITATION_OF_LIABILITY',
        publicationNumber: 2,
        text: 'Liability must be capped at two times the fees paid.',
      },
    ],
    walkAway: [],
    escalation: [
      {
        evidenceItemId: 'e-policy',
        authorityClass: 'POLICY',
        publicationNumber: 2,
        trigger: 'Uncapped liability or consequential damages',
      },
    ],
  }

  const evidenceItems = [
    {
      evidenceId: 'e-policy',
      authorityClass: 'POLICY',
      sourceArtifactId: 'a-playbook',
      text: 'Liability must be capped at fees paid in the preceding 12 months.',
    },
  ]

  it('shows the closest rule, its position label and the playbook it came from', () => {
    const finding = findingText({
      concept: 'LIMITATION_OF_LIABILITY',
      issueFamily: 'POSITION_NEEDS_FALLBACK',
      positionComparison: position,
      evidenceItems,
    })

    expect(findingPlaybookRule(finding, labelFor, playbooks)).toEqual({
      label: 'Limitation of liability',
      text: 'Liability must be capped at two times the fees paid.',
      basedOn: 'Mutual NDA Negotiation Guide, version 2',
      escalation: 'Uncapped liability or consequential damages',
    })
  })

  it('names no playbook when the rule cannot be traced to one the review froze', () => {
    const finding = findingText({
      concept: 'LIMITATION_OF_LIABILITY',
      issueFamily: 'POSITION_NEEDS_FALLBACK',
      positionComparison: position,
      evidenceItems,
    })

    expect(findingPlaybookRule(finding, labelFor, null)?.basedOn).toBeNull()
  })

  it('shows an omission’s own position label without inventing a rule', () => {
    const finding = findingText({
      subjectKind: 'ABSENCE',
      issueFamily: 'MISSING_REQUIRED_PROVISION',
      positionLabel: 'Cap Exceptions',
    })

    expect(findingPlaybookRule(finding, labelFor, playbooks)).toEqual({
      label: 'Cap Exceptions',
      text: null,
      basedOn: null,
      escalation: null,
    })
  })

  it('shows an omission’s rule and playbook when the cited evidence carries them', () => {
    const finding = findingText({
      subjectKind: 'ABSENCE',
      issueFamily: 'MISSING_REQUIRED_PROVISION',
      positionLabel: 'Cap Exceptions',
      positionComparison: position,
      evidenceItems,
    })

    expect(findingPlaybookRule(finding, labelFor, playbooks)).toEqual({
      label: 'Cap Exceptions',
      text: 'Liability must be capped at fees paid in the preceding 12 months.',
      basedOn: 'Mutual NDA Negotiation Guide, version 2',
      escalation: 'Uncapped liability or consequential damages',
    })
  })

  it('shows every fallback rather than choosing one for the reader', () => {
    const multiFallback = {
      ...position,
      fallback: [
        position.fallback[0],
        { ...position.fallback[0], text: 'Liability must be capped at three times the fees paid.' },
      ],
    }
    const finding = findingText({
      concept: 'LIMITATION_OF_LIABILITY',
      issueFamily: 'POSITION_ACCEPTABLE_FALLBACK',
      positionComparison: multiFallback,
      evidenceItems,
    })

    expect(findingPlaybookRule(finding, labelFor, playbooks)?.text).toBe(
      'Liability must be capped at two times the fees paid.\n\nLiability must be capped at three times the fees paid.',
    )
  })

  it('shows no label-only block for an ordinary position with no rule data', () => {
    const finding = findingText({
      concept: 'LIMITATION_OF_LIABILITY',
      issueFamily: 'POSITION_NEEDS_FALLBACK',
      positionComparison: null,
    })

    expect(findingPlaybookRule(finding, labelFor, playbooks)).toBeNull()
  })

  it('shows no playbook block for a policy the playbook does not cover', () => {
    expect(
      findingPlaybookRule(
        findingText({ issueFamily: 'POLICY_UNAVAILABLE', positionComparison: position }),
        labelFor,
        playbooks,
      ),
    ).toBeNull()
  })

  it('never labels a playbook-silent advisory "Policy unavailable"', () => {
    expect(
      findingIssueLabel({
        issueFamily: 'POLICY_UNAVAILABLE',
        observationKind: 'SPOTTED_IN_WHOLE_CONTRACT_READ',
      }),
    ).toBe('Not covered by your playbook')
  })
})

describe('coverage notes and check labels', () => {
  it('says only which topics Rivell did not check, never a topic it checked and did not find', () => {
    expect(
      coverageLimitationSummary(
        [
          { concept: 'TERM', expectation: 'REQUIRED', coverage: 'PRESENT_SECONDARY_ONLY' },
          { concept: 'LIMITATION_OF_LIABILITY', expectation: 'REQUIRED', coverage: 'PRESENT_PRIMARY' },
          { concept: 'INDEMNIFICATION', expectation: 'OPTIONAL', coverage: 'ABSENT' },
        ],
        labelFor,
      ),
    ).toBe('Rivell did not check: Term')
  })

  it('names the carve-out warning in plain words, without a bounded classification', () => {
    expect(checkKeyLabel('CROSS:CARVE_OUT_CONSISTENCY:LIMITATION_OF_LIABILITY:INDEMNIFICATION:x', labelFor)).toBe(
      'Liability cap and its exceptions',
    )
    const carveOut = {
      checkKey: 'CROSS:CARVE_OUT_CONSISTENCY:LIMITATION_OF_LIABILITY:INDEMNIFICATION:x',
      status: 'DEGRADED',
      reasonCode: 'CLASSIFICATION_UNCERTAIN',
      message: 'Rivell was not confident enough about this clause to rely on the result.',
    }
    expect(analysisWarningMessage(carveOut)).toBe(
      'Rivell could not check how the liability cap and its exceptions fit together. Please check both clauses yourself.',
    )
    // One plain sentence, not "Cross · Carve out consistency" followed by a second sentence.
    expect(analysisWarningCheckLabel(carveOut, labelFor)).toBeNull()
    expect(
      analysisWarningCheckLabel(
        { ...carveOut, checkKey: 'POLICY_COMPARISON:LIMITATION_OF_LIABILITY', reasonCode: 'PROVIDER_UNAVAILABLE' },
        labelFor,
      ),
    ).toBe('Policy comparison · Limitation of liability')
  })

  it('uses short, specific words for an uncertain check on the row', () => {
    expect(observationKindLabel('UNCERTAINTY:second_opinion_disagrees')).toBe(
      'Rivell is unsure',
    )
    expect(observationKindLabel('UNCERTAINTY:second_opinion_unavailable')).toBe('Not double-checked')
  })

  it('shows an approval only when a typed rule names an approver', () => {
    // a trigger text alone is not an approval
    expect(
      approvalSummary(quality({ escalationCondition: 'Escalate above net 90' })),
    ).toBeNull()

    const summary = approvalSummary(
      quality({
        escalationApprover: 'Commercial Manager',
        escalationCondition: 'Uncapped liability or consequential damages',
        escalationSource: 'MATRIX',
        escalationStance: 'FALLBACK',
        escalationConflictApprover: 'General Counsel',
        escalationConflictSource: 'ENTRY',
      }),
    )

    expect(summary).toEqual({
      approver: 'Commercial Manager',
      condition: 'Uncapped liability or consequential damages',
      source: 'MATRIX',
      stance: 'FALLBACK',
      conflict: { approver: 'General Counsel', source: 'ENTRY' },
    })
    expect(escalateIfLine(summary)).toBe(
      'Escalate if: Uncapped liability or consequential damages.',
    )
    expect(escalateIfText('Escalate shorter periods for source code or customer data.')).toBe(
      'shorter periods for source code or customer data',
    )
    expect(escalateIfText('Escalate to the named approver at a walk-away')).toBeNull()
    expect(
      escalateIfText('Escalate to general counsel before agreeing to any cap above the preferred position'),
    ).toBe('agreeing to any cap above the preferred position')
    expect(escalateIfText('  ')).toBeNull()
    expect(escalateIfText('Escalate.')).toBeNull()
    expect(approvalConflictLine(summary)).toBe('The entry names General Counsel for this.')
    expect(approvalSourceLabel(summary?.source ?? null)).toBe('from the approval matrix')
    expect(approvalConflictLine(null)).toBeNull()
    expect(escalateIfLine(null)).toBeNull()
  })

  it('names the approval matrix or table in the conflict line, whichever is lower', () => {
    const matrix = approvalSummary(
      quality({
        escalationApprover: 'The lawyer handling the NDA',
        escalationSource: 'ENTRY',
        escalationConflictApprover: 'Reviewing Commercial Legal counsel',
        escalationConflictSource: 'TABLE',
      }),
    )
    expect(approvalConflictLine(matrix)).toBe(
      'The approval table names Reviewing Commercial Legal counsel for this.',
    )

    const higher = approvalSummary(
      quality({
        escalationApprover: 'Commercial Legal Director',
        escalationSource: 'ENTRY',
        escalationConflictApprover: 'Commercial Manager',
        escalationConflictSource: 'MATRIX',
      }),
    )
    expect(approvalConflictLine(higher)).toBe(
      'The approval matrix names Commercial Manager for this.',
    )
  })
})
