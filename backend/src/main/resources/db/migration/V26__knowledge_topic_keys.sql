-- Topic sets on knowledge candidates (design 6a item 1): the classification members
-- (probability >= rivell.taxonomy.topic-threshold) plus the effective concept; a human
-- concept correction replaces the set with the corrected key. Membership resolution reads
-- this column, so it must serve the GIN operator class.
alter table knowledge_candidate add column topic_keys text[] not null default '{}';

-- Existing rows predate member persistence: the effective concept is all we can prove.
update knowledge_candidate
set topic_keys = case
    when effective_concept_key is null then '{}'::text[]
    else array[effective_concept_key]
end;

create index idx_knowledge_candidate_topic_keys on knowledge_candidate using gin (topic_keys);
