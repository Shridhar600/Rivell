package com.rivell.workbench.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ContractParty;
import com.rivell.workbench.contracts.ContractReference;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.contracts.DefinedTerm;
import com.rivell.workbench.contracts.LegalFact;
import com.rivell.workbench.contracts.MetadataFact;
import com.rivell.workbench.contracts.SemanticRelationship;

/**
 * Deterministic digest of the effective semantic state a review plans against.
 * Analysis fails closed when the live semantics no longer match the snapshot digest,
 * so a re-enrichment or correction cannot silently change review inputs.
 */
final class SemanticsDigest {

    private SemanticsDigest() {
    }

    static String of(ContractSemantics semantics) {
        var canonical = new StringBuilder();
        canonical.append("v2\n");
        canonical.append("status=").append(semantics.enrichmentStatus()).append('\n');
        canonical.append("pipeline=").append(semantics.pipelineVersion()).append('\n');
        var profile = semantics.reviewProfile();
        canonical.append("profile=").append(profile.contractType()).append('|')
                .append(profile.ourSideRole()).append('|').append(profile.paperSource())
                .append('|').append(profile.jurisdiction()).append('|')
                .append(profile.contractPackKey()).append('\n');
        semantics.metadata().stream()
                .sorted(Comparator.comparing(MetadataFact::field))
                .forEach(fact -> canonical.append("metadata=").append(fact.field())
                        .append('|').append(fact.effectiveValue()).append('\n'));
        semantics.parties().stream()
                .sorted(Comparator.comparing(ContractParty::normalizedName))
                .forEach(party -> canonical.append("party=").append(party.normalizedName())
                        .append('|').append(party.effectiveRoles().stream()
                                .map(Enum::name).sorted().collect(Collectors.joining(",")))
                        .append('\n'));
        semantics.definedTerms().stream()
                .sorted(Comparator.comparing(DefinedTerm::term))
                .forEach(term -> canonical.append("term=").append(term.term())
                        .append('|').append(term.definition()).append('\n'));
        semantics.references().stream()
                .sorted(Comparator.comparing(ContractReference::sourceNodeId)
                        .thenComparing(reference -> reference.kind().name()))
                .forEach(reference -> canonical.append("reference=")
                        .append(reference.sourceNodeId()).append('|')
                        .append(reference.kind()).append('|')
                        .append(reference.status()).append('|')
                        .append(reference.targetNodeId()).append('\n'));
        semantics.clauses().stream()
                .sorted(Comparator.comparing(ClauseSemantics::headingNodeId))
                .forEach(clause -> {
                    canonical.append("clause=").append(clause.headingNodeId()).append('|')
                            .append(clause.effectivePrimaryConcept().orElse(null)).append('|')
                            .append(clause.secondaryConcepts().stream()
                                    .map(Enum::name).sorted()
                                    .collect(Collectors.joining(",")))
                            .append('|')
                            .append(clause.effectiveFacts().stream()
                                    .sorted(Comparator.comparing(LegalFact::factSchema))
                                    .map(SemanticsDigest::fact)
                                    .collect(Collectors.joining(";")))
                            .append('|').append(clause.contentText()).append('\n');
                });
        semantics.relationships().stream()
                .sorted(Comparator.comparing(SemanticRelationship::sourceRegionNodeId)
                        .thenComparing(SemanticRelationship::targetRegionNodeId)
                        .thenComparing(relationship -> relationship.type().name()))
                .forEach(relationship -> canonical.append("relationship=")
                        .append(relationship.sourceRegionNodeId()).append('|')
                        .append(relationship.targetRegionNodeId()).append('|')
                        .append(relationship.type()).append('\n'));
        return sha256(canonical.toString());
    }

    private static String fact(LegalFact fact) {
        return fact.factSchema() + ":" + fact.schemaVersion() + ":"
                + fact.values().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining(","));
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
