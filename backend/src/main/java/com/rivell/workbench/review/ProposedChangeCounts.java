package com.rivell.workbench.review;

/**
 * What the counted generation of a review actually proposes, per change kind.
 *
 * <p>These are Rivell's own proposals, for orientation before anyone decides. They are not
 * counts of approved changes and must never be read as such: a proposal becomes an approved
 * change only when a human accepts or edits it, and the frozen ApprovedChangeSet is the only
 * record of that.
 *
 * <p>The three numbers are not the same kind of fact, and reading them as if they were would
 * misstate what Rivell can do:
 *
 * <ul>
 *   <li>{@code replace} — Findings on an existing clause that carry replacement wording Rivell
 *       proposes. This is the only genuinely executable proposal in the system: it comes from
 *       approved organizational drafting or from generated language the domain verified, and
 *       accepting such a Finding yields a REPLACE change without a human writing anything.
 *   <li>{@code insert} — Findings that a required provision is absent from the contract.
 *       Rivell detects the gap; it proposes <em>no wording for it</em>, so accepting such a
 *       Finding is refused and a human must author the clause. This counts detected gaps, not
 *       drafted insertions.
 *   <li>{@code delete} — always zero. Deletion is never a machine proposal anywhere in the
 *       domain: {@code ProposedAction} has no deleting member, and {@code DecisionRules}
 *       derives a change type from a Finding only to choose between adding a missing provision
 *       and replacing an existing clause. A deletion appears solely when a human chooses one
 *       at decision time, so the field exists at zero to keep the shape stable and to stop a
 *       consumer deriving the figure some other way.
 * </ul>
 *
 * <p>A Finding that escalates, reports ambiguity, or recommends no action proposes nothing and
 * is counted in none of these numbers.
 */
public record ProposedChangeCounts(int replace, int insert, int delete) {

    public static final ProposedChangeCounts NONE = new ProposedChangeCounts(0, 0, 0);
}

