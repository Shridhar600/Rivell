export type CreateOutcomeKind =
  | 'CONFLICT'
  | 'INVALID_PACK'
  | 'NOT_FOUND'
  | 'FORBIDDEN'
  | 'TOO_LARGE'
  | 'UNCONFIRMED'

export type CreateOutcome = {
  kind: CreateOutcomeKind
  message: string
  uncertain: boolean
}

const OUTCOMES: Record<CreateOutcomeKind, Omit<CreateOutcome, 'kind'>> = {
  CONFLICT: {
    message:
      'Rivell could not start a review for this contract version. The analysis may not be ready, or this request was already used for a different contract version or review pack. Check the review history before trying again.',
    uncertain: false,
  },
  INVALID_PACK: {
    message:
      'The review could not be started with the selected review pack. Choose a different pack, or run the review without a pack.',
    uncertain: false,
  },
  NOT_FOUND: {
    message: 'This contract version is not available for review.',
    uncertain: false,
  },
  FORBIDDEN: {
    message: 'This review could not be started.',
    uncertain: false,
  },
  TOO_LARGE: {
    message:
      'The uploaded file is larger than Rivell accepts. Upload a smaller file, or ask an administrator to raise the limit.',
    uncertain: false,
  },
  UNCONFIRMED: {
    message:
      'We could not confirm whether the review started. Check the review history before starting another review.',
    uncertain: true,
  },
}

export function classifyCreateOutcome(status: number | null): CreateOutcome {
  switch (status) {
    case 400:
      return { kind: 'INVALID_PACK', ...OUTCOMES.INVALID_PACK }
    case 403:
      return { kind: 'FORBIDDEN', ...OUTCOMES.FORBIDDEN }
    case 404:
      return { kind: 'NOT_FOUND', ...OUTCOMES.NOT_FOUND }
    case 409:
      return { kind: 'CONFLICT', ...OUTCOMES.CONFLICT }
    case 413:
      return { kind: 'TOO_LARGE', ...OUTCOMES.TOO_LARGE }
    default:
      return { kind: 'UNCONFIRMED', ...OUTCOMES.UNCONFIRMED }
  }
}
