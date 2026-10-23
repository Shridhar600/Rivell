-- One-time knowledge data migrations record completion here so an idempotent startup reconciler
-- can prove it has already run in this database. This is platform bookkeeping, not knowledge
-- source truth: the rows it guards remain derived and rebuildable.
CREATE TABLE knowledge_data_migration (
    migration_key TEXT PRIMARY KEY,
    completed_at TIMESTAMPTZ NOT NULL,
    detail TEXT NOT NULL
);
