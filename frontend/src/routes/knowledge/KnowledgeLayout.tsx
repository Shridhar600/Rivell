import { Outlet, useSearchParams } from 'react-router'
import { AddKnowledgeDialog, type AddType } from '../../components/knowledge/add/AddKnowledgeDialog'

const PRESET: Record<string, AddType> = { playbook: 'PLAYBOOK', 'clause-library': 'CLAUSE_LIBRARY' }

/**
 * Wraps every Knowledge page so Add knowledge can open on any of them. It lives here, above the
 * pages, because each page replaces its own frame when its data arrives. A dialog inside a page
 * would be rebuilt then, and the reviewer would lose the file they had just chosen.
 */
export function KnowledgeLayout() {
  const [params, setParams] = useSearchParams()
  const adding = params.get('add')
  const close = () =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        next.delete('add')
        return next
      },
      { replace: true },
    )
  return (
    <>
      <Outlet />
      {adding !== null ? <AddKnowledgeDialog key={adding} preset={PRESET[adding] ?? null} onClose={close} /> : null}
    </>
  )
}
