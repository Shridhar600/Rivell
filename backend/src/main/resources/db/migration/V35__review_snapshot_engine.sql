-- Engine v2 P2: the review engine is frozen per review (V1 Jev only; V2 Jev with a second
-- opinion when Jev is unsure), so a review always replays with the engine it was created with.
-- Reviews created before this migration ran on V1.
ALTER TABLE review_snapshot
    ADD COLUMN review_engine TEXT NOT NULL DEFAULT 'V1'
        CHECK (review_engine IN ('V1', 'V2'));
