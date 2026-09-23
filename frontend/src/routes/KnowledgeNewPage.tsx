import { AddKnowledgeForm } from '../components/knowledge/AddKnowledgeForm'
import { useHeadingFocus } from '../lib/useHeadingFocus'

export function KnowledgeNewPage() {
  const headingRef = useHeadingFocus<HTMLHeadingElement>(true)

  return (
    <section aria-labelledby="add-knowledge-heading" className="page">
      <h1 id="add-knowledge-heading" tabIndex={-1} ref={headingRef}>
        Add knowledge
      </h1>
      <p className="meta">
        Upload a DOCX or XLSX source, declare what it is, and Rivell compiles it
        into reviewable candidates.
      </p>
      <AddKnowledgeForm />
    </section>
  )
}
