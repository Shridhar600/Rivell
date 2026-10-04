import type { ArtifactType } from '../api/knowledge'

/**
 * Handed to the read surfaces by router state when a publication has just been made, so the page
 * the reviewer lands on can say what happened. It is a one-time message. The published knowledge
 * itself always comes from the server.
 */
export interface PublishedNotice {
  publicationNumber: number
  type: ArtifactType
  /** What the reviewer decided, so the message is about this publication and not a guess. */
  published: number
  rejected: number
  corrected: number
}

export function publishedState(notice: PublishedNotice): { published: PublishedNotice } {
  return { published: notice }
}

export function readPublishedNotice(state: unknown): PublishedNotice | null {
  if (typeof state !== 'object' || state === null || !('published' in state)) return null
  const value = (state as { published: unknown }).published
  if (typeof value !== 'object' || value === null) return null
  const notice = value as Partial<PublishedNotice>
  if (
    typeof notice.publicationNumber !== 'number' ||
    typeof notice.type !== 'string' ||
    typeof notice.published !== 'number' ||
    typeof notice.rejected !== 'number' ||
    typeof notice.corrected !== 'number'
  ) {
    return null
  }
  return notice as PublishedNotice
}
