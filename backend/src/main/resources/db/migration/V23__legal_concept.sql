-- Legal topics shared by contract clauses, playbook positions and review checks.
-- Parents group leaves; only leaves are classified against. Seeded in V24.
create table legal_concept (
    concept_key text primary key,
    label text not null,
    parent_key text references legal_concept (concept_key),
    definition text,
    aliases text[] not null default '{}',
    contract_types text[] not null default '{}',
    sort_order integer not null unique,
    taxonomy_version integer not null,
    check (concept_key ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    check (parent_key is null or definition is not null)
);
