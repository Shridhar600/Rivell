import type { JSX } from 'react'
import { matchPath } from 'react-router'
import { BookIcon, ClauseIcon, DocumentIcon, HomeIcon, OverviewIcon, SourceIcon } from '../ui/Icon'

/**
 * The one place the sidebar's structure is defined. Changing the product's navigation means
 * changing this file: the shell, the tests and the route audit all read from here.
 *
 * Only destinations that are real, list-level pages belong in it. Object pages (one Review, one
 * Playbook, one compilation) and creation actions are reached from the page that owns them, so
 * they are not navigation. The ids are stable keys for tests and styling, not display text.
 */

export type NavIcon = (props: { className?: string }) => JSX.Element

export interface NavDestination {
  id: string
  label: string
  to: string
  Icon: NavIcon
  /** Active only on the exact path. Needed where the path is a prefix of its own children. */
  end?: boolean
}

/**
 * The sidebar is a fixed list of groups. Every destination is always listed, so opening one never
 * pushes the rest of the sidebar around. A group without a heading is the product's own top level.
 */
export interface NavGroup {
  id: string
  heading?: string
  items: readonly NavDestination[]
}

export const NAV_GROUPS: readonly NavGroup[] = [
  {
    id: 'work',
    items: [
      { id: 'home', label: 'Home', to: '/', end: true, Icon: HomeIcon },
      { id: 'reviews', label: 'Reviews', to: '/reviews', Icon: DocumentIcon },
    ],
  },
  {
    id: 'knowledge',
    heading: 'Knowledge',
    items: [
      { id: 'knowledge-overview', label: 'Overview', to: '/knowledge', end: true, Icon: OverviewIcon },
      { id: 'knowledge-playbooks', label: 'Playbooks', to: '/knowledge/playbooks', Icon: BookIcon },
      { id: 'knowledge-clauses', label: 'Clause library', to: '/knowledge/clauses', Icon: ClauseIcon },
      { id: 'knowledge-sources', label: 'Sources', to: '/knowledge/sources', Icon: SourceIcon },
    ],
  },
]

/**
 * Things a reviewer starts. They are buttons wherever they are offered, never sidebar sections.
 * Add knowledge opens the dialog that Knowledge pages mount, addressed by `?add=`.
 */
export const CREATE_ACTIONS = {
  startReview: '/reviews/new',
  addKnowledge: '/knowledge?add=1',
} as const

/** Whether the current path belongs to a destination, by the same rule `NavLink` uses. */
export function isActive(destination: Pick<NavDestination, 'to' | 'end'>, pathname: string): boolean {
  return matchPath({ path: destination.to, end: destination.end ?? false }, pathname) !== null
}

/** Every address the sidebar can send a reviewer to. Used to prove none of them is dead. */
export function allNavigationTargets(): string[] {
  return [...new Set(NAV_GROUPS.flatMap((group) => group.items.map((item) => item.to)))]
}
