-- Knowledge candidate human LegalConcept correction (DW-017).
-- Same layering proven in M5 contracts: machine concept is never erased;
-- a human correction (set or clear) defines the effective concept that
-- publication/retrieval consume. Existing published history is untouched.
alter table knowledge_candidate add column concept_correction text;
alter table knowledge_candidate add column concept_corrected_by uuid;

-- single derived source of truth for the effective concept:
-- no correction -> machine concept; blank correction -> unclassified; else the
-- human-corrected key. Generated + stored so indexes and retrieval read one column.
alter table knowledge_candidate add column effective_concept_key text
    generated always as (
        case
            when concept_correction is null then legal_concept_key
            when concept_correction = '' then null
            else concept_correction
        end
    ) stored;

-- retrieval applicability index now targets the effective concept
drop index idx_knowledge_candidate_retrieval;
create index idx_knowledge_candidate_retrieval on knowledge_candidate
    (organization_id, review_status, effective_concept_key);
