import { useMemo } from 'react'
import { useNavigate } from 'react-router'
import { useOrganizationReviewsQuery } from '../../api/reviews'
import { useKnowledgeCatalog } from '../../api/knowledgeRead'
import { useTaxonomy } from '../../api/taxonomy'
import { artifactName, clauseGroups } from '../../lib/knowledgeCatalog'
import { lifecyclePath } from '../../lib/reviewLifecycle'
import { CommandPalette, type PaletteItem } from '../ui/CommandPalette'
import { BookIcon, ClauseIcon, DocumentIcon, PlusIcon, SourceIcon } from '../ui/Icon'
import { CREATE_ACTIONS, NAV_GROUPS } from './navigation'
import type { PageCommands } from './ShellContext'

interface Props {
  onClose: () => void
  page: PageCommands | null
}

/**
 * Mounted only while the menu is open, so the lists behind it are read on demand and never slow
 * down the page underneath.
 */
export function GlobalSearch({ onClose, page }: Props) {
  const navigate = useNavigate()
  const reviews = useOrganizationReviewsQuery()
  const knowledge = useKnowledgeCatalog()
  const { labelFor } = useTaxonomy()

  const items = useMemo<PaletteItem[]>(() => {
    const go = (to: string) => () => void navigate(to)
    const catalog = knowledge.catalog
    return [
      ...(page?.items ?? []),
      ...(reviews.data?.items ?? []).map((review) => ({
        group: 'Reviews',
        label: (review.contractFilename ?? 'Untitled contract').replace(/\.docx$/i, ''),
        hint: `v${review.contractVersionNumber}`,
        icon: <DocumentIcon className="rv-icon" />,
        run: go(lifecyclePath(review.contractId, review.id)),
      })),
      ...(catalog?.playbooks ?? []).map((playbook) => ({
        group: 'Playbooks',
        label: artifactName(playbook.artifact),
        icon: <BookIcon className="rv-icon" />,
        run: go(`/knowledge/playbooks/${playbook.artifact.id}`),
      })),
      ...(catalog ? clauseGroups(catalog.clauseLibrary, catalog.index, labelFor) : []).map((clause) => ({
        group: 'Clauses',
        label: clause.title,
        icon: <ClauseIcon className="rv-icon" />,
        run: go(`/knowledge/clauses/${encodeURIComponent(clause.key)}`),
      })),
      ...(catalog?.sources ?? []).map((source) => ({
        group: 'Sources',
        label: artifactName(source.artifact),
        icon: <SourceIcon className="rv-icon" />,
        run: go(`/knowledge/sources/${source.artifact.id}`),
      })),
      { group: 'Create', label: 'Start a review', icon: <PlusIcon className="rv-icon" />, run: go(CREATE_ACTIONS.startReview) },
      { group: 'Create', label: 'Add knowledge', icon: <PlusIcon className="rv-icon" />, run: go(CREATE_ACTIONS.addKnowledge) },
      ...NAV_GROUPS.flatMap((group) =>
        group.items.map((item) => ({
          group: 'Go to',
          label: group.heading && item.label !== group.heading ? `${group.heading}: ${item.label}` : item.label,
          icon: <item.Icon className="rv-icon" />,
          run: go(item.to),
        })),
      ),
    ]
  }, [page, reviews.data, knowledge.catalog, labelFor, navigate])

  return (
    <CommandPalette
      open
      onClose={onClose}
      items={items}
      onSearch={page?.onSearch}
      loading={reviews.isPending || knowledge.status === 'pending'}
    />
  )
}
