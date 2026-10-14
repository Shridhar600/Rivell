package com.rivell.workbench.review;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * Deterministic signal-to-Finding consolidation (spec §6/§7). Pure domain logic: no
 * model calls, no persistence. Signals group by
 * (subjectKind, subjectRef, issueFamily); sharing a clause or a related concept never
 * merges issues by itself (R3). Severity and classification come from a code-owned
 * table, never from model confidence.
 */
public final class FindingAssembler {

    public List<Finding> assemble(
            ReviewAnalysisRun run, List<RawReviewSignal> signals, Instant now) {
        var grouped = new LinkedHashMap<String, List<RawReviewSignal>>();
        for (var signal : signals) {
            grouped.computeIfAbsent(consolidationKey(signal), key -> new ArrayList<>())
                    .add(signal);
        }
        var findings = new ArrayList<Finding>();
        grouped.forEach((key, group) ->
                primary(group).ifPresent(signal -> findings.add(build(run, key, group, now))));
        return List.copyOf(findings);
    }

    private static String consolidationKey(RawReviewSignal signal) {
        var subject = signal.subjectKind() == FindingSubjectKind.CLAUSE
                ? signal.subjectHeadingNodeId().toString()
                : signal.absenceConcept().value();
        var key = signal.subjectKind().name() + ":" + subject + ":" + signal.issueFamily().name();
        // engine v2 omissions lane: two missing positions on one concept stay two findings
        if (signal.payload() instanceof OmissionPayload omission) {
            key = key + ":" + omission.candidateId();
        }
        return key;
    }

    private Finding build(
            ReviewAnalysisRun run, String key, List<RawReviewSignal> group, Instant now) {
        var primary = primary(group).orElseThrow();
        if (primary.subjectKind() == FindingSubjectKind.CLAUSE
                && evidence(group).isEmpty()) {
            throw new FindingAssemblyException(
                    "Clause-subject signal without evidence: " + primary.signalType()
                            + " for " + primary.concept());
        }
        ApprovedDraftingPayload drafting = firstPayload(group,
                SignalType.APPROVED_DRAFTING_AVAILABLE);
        EscalationPayload escalation = firstPayload(group, SignalType.ESCALATION_REQUIRED);
        OpenEndedAnalysisPayload openEnded = firstPayload(group,
                SignalType.OPEN_ENDED_ANALYSIS_RESULT);

        var observation = observation(primary);
        var action = action(primary, drafting, openEnded, escalation);
        var severity = severity(primary);
        var quality = quality(primary, group);
        var evidence = evidence(group);
        var concepts = concepts(group);

        String proposedLanguage = null;
        var languageSource = ProposedLanguageSource.NONE;
        UUID draftingEvidenceId = null;
        if (action == ProposedAction.USE_APPROVED_FALLBACK && drafting != null) {
            proposedLanguage = drafting.evidenceText();
            languageSource = ProposedLanguageSource.APPROVED_DRAFTING;
            draftingEvidenceId = drafting.draftingEvidenceId();
            evidence = distinct(evidence, List.of(drafting.draftingEvidenceId()));
        } else if (action == ProposedAction.CONSIDER_GENERATED_LANGUAGE
                && openEnded != null) {
            proposedLanguage = openEnded.proposedLanguage();
            languageSource = ProposedLanguageSource.GENERATIVE;
        }

        var claims = claims(primary, group, action, drafting, openEnded);

        return new Finding(
                UUID.randomUUID(), run.organizationId(), run.reviewId(), run.id(), key,
                primary.subjectKind(), primary.subjectHeadingNodeId(),
                primary.absenceConcept(), primary.concept(), primary.secondaryConcept(),
                primary.issueFamily(), observation.kind(), observation.text(),
                severity, classification(primary, escalation), action, proposedLanguage,
                languageSource, draftingEvidenceId, quality, now, concepts, claims,
                evidence, group.stream().map(RawReviewSignal::id).toList(), null);
    }

