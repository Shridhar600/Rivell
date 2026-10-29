import type { DemoStage } from './state'
import type { DemoIds } from './transport'

export interface GuideStep {
  title: string
  text: string
  /** The one suggested next place, or one action inside the current dialog. */
  link?: { label: string; to: string }
  action?: 'prepared-file'
  /** The prepared-result notice the plan requires after finalize. */
  note?: string
}

export interface GuideInput {
  pathname: string
  search: string
  stage: DemoStage
  ids: DemoIds
}

export const PREPARED_RESULT_NOTICE =
  'This redline was prepared in advance. It does not include changes you make in the demo.'

/** Shown once the NDA review is finalized: the change set and redline come from the recorded review. */
export const PREPARED_REVIEW_NOTICE =
  'This result was prepared in advance from a recorded review. It does not include changes you make in the demo.'

function workspacePath(ids: { contractId: string; reviewId: string }): string {
  return `/reviews/${ids.contractId}/analysis/${ids.reviewId}`
}

function lifecyclePath(ids: { contractId: string; reviewId: string }): string {
  return `/reviews/${ids.contractId}/overview/${ids.reviewId}`
}

function onReview(pathname: string, ids: { reviewId: string }): 'workspace' | 'lifecycle' | null {
  if (pathname.includes(`/analysis/${ids.reviewId}`)) return 'workspace'
  if (pathname.includes(`/overview/${ids.reviewId}`)) return 'lifecycle'
  return null
}

function c5Step(input: GuideInput, where: 'workspace' | 'lifecycle'): GuideStep {
  const ids = input.ids.c5!
  switch (input.stage) {
    case 'tour-k2-published':
      if (where === 'lifecycle') {
        return {
          title: 'Review the NDA',
          text: 'The NDA is reviewed and ready. Decide each finding in the workspace.',
          link: { label: 'Open the review', to: workspacePath(ids) },
        }
      }
      return {
        title: 'Decide each finding',
        text: 'Rivell flagged the clauses your NDA playbook does not cover. Decide each one: edit, keep as drafted or dismiss.',
      }
    case 'tour-c5-decided':
      if (where === 'lifecycle') {
        return {
          title: 'Finalize the review',
          text: 'Every finding is decided. Finalize it in the workspace.',
          link: { label: 'Open the review', to: workspacePath(ids) },
        }
      }
      return {
        title: 'Finalize the review',
        text: 'Every finding is decided. Finalize the review to see the prepared result.',
      }
    case 'tour-c5-finalized':
      if (where === 'workspace') {
        return {
          title: 'Produce the redline',
          text: 'The review is finalized with the prepared result. The lifecycle page produces and checks the redline.',
          link: { label: 'Open the lifecycle', to: lifecyclePath(ids) },
          note: PREPARED_REVIEW_NOTICE,
        }
      }
      return {
        title: 'Produce the redline',
        text: 'Produce the redline, then download it. Rivell checks it against the original before you get it.',
        note: PREPARED_REVIEW_NOTICE,
      }
    case 'tour-c5-done':
      if (where === 'workspace') {
        return {
          title: 'Download the redline',
          text: 'The verified redline is on the lifecycle page.',
          link: { label: 'Open the lifecycle', to: lifecyclePath(ids) },
          note: PREPARED_RESULT_NOTICE,
        }
      }
      return {
        title: 'Download the redline',
        text: 'That is the tour. Download the Word file to see the tracked changes.',
        note: PREPARED_RESULT_NOTICE,
      }
    default:
      return {
        title: 'Review the NDA',
        text: 'Add and publish the NDA playbook first.',
        link: { label: 'Go to Knowledge', to: '/knowledge' },
      }
  }
}

/**
 * One short step for where the visitor is and how far the tour has moved. Route-specific hints
 * for the other reviews come first when the visitor roams away from the tour.
 */
