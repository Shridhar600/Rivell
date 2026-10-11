-- The heading part of a heading paragraph that also carries clause text ("5. Term. This
-- Agreement..."), so a redline can delete the clause text and keep the heading.
ALTER TABLE document_node ADD COLUMN run_in_heading TEXT;
