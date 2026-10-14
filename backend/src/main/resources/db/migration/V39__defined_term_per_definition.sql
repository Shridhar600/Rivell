-- DW-080: one row per definition. A contract may define the same term more than once
-- (recitals and the definitions clause); every definition is kept and flagged, so the old
-- one-row-per-term key is replaced by one that allows a definition per defining node.
ALTER TABLE contract_defined_term
    DROP CONSTRAINT contract_defined_term_semantics_id_term_key;

-- Postgres names the replacement contract_defined_term_semantics_id_term_defining_node_id_key.
ALTER TABLE contract_defined_term
    ADD UNIQUE (semantics_id, term, defining_node_id);