export function guideFor(input: GuideInput): GuideStep {
  const { pathname, search, stage, ids } = input
  if (new URLSearchParams(search).get('add') !== null) {
    return {
      title: 'Use the prepared playbook',
      text: 'The demo can add one prepared file, the NDA playbook. Use it, then continue in the dialog.',
      action: 'prepared-file',
    }
  }

  if (ids.c5) {
    const where = onReview(pathname, ids.c5)
    if (where) return c5Step(input, where)
  }

  if (ids.orderForm) {
    const where = onReview(pathname, ids.orderForm)
    if (where) {
      return {
        title: 'A finished redline is waiting',
        text: 'The order form was finalized and its redline is verified.',
        ...(where === 'workspace'
          ? { link: { label: 'Open the lifecycle', to: lifecyclePath(ids.orderForm) } }
          : {}),
      }
    }
  }
  if (ids.msa) {
    const where = onReview(pathname, ids.msa)
    if (where) {
      return {
        title: 'Items left for a person',
        text: 'This review is in progress. The open items are the ones Rivell could not settle alone.',
        ...(where === 'lifecycle'
          ? { link: { label: 'Open the review', to: workspacePath(ids.msa) } }
          : {}),
      }
    }
  }
  if (ids.vantage) {
    if (onReview(pathname, ids.vantage)) {
      return {
        title: 'Ready to finalize',
        text: 'Every finding is decided. Finalizing this review is not part of the demo.',
      }
    }
  }

  const onK2Compilation = ids.k2ArtifactId
    ? pathname === `/knowledge/compilations/${ids.k2ArtifactId}`
    : false
  const k2Link = ids.k2ArtifactId
    ? { label: 'Open the compilation', to: `/knowledge/compilations/${ids.k2ArtifactId}` }
    : null

  if (stage === 'base') {
    if (pathname === '/knowledge' || pathname.startsWith('/knowledge/')) {
      return {
        title: 'Add the NDA playbook',
        text: 'The NDA playbook is not added yet. Add it, and Rivell turns it into positions you approve.',
        link: { label: 'Add knowledge', to: '/knowledge?add=playbook' },
      }
    }
    return {
      title: 'This is the Bramber workspace',
      text: 'Four contracts sit at different stages. Add the NDA playbook to review a fifth, a mutual NDA.',
      link: { label: 'Go to Knowledge', to: '/knowledge' },
    }
  }

  if (stage === 'tour-k2-compiled') {
    if (onK2Compilation) {
      return {
        title: 'Approve the positions',
        text: 'Check each position Rivell read from the playbook and approve it. Publish once all are approved.',
      }
    }
    return {
      title: 'Approve the NDA playbook',
      text: 'The NDA playbook is compiled and waiting for approval.',
      ...(k2Link ? { link: k2Link } : {}),
    }
  }

  if (stage === 'tour-k2-approved') {
    if (onK2Compilation) {
      return {
        title: 'Publish the playbook',
        text: 'Every entry is approved. Publishing makes the playbook available to new reviews.',
      }
    }
    return {
      title: 'Publish the NDA playbook',
      text: 'The playbook is approved and waiting to be published.',
      ...(k2Link ? { link: k2Link } : {}),
    }
  }

  if (stage === 'tour-c5-done') {
    return {
      title: 'That is the tour',
      text: 'You added knowledge, published it, decided a review and produced a checked redline.',
      note: PREPARED_RESULT_NOTICE,
      link: { label: 'Back to Home', to: '/' },
    }
  }

  if (ids.c5) {
    if (stage === 'tour-k2-published') {
      return {
        title: 'Open the NDA review',
        text: 'The playbook is published and the Sablepoint NDA is reviewed. It is ready for your decisions.',
        link: { label: 'Open the NDA review', to: workspacePath(ids.c5) },
      }
    }
    if (stage === 'tour-c5-decided') {
      return {
        title: 'Finalize the NDA review',
        text: 'Every finding is decided. Open the review to finalize it.',
        link: { label: 'Open the review', to: workspacePath(ids.c5) },
      }
    }
    return {
      title: 'Produce the redline',
      text: 'Open the NDA lifecycle page to produce and download the redline.',
      link: { label: 'Open the lifecycle', to: lifecyclePath(ids.c5) },
      note: PREPARED_REVIEW_NOTICE,
    }
  }

  return {
    title: 'The demo is ready',
    text: 'This demo shows real output recorded from a reviewed contract set.',
    link: { label: 'Go to Home', to: '/' },
  }
}
