package com.rivell.workbench.review;

/**
 * Which judgement path a review was created with (engine v2 spec §5). {@code V1}: Jev decided every
 * judged position. {@code V2}: Jev decides, a position Jev is unsure about gets a second opinion,
 * and a disagreement goes to the user. Frozen in the review snapshot as a record of how the review
 * was created (P6); analysis always runs the v2 path.
 */
public enum ReviewEngine { V1, V2 }
