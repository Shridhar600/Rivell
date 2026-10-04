package com.rivell.workbench.review;

/**
 * How many Findings in one generation propose each kind of executable change.
 *
 * <p>These are Rivell's own proposals for orientation before anyone decides anything. They
 * are not counts of approved changes and must never be read as such: a proposal becomes an
 * approved change only when a human accepts or edits it, and the frozen ApprovedChangeSet is
 * the only record of that.
 *
 * <p>Only two proposal kinds exist, and both are read off the Finding itself. A Finding that
 * proposes adding an absent provision counts as an insertion, and a Finding on an existing
 * clause counts as a replacement when it actually carries replacement wording. A Finding
 * that escalates, reports ambiguity, or recommends no action proposes no executable change
 * and is counted in neither.
 *
 * <p>{@code delete} is always zero, and that is a fact rather than a gap. Deletion is not a
 * machine proposal anywhere in the domain: {@code ProposedAction} has no deleting member, and
 * {@code DecisionRules} derives a change type from the Finding only to choose between adding
 * a missing provision and replacing an existing clause. A deletion appears solely when a
 * human chooses one at decision time. Reporting a number here would be inventing a
 * proposal Rivell never made, so the field exists at zero to keep the shape stable and to
 * stop a consumer from deriving the figure some other way.
 *
 * <p>A Finding that escalates, reports ambiguity, or recommends no action proposes no
 * executable change and is counted in none of these numbers.
 */
public record ProposedChangeCounts(int replace, int insert, int delete) {

    public static final ProposedChangeCounts NONE = new ProposedChangeCounts(0, 0, 0);
}