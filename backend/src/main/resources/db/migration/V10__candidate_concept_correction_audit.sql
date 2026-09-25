-- Concept-correction audit exposure (pre-M7 review: write-only corrected_by).
alter table knowledge_candidate add column concept_corrected_at timestamptz;
