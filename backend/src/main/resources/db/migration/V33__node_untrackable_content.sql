-- A paragraph holding inline content a tracked change cannot delete (footnote reference, field,
-- drawing, embedded object); a change that would delete it is refused before approval.
ALTER TABLE document_node ADD COLUMN untrackable_content BOOLEAN NOT NULL DEFAULT FALSE;
