-- Taxonomy v2. EQUITABLE_RELIEF was defined as a statement that a breach can be stopped by an
-- injunction, so a clause that takes that relief away ("the only remedy is damages") matched
-- no topic and was never reviewed (dev2 eval, 2026-10-12). A topic names a subject, not a stance.
update legal_concept
set definition = 'Whether a breach can be stopped by an injunction or specific performance without the injured party proving loss, including a clause that limits the remedies for a breach to damages.',
    aliases = aliases || ARRAY['Remedies', 'Remedies for Breach', 'Exclusive Remedy']::text[],
    taxonomy_version = 2
where concept_key = 'EQUITABLE_RELIEF';
