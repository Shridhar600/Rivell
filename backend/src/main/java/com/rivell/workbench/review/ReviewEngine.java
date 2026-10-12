package com.rivell.workbench.review;

/**
 * Which judgement path a review runs (engine v2 spec §5). {@code V1}: Jev decides every judged
 * position. {@code V2}: Jev decides, a position Jev is unsure about gets a second opinion, and a
 * disagreement goes to the user. Frozen in the review snapshot, so a review always replays with
 * the engine it was created with.
 */
public enum ReviewEngine { V1, V2 }