    /** The issue-bearing signal of a group; support-only groups yield no finding. */
    private static java.util.Optional<RawReviewSignal> primary(List<RawReviewSignal> group) {
        for (var type : List.of(SignalType.POLICY_DEVIATION, SignalType.CROSS_CLAUSE_CONFLICT,
                SignalType.MISSING_REQUIRED_CONCEPT, SignalType.EXPECTED_CONCEPT_ABSENT,
                SignalType.POLICY_UNAVAILABLE, SignalType.POLICY_AMBIGUOUS,
                SignalType.DEVIATION_UNCERTAIN, SignalType.CROSS_CLAUSE_UNCERTAIN,
                SignalType.OPEN_ENDED_ANALYSIS_RESULT)) {
            // a position on the clause's own topic outranks one on a topic it only touches
            var match = group.stream().filter(signal -> signal.signalType() == type)
                    .min(java.util.Comparator.comparing(FindingAssembler::secondaryTopic));
            if (match.isPresent()) {
                return match;
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean secondaryTopic(RawReviewSignal signal) {
        return signal.payload() instanceof PositionDeviationPayload deviation
                && deviation.secondaryTopic();
    }

    private record Observation(String kind, String text) {
    }

    private static Observation observation(RawReviewSignal signal) {
        if (signal.payload() instanceof OmissionPayload omission) {
            // engine v2 omissions lane: the code-owned text, never model prose
            return new Observation("MISSING_POSITION",
                    "Missing: " + omission.label() + ". Searched " + omission.searchedClauses()
                            + " clauses; none addresses it.");
        }
        if (signal.signalType() == SignalType.MISSING_REQUIRED_CONCEPT) {
            return new Observation("MISSING_REQUIRED_CONCEPT",
                    "The required provision " + signal.concept().value()
                            + " is missing from this contract.");
        }
        if (signal.signalType() == SignalType.EXPECTED_CONCEPT_ABSENT) {
            return new Observation("EXPECTED_CONCEPT_ABSENT",
                    "The expected provision " + signal.concept().value()
                            + " was not found in this contract.");
        }
        if (signal.signalType() == SignalType.POLICY_UNAVAILABLE
                && signal.payload() instanceof PolicyUnavailablePayload unavailable
                && PolicyUnavailablePayload.NOT_COVERED.equals(unavailable.sufficiency())) {
            return new Observation(PolicyUnavailablePayload.NOT_COVERED,
                    "No playbook position addresses this clause, and it reads as unusual for "
                            + "this kind of contract and against our side; a lawyer should "
                            + "review it.");
        }
        if (signal.signalType() == SignalType.POLICY_UNAVAILABLE
                && signal.payload() instanceof PolicyUnavailablePayload unavailable
                && PolicyUnavailablePayload.SPOTTED.equals(unavailable.sufficiency())) {
            return new Observation(PolicyUnavailablePayload.SPOTTED,
                    "A whole-contract read named this clause as one our side must act on, and no "
                            + "playbook position check flagged it; a lawyer should review it.");
        }
        if (signal.signalType() == SignalType.POLICY_UNAVAILABLE) {
            return new Observation("POLICY_UNAVAILABLE",
                    "No applicable published policy exists for " + signal.concept().value()
                            + "; no deviation judgment was made and precedent does not "
                            + "substitute for policy.");
        }
        if (signal.signalType() == SignalType.POLICY_AMBIGUOUS) {
            return new Observation("AMBIGUOUS_POLICY",
                    "Multiple conflicting published policies apply to "
                            + signal.concept().value()
                            + "; no rule was chosen and the ambiguity is preserved.");
        }
        if (signal.signalType() == SignalType.POLICY_DEVIATION
                && signal.payload() instanceof PositionDeviationPayload deviation) {
            return new Observation(observationKind(deviation.category()),
                    observationText(deviation.category()));
        }
        if (signal.signalType() == SignalType.CROSS_CLAUSE_UNCERTAIN
                && signal.payload() instanceof DeviationUncertainPayload uncertain) {
            return new Observation("UNCERTAINTY:" + uncertain.reason(),
                    uncertaintyText(uncertain.reason(), signal));
        }
        if (signal.signalType() == SignalType.CROSS_CLAUSE_CONFLICT
                && signal.payload() instanceof CrossClauseConflictPayload conflict) {
            if ("MISSING_SURVIVAL_COVERAGE".equals(conflict.conflictType())) {
                return new Observation("SURVIVAL_COVERAGE_GAP",
                        "Confidentiality obligations are not covered by a survival "
                                + "provision.");
            }
            return new Observation("CARVE_OUT_INCONSISTENCY",
                    "The liability carve-out and the indemnification clause contradict "
                            + "each other about whether the liability cap applies to "
                            + "indemnification.");
        }
        if (signal.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT
                && signal.payload() instanceof OpenEndedAnalysisPayload payload) {
            var verified = "VERIFIED".equals(payload.verificationOutcome());
            return new Observation("OPEN_ENDED_ANALYSIS_RESULT",
                    verified
                            ? "The clause cannot be compared with policy and no approved "
                                    + "drafting exists; an independently verified machine "
                                    + "candidate is available for human review."
                            : "The clause cannot be compared with policy; the machine "
                                    + "candidate was not verified and is not presented as "
                                    + "acceptable (" + payload.verificationOutcome() + ").");
        }
        if (signal.payload() instanceof DeviationUncertainPayload uncertain) {
            return new Observation("UNCERTAINTY:" + uncertain.reason(),
                    uncertaintyText(uncertain.reason(), signal));
        }
        throw new IllegalStateException(
                "No observation template for signal " + signal.signalType());
    }

    private static String uncertaintyText(String reason, RawReviewSignal signal) {
        return switch (reason) {
            case "classification_uncertain" -> "The bounded classifier did not reach a "
                    + "clear classification for this clause; the uncertainty is recorded "
                    + "rather than a deviation judgment.";
            case "bounded_decision_unavailable", "cross_clause_decision_unavailable" ->
                    "The bounded semantic decision was unavailable; the clause was not "
                            + "classified and requires human review.";
            case "carve_out_context_too_large" -> "The cross-clause context exceeds the "
                    + "bounded prompt; the interaction was not assessed.";
            case "carve_out_not_comparable" -> "The bounded classifier could not determine "
                    + "how the liability carve-out and the indemnification clause interact; "
                    + "no conflict or consistency was asserted.";
            case "cross_clause_classification_uncertain" -> "The cross-clause classification "
                    + "did not reach a clear verdict; the interaction requires human review.";
            case "survival_coverage_partial" -> "Survival coverage could not be established "
                    + "for all confidentiality provisions; the limitation requires human "
                    + "review.";
            case DeviationUncertainPayload.SECOND_OPINION_DISAGREES -> "Rivell's two reviewers "
                    + "disagree on this clause against the position: one finds an issue, the "
                    + "other does not. Rivell does not decide; please take a look.";
            case DeviationUncertainPayload.SECOND_OPINION_UNAVAILABLE -> "Rivell was unsure about "
                    + "this clause against the position, and a second opinion could not be "
                    + "obtained; please take a look.";
            default -> "The check recorded unresolved uncertainty for "
                    + signal.concept().value() + ".";
        };
    }

    private static String observationKind(String category) {
        return switch (category) {
            case PolicyComparisonSpecs.WALK_AWAY -> "POSITION_WALK_AWAY";
            case PolicyComparisonSpecs.BEYOND_FALLBACK -> "POSITION_BEYOND_FALLBACK";
            case PolicyComparisonSpecs.WITHIN_FALLBACK -> "POSITION_WITHIN_FALLBACK";
            default -> "POSITION_UNCLEAR";
        };
    }

    private static String observationText(String category) {
        return switch (category) {
            case PolicyComparisonSpecs.WALK_AWAY -> "The clause contains or permits what "
                    + "the position's walk-away says we must never accept.";
            case PolicyComparisonSpecs.BEYOND_FALLBACK -> "The clause is worse for us than "
                    + "every fallback the position lists.";
            case PolicyComparisonSpecs.WITHIN_FALLBACK -> "The clause departs from the "
                    + "preferred position but stays within a listed fallback; accept and note.";
            default -> "The clause deals with this subject, but its wording is ambiguous or "
                    + "depends on something outside the contract, so compliance cannot be "
                    + "decided.";
        };
    }

    private static ProposedAction action(
            RawReviewSignal primary, ApprovedDraftingPayload drafting,
            OpenEndedAnalysisPayload openEnded, EscalationPayload escalation) {
        if (primary.signalType() == SignalType.MISSING_REQUIRED_CONCEPT) {
            return ProposedAction.ADD_REQUIRED_PROVISION;
        }
        if (primary.signalType() == SignalType.EXPECTED_CONCEPT_ABSENT) {
            return ProposedAction.NO_ACTION;
        }
        if (primary.signalType() == SignalType.POLICY_DEVIATION
                && primary.payload() instanceof PositionDeviationPayload deviation) {
            var category = deviation.category();
            if (PolicyComparisonSpecs.WITHIN_FALLBACK.equals(category)) {
                return escalation != null
                        ? ProposedAction.ESCALATE_TO_HUMAN : ProposedAction.NO_ACTION;
            }
            if (PolicyComparisonSpecs.UNCLEAR.equals(category)) {
                return ProposedAction.REVIEW_AMBIGUITY;
            }
            if (drafting != null) {
                return ProposedAction.USE_APPROVED_FALLBACK;
            }
            if (openEnded != null && "VERIFIED".equals(openEnded.verificationOutcome())) {
                return ProposedAction.CONSIDER_GENERATED_LANGUAGE;
            }
            return ProposedAction.ESCALATE_TO_HUMAN;
        }
        if (primary.signalType() == SignalType.OPEN_ENDED_ANALYSIS_RESULT
                && openEnded != null && "VERIFIED".equals(openEnded.verificationOutcome())) {
            return ProposedAction.CONSIDER_GENERATED_LANGUAGE;
        }
        if (primary.signalType() == SignalType.POLICY_UNAVAILABLE) {
            return ProposedAction.ESCALATE_TO_HUMAN;
        }
        if (primary.signalType() == SignalType.POLICY_AMBIGUOUS) {
            return ProposedAction.REVIEW_AMBIGUITY;
        }
        return ProposedAction.ESCALATE_TO_HUMAN;
    }

    private static FindingSeverity severity(RawReviewSignal primary) {
        if (primary.payload() instanceof OmissionPayload) {
            // engine v2 omissions lane: a required absence is an alarm; an unsure one asks for a look
            return "NEEDS_YOUR_LOOK".equals(primary.quality().get("route"))
                    ? FindingSeverity.LOW : FindingSeverity.MEDIUM;
        }
        if (primary.signalType() == SignalType.MISSING_REQUIRED_CONCEPT) {
            return FindingSeverity.HIGH;
        }
        if (primary.signalType() == SignalType.EXPECTED_CONCEPT_ABSENT) {
            return FindingSeverity.LOW;
        }
        if (primary.signalType() == SignalType.POLICY_UNAVAILABLE
                && primary.payload() instanceof PolicyUnavailablePayload unavailable
                && PolicyUnavailablePayload.SPOTTED.equals(unavailable.sufficiency())
                && "NEEDS_YOUR_LOOK".equals(primary.quality().get("route"))) {
            // engine v2: an advisory pick of the whole-contract read asks for a look, never an alarm
            return FindingSeverity.LOW;
        }
        if (primary.signalType() == SignalType.POLICY_DEVIATION
                && primary.payload() instanceof PositionDeviationPayload deviation) {
            // severity follows the category; a named approver changes the action, not the risk;
            // a position on a topic the clause only touches is a note
            if (deviation.secondaryTopic()) {
                return FindingSeverity.LOW;
            }
            return switch (deviation.category()) {
                case PolicyComparisonSpecs.WALK_AWAY -> FindingSeverity.HIGH;
                case PolicyComparisonSpecs.WITHIN_FALLBACK -> FindingSeverity.LOW;
                default -> FindingSeverity.MEDIUM;
            };
        }
        if (primary.signalType() == SignalType.CROSS_CLAUSE_CONFLICT) {
            return FindingSeverity.HIGH;
        }
        if (primary.payload() instanceof DeviationUncertainPayload uncertain
                && List.of(DeviationUncertainPayload.SECOND_OPINION_DISAGREES,
                        DeviationUncertainPayload.SECOND_OPINION_UNAVAILABLE)
                        .contains(uncertain.reason())) {
            // engine v2: a walk-away or beyond-fallback second opinion raises the look item to MEDIUM
            var secondOpinionCategory = primary.quality().get("secondOpinionCategory");
            return DeviationUncertainPayload.SECOND_OPINION_DISAGREES.equals(uncertain.reason())
                    && (PolicyComparisonSpecs.WALK_AWAY.equals(secondOpinionCategory)
                            || PolicyComparisonSpecs.BEYOND_FALLBACK
                                    .equals(secondOpinionCategory))
                    ? FindingSeverity.MEDIUM : FindingSeverity.LOW;
        }
        return FindingSeverity.MEDIUM;
    }

    private static FindingClassification classification(
            RawReviewSignal primary, EscalationPayload escalation) {
        if (primary.payload() instanceof OmissionPayload) {
            // engine v2 omissions lane: a missing position needs review, it does not block
            return FindingClassification.REVIEW_REQUIRED;
        }
        if (primary.signalType() == SignalType.MISSING_REQUIRED_CONCEPT) {
            return FindingClassification.BLOCKING;
        }
        if (primary.signalType() == SignalType.EXPECTED_CONCEPT_ABSENT) {
            return FindingClassification.INFORMATIONAL;
        }
        if (primary.signalType() == SignalType.POLICY_DEVIATION
                && primary.payload() instanceof PositionDeviationPayload deviation) {
            if (deviation.secondaryTopic()) {
                return FindingClassification.INFORMATIONAL;
            }
            if (PolicyComparisonSpecs.WALK_AWAY.equals(deviation.category())) {
                return FindingClassification.BLOCKING;
            }
            if (PolicyComparisonSpecs.WITHIN_FALLBACK.equals(deviation.category())) {
                return escalation == null
                        ? FindingClassification.INFORMATIONAL
                        : FindingClassification.REVIEW_REQUIRED;
            }
        }
        return FindingClassification.REVIEW_REQUIRED;
    }

    private static List<FindingClaim> claims(
            RawReviewSignal primary, List<RawReviewSignal> group, ProposedAction action,
            ApprovedDraftingPayload drafting, OpenEndedAnalysisPayload openEnded) {
        var claims = new ArrayList<FindingClaim>();
        var observation = observation(primary);
        var evidence = evidence(group);
        // absence-subject findings are grounded in the plan-check/absence identity
        var absenceSubject = primary.subjectKind() == FindingSubjectKind.ABSENCE;
        if (absenceSubject) {
            var basis = primary.payload() instanceof OmissionPayload
                    ? "No clause in this contract addresses this playbook position; the "
                            + "absence is the observation."
                    : "No clause in this contract carries " + primary.absenceConcept().value()
                            + "; the absence is the observation.";
            claims.add(FindingClaim.of(0, ClaimKind.ABSENCE_BASIS, basis, true, List.of()));
        } else {
            claims.add(FindingClaim.of(0, ClaimKind.OBSERVATION, observation.text(),
                    false, evidence));
        }
        if (primary.signalType() == SignalType.POLICY_DEVIATION) {
            claims.add(FindingClaim.of(1, ClaimKind.POLICY_BASIS,
                    "The assessment is based on the applicable published organizational "
                            + "policy resolved for this review; precedent is evidence, "
                            + "never policy.",
                    absenceSubject, evidence));
        } else if (primary.signalType() == SignalType.POLICY_UNAVAILABLE) {
            claims.add(FindingClaim.of(1, ClaimKind.POLICY_BASIS,
                    "No published policy applies to this concept; the finding records "
                            + "that absence and does not infer policy. Precedent is "
                            + "evidence, never policy.",
                    absenceSubject, evidence));
        } else if (primary.signalType() == SignalType.POLICY_AMBIGUOUS) {
            claims.add(FindingClaim.of(1, ClaimKind.POLICY_BASIS,
                    "Multiple applicable published rules conflict for this concept; no "
                            + "rule was selected. Precedent is evidence, never policy.",
                    absenceSubject, evidence));
        }
        if (drafting != null && action == ProposedAction.USE_APPROVED_FALLBACK) {
            var preferredOnly = "PREFERRED".equals(drafting.variantRole());
            claims.add(FindingClaim.of(2, ClaimKind.EVIDENCE_BASIS,
                    preferredOnly
                            ? "Approved drafting is available in the clause library; the "
                                    + "preferred variant is proposed because no fallback "
                                    + "variant is published."
                            : "Approved drafting is available in the clause library and is "
                                    + "proposed as the acceptable second position.",
                    absenceSubject, List.of(drafting.draftingEvidenceId())));
        }
        if (openEnded != null
                && "VERIFIED".equals(openEnded.verificationOutcome())) {
            claims.add(FindingClaim.of(claims.size(), ClaimKind.GENERATIVE_EXPLANATION,
                    openEnded.explanation(), absenceSubject, evidence));
        }
        claims.add(FindingClaim.of(claims.size(), ClaimKind.RECOMMENDATION,
                recommendation(action), absenceSubject, evidence));
        var ordinals = new int[claims.size()];
        for (var index = 0; index < claims.size(); index++) {
            ordinals[index] = claims.get(index).ordinal();
        }
        for (var index = 0; index < ordinals.length; index++) {
            if (ordinals[index] != index) {
                throw new FindingAssemblyException("Claim ordinals are not sequential");
            }
        }
        return List.copyOf(claims);
    }

    private static String recommendation(ProposedAction action) {
        return switch (action) {
            case USE_APPROVED_FALLBACK -> "Recommend replacing the clause with the "
                    + "approved fallback wording.";
            case ADD_REQUIRED_PROVISION -> "Recommend adding the required provision before "
                    + "signature.";
            case REVIEW_AMBIGUITY -> "Recommend human legal review to resolve the policy "
                    + "ambiguity before any change.";
            case ESCALATE_TO_HUMAN -> "Recommend escalation to the responsible legal "
                    + "reviewer; no machine-approved change is proposed.";
            case CONSIDER_GENERATED_LANGUAGE -> "Recommend human review of the machine "
                    + "candidate; it is not approved language.";
            case NO_ACTION -> "No action proposed; this is an informational observation.";
        };
    }

    /** The deciding signal's own assessment, shown with the finding (model estimates). */
    private static final List<String> ASSESSMENT_KEYS = List.of("category", "flagProbability",
            "probabilities", "secondaryTopic", "uncoveredProbability", "route", "jevCategory",
            "secondOpinionOutcome", "secondOpinionCategory", "secondOpinionQuote",
            "secondOpinionReason", "foundBy", "spottedSeverity", "spottedReason",
            "uncertaintyReason", "searchedClauses", "comparedClauses", "presenceProbability",
            "positionLabel");

    private static Map<String, Object> quality(RawReviewSignal primary, List<RawReviewSignal> group) {
        var quality = new LinkedHashMap<String, Object>();
        for (var key : ASSESSMENT_KEYS) {
            var value = primary.quality().get(key);
            if (value != null) {
                quality.put(key, value);
            }
        }
        var flags = new LinkedHashSet<String>();
        for (var signal : group) {
            var sufficiency = signal.quality().get("sufficiency");
            if (sufficiency instanceof String value) {
                quality.merge("sufficiency", value,
                        (left, right) -> worseSufficiency((String) left, (String) right));
            }
            var verification = signal.verificationOutcome() != null
                    ? signal.verificationOutcome()
                    : signal.quality().get("verificationOutcome");
            if (verification instanceof String value) {
                quality.put("verificationOutcome", value);
            }
            var degraded = signal.quality().get("degradedReason");
            if (degraded != null) {
                flags.add(degraded.toString());
            }
            var uncertain = signal.quality().get("uncertaintyReason");
            if (uncertain != null) {
                flags.add(uncertain.toString());
            }
        }
        if (!flags.isEmpty()) {
            quality.put("degradationFlags", List.copyOf(flags));
        }
        return quality;
    }

    private static String worseSufficiency(String left, String right) {
        var order = List.of("SUFFICIENT", "PARTIAL", "PRECEDENT_WEAK", "DRAFTING_MISSING",
                "CONTRACT_CONTEXT_INCOMPLETE", "SOURCE_CONFLICT", "POLICY_MISSING",
                "AMBIGUOUS_POLICY");
        return order.indexOf(left) >= order.indexOf(right) ? left : right;
    }

    private static List<ConceptKey> concepts(List<RawReviewSignal> group) {
        var concepts = new LinkedHashSet<ConceptKey>();
        for (var signal : group) {
            concepts.add(signal.concept());
            if (signal.secondaryConcept() != null) {
                concepts.add(signal.secondaryConcept());
            }
        }
        return List.copyOf(concepts);
    }

    private static List<UUID> evidence(List<RawReviewSignal> group) {
        var ids = new LinkedHashSet<UUID>();
        for (var signal : group) {
            ids.addAll(signal.evidenceItemIds());
        }
        return List.copyOf(ids);
    }

    private static List<UUID> distinct(List<UUID> first, List<UUID> second) {
        var ids = new LinkedHashSet<UUID>(first);
        ids.addAll(second);
        return List.copyOf(ids);
    }

    @SuppressWarnings("unchecked")
    private static <T> T firstPayload(List<RawReviewSignal> group, SignalType type) {
        return (T) group.stream().filter(signal -> signal.signalType() == type)
                .map(RawReviewSignal::payload).findFirst().orElse(null);
    }
}
