package com.rivell.workbench.contracts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * The M5 semantic enrichment engine. Deterministic extraction first; then bounded
 * semantic stages (one batched execution per region/party/pair) and typed fact
 * extraction. Runs with no open transaction — the service owns lifecycle and
 * persistence. Model fan-out is budgeted: over budget is deterministic degradation
 * with a quality reason, never silent unbounded calls.
 */
public final class ContractSemanticEnricher {

    private final SemanticDecisionService decisionService;
    private final ContractFactExtractor factExtractor;
    private final ContractEnrichmentProperties properties;

    public ContractSemanticEnricher(
            SemanticDecisionService decisionService,
            ContractFactExtractor factExtractor,
            ContractEnrichmentProperties properties) {
        this.decisionService = decisionService;
        this.factExtractor = factExtractor;
        this.properties = properties;
    }

    public record Result(
            List<MetadataFact> metadata,
            List<ContractParty> parties,
            List<DefinedTerm> definedTerms,
            List<ContractReference> references,
            List<ClauseSemantics> clauses,
            List<SemanticRelationship> relationships,
            QualityReasons quality,
            Observations observations) {
    }

    public Result enrich(List<DocumentNode> nodes, ReviewProfile profile,
            List<String> parseWarnings) {
        var segmenter = new ContractRegionSegmenter();
        var extractors = new DeterministicContractExtractors();
        var versionId = nodes.get(0).ownerAggregateId();

        var regions = segmenter.segment(nodes);
        var quality = new LinkedHashSet<ReviewQualityNote>();

        var metadata = new ArrayList<>(extractors.metadata(versionId, regions, nodes));
        metadata.add(contractTypeFact(versionId, profile));
        List<ContractParty> parties = new ArrayList<>(extractors.parties(versionId, regions, nodes));
        var definedTerms = new ArrayList<>(extractors.definedTerms(versionId, regions, nodes));
        var references = new ArrayList<>(extractors.references(versionId, regions, nodes));

        var counters = new ExecutionCounters();
        var regionBudget = properties.maxRegions();
        var clausesByHeading = new LinkedHashMap<UUID, ContractRegionSegmenter.Region>();
        var clauses = new ArrayList<ClauseSemantics>();

        var classifications = new LinkedHashMap<UUID, ConceptClassification>();
        for (var region : regions) {
            if (region.isPreamble()) {
                continue;
            }
            if (regionBudget-- <= 0) {
                quality.add(ReviewQualityNote.BUDGET_EXHAUSTED);
                counters.budgetExhausted = true;
                break;
            }
            try {
                var outcome = classifyConcepts(region);
                counters.decisionExecutions++;
                counters.questions += LegalConcept.values().length;
                classifications.put(region.headingNodeId(),
                        withPrimary(region, outcome, counters, quality));
            } catch (SemanticDecisionException e) {
                counters.providerDegraded = true;
                classifications.put(region.headingNodeId(),
                        new ConceptClassification(List.of(), List.of(), Map.of()));
            }
        }

        for (var region : regions) {
            if (region.isPreamble()) {
                continue;
            }
            clausesByHeading.put(region.headingNodeId(), region);
            var classification = classifications.getOrDefault(region.headingNodeId(),
                    new ConceptClassification(List.of(), List.of(), Map.of()));
            if (classification.concepts().isEmpty()) {
                quality.add(ReviewQualityNote.UNCLASSIFIED_REGIONS);
            } else if (classification.primaryConcept().isEmpty()) {
                quality.add(ReviewQualityNote.NO_PRIMARY_CONCEPT);
            }
            var factOutcome = extractFacts(region, classification, counters, quality);
            clauses.add(new ClauseSemantics(UUID.randomUUID(), versionId,
                    region.headingNodeId(), region.textNodeIds(), region.contentText(),
                    classification, factOutcome.facts(), factOutcome.interpretations(),
                    FactCorrection.NONE, null));
        }

        parties = classifyPartyRoles(parties, regions, profile, counters, quality);

        var relationships = analyzeRelationships(clauses, references, clausesByHeading,
                counters, quality);

        composeQuality(quality, definedTerms, references, parties, clauses);
        if (counters.providerDegraded) {
            quality.add(ReviewQualityNote.PROVIDER_DEGRADATION);
        }
        if (parseWarnings != null && !parseWarnings.isEmpty()) {
            quality.add(ReviewQualityNote.PARSER_WARNINGS_PRESENT);
        }

        var observations = new Observations(
                regions.size(), counters.decisionExecutions, counters.questions,
                counters.generative, counters.deterministicFacts,
                counters.providerDegraded, counters.budgetExhausted);
        return new Result(metadata, parties, definedTerms, references, clauses,
                relationships, QualityReasons.of(List.copyOf(quality)), observations);
    }

    private static final class ExecutionCounters {
        int decisionExecutions;
        int questions;
        int generative;
        int deterministicFacts;
        boolean providerDegraded;
        boolean budgetExhausted;
    }

    private record ConceptOutcome(
            List<LegalConcept> members, Map<LegalConcept, Double> probabilities) {
    }

    private ConceptOutcome classifyConcepts(ContractRegionSegmenter.Region region)
            throws SemanticDecisionException {
        var execution = decisionService.evaluate(M5SemanticSpecs.clauseConcepts(),
                new M5SemanticStates.ClauseState(region.headingText(), region.contentText()));
        var members = new ArrayList<LegalConcept>();
        var probabilities = new LinkedHashMap<LegalConcept, Double>();
        for (var concept : LegalConcept.values()) {
            var answer = execution.answers().get(M5SemanticSpecs.conceptQuestionName(concept));
            if (answer instanceof YesNoAnswer yesNo) {
                probabilities.put(concept, yesNo.probability());
                if (yesNo.probability() >= properties.conceptThreshold()) {
                    members.add(concept);
                }
            } else {
                throw new ProviderProtocolException(
                        "Missing concept answer " + M5SemanticSpecs.conceptQuestionName(concept));
            }
        }
        return new ConceptOutcome(members, probabilities);
    }

    private ConceptClassification withPrimary(
            ContractRegionSegmenter.Region region, ConceptOutcome outcome,
            ExecutionCounters counters, LinkedHashSet<ReviewQualityNote> quality)
            throws SemanticDecisionException {
        var members = outcome.members();
        if (members.size() <= 1) {
            return new ConceptClassification(members, List.of(), outcome.probabilities());
        }
        var execution = decisionService.evaluate(
                M5SemanticSpecs.primaryConcept(members),
                new M5SemanticStates.PrimaryConceptState(region.headingText(),
                        region.contentText(), namesOf(members)));
        counters.decisionExecutions++;
        counters.questions += 1;
        if (!(execution.answers().get("primary_concept") instanceof ChoiceAnswer answer)) {
            throw new ProviderProtocolException("Primary concept answer missing");
        }
        var selected = parseConcept(answer.selected());
        var probability = answer.probabilities().getOrDefault(answer.selected(), 0.0);
        var secondaries = members.stream()
                .filter(concept -> concept != selected)
                .toList();
        if (probability >= properties.primaryConceptThreshold() && clearWinner(answer)) {
            return new ConceptClassification(members, secondaries, outcome.probabilities());
        }
        quality.add(ReviewQualityNote.NO_PRIMARY_CONCEPT);
        return new ConceptClassification(members, List.copyOf(members), outcome.probabilities());
    }

    private LegalFactOutcome extractFacts(
            ContractRegionSegmenter.Region region, ConceptClassification classification,
            ExecutionCounters counters, LinkedHashSet<ReviewQualityNote> quality) {
        var facts = new LinkedHashMap<String, LegalFact>();
        var interpretations = new LinkedHashMap<String, Interpretation>();
        for (var concept : classification.concepts()) {
            if (concept != LegalConcept.LIMITATION_OF_LIABILITY
                    && concept != LegalConcept.TERM_TERMINATION) {
                continue;
            }
            var factResult = factExtractor.extract(concept, region.headingText(),
                    region.contentText(), List.of(region.headingNodeId()));
            for (var fact : factResult.facts()) {
                facts.putIfAbsent(fact.factSchema(), fact);
                counters.deterministicFacts++;
            }
            for (var entry : factResult.interpretations().entrySet()) {
                interpretations.putIfAbsent(entry.getKey(), entry.getValue());
                if (entry.getValue().kind() == InterpretationKind.GENERATIVE_EXTRACTION) {
                    if (entry.getValue().proposedValue().equals("UNAVAILABLE")) {
                        counters.providerDegraded = true;
                    } else {
                        counters.generative++;
                    }
                }
            }
        }
        return new LegalFactOutcome(List.copyOf(facts.values()),
                List.copyOf(interpretations.values()));
    }

    private record LegalFactOutcome(
            List<LegalFact> facts, List<Interpretation> interpretations) {
    }

    private List<ContractParty> classifyPartyRoles(
            List<ContractParty> parties, List<ContractRegionSegmenter.Region> regions,
            ReviewProfile profile, ExecutionCounters counters,
            LinkedHashSet<ReviewQualityNote> quality) {
        var preambleText = regions.stream()
                .filter(ContractRegionSegmenter.Region::isPreamble)
                .map(ContractRegionSegmenter.Region::contentText)
                .findFirst()
                .orElse("");
        var roles = M5SemanticSpecs.partyRoleSet(profile.contractType());
        var classified = new ArrayList<ContractParty>(parties.size());
        for (var party : parties) {
            if (!isFallbackOnly(party.machineRoles())) {
                classified.add(party);
                continue;
            }
            try {
                var execution = decisionService.evaluate(
                        M5SemanticSpecs.partyRoles(roles),
                        new M5SemanticStates.PartyRoleState(party.rawName(),
                                profile.contractType().name(),
                                profile.ourSideRole() == null ? null
                                        : profile.ourSideRole().name(),
                                excerptOf(preambleText, party.rawName())));
                counters.decisionExecutions++;
                counters.questions += roles.size();
                var machineRoles = new ArrayList<PartyRole>();
                for (var role : roles) {
                    var answer = execution.answers().get(M5SemanticSpecs.roleQuestionName(role));
                    if (answer instanceof YesNoAnswer yesNo
                            && yesNo.probability() >= properties.roleThreshold()) {
                        machineRoles.add(role);
                    }
                }
                if (machineRoles.isEmpty()) {
                    quality.add(ReviewQualityNote.AMBIGUOUS_PARTY_ROLES);
                    classified.add(party);
                } else {
                    classified.add(party.withMachineRoles(machineRoles));
                }
            } catch (SemanticDecisionException e) {
                counters.providerDegraded = true;
                quality.add(ReviewQualityNote.AMBIGUOUS_PARTY_ROLES);
                classified.add(party);
            }
        }
        return classified;
    }

    private List<SemanticRelationship> analyzeRelationships(
            List<ClauseSemantics> clauses, List<ContractReference> references,
            Map<UUID, ContractRegionSegmenter.Region> regionsByHeading,
            ExecutionCounters counters, LinkedHashSet<ReviewQualityNote> quality) {
        var regionOfNode = new HashMap<UUID, UUID>();
        for (var clause : clauses) {
            regionOfNode.put(clause.headingNodeId(), clause.headingNodeId());
            for (var textNode : clause.textNodeIds()) {
                regionOfNode.put(textNode, clause.headingNodeId());
            }
        }

        var pairs = new LinkedHashSet<RegionPair>();
        for (var reference : references) {
            if (!reference.resolved()) {
                continue;
            }
            var sourceRegion = regionOfNode.get(reference.sourceNodeId());
            var targetRegion = regionOfNode.get(reference.targetNodeId());
            if (sourceRegion == null || targetRegion == null
                    || sourceRegion.equals(targetRegion)) {
                continue;
            }
            pairs.add(new RegionPair(sourceRegion, targetRegion));
        }

        var relationships = new ArrayList<SemanticRelationship>();
        var budget = properties.maxRelationshipPairs();
        for (var pair : pairs) {
            if (budget-- <= 0) {
                quality.add(ReviewQualityNote.BUDGET_EXHAUSTED);
                counters.budgetExhausted = true;
                break;
            }
            var sourceClause = clauseByHeading(clauses, pair.source());
            var targetClause = clauseByHeading(clauses, pair.target());
            if (sourceClause.isEmpty() || targetClause.isEmpty()) {
                continue;
            }
            try {
                var execution = decisionService.evaluate(
                        M5SemanticSpecs.relationships(M5SemanticSpecs.relationshipTypes()),
                        new M5SemanticStates.RelationshipState(
                                headingOf(sourceClause.get()), sourceClause.get().contentText(),
                                headingOf(targetClause.get()), targetClause.get().contentText()));
                counters.decisionExecutions++;
                counters.questions += M5SemanticSpecs.relationshipTypes().size();
                for (var type : M5SemanticSpecs.relationshipTypes()) {
                    var answer = execution.answers()
                            .get(M5SemanticSpecs.relationshipQuestionName(type));
                    if (answer instanceof YesNoAnswer yesNo
                            && yesNo.probability() >= properties.relationshipThreshold()) {
                        relationships.add(new SemanticRelationship(UUID.randomUUID(),
                                sourceClause.get().contractVersionId(), pair.source(),
                                pair.target(), type, RelationshipDetector.SEMANTIC,
                                "narrowed pair analysis", yesNo.probability(),
                                execution.metadata().executionId(),
                                VerificationState.NOT_VERIFIED, null));
                    }
                }
            } catch (SemanticDecisionException e) {
                counters.providerDegraded = true;
            }
        }
        return relationships;
    }

    private record RegionPair(UUID source, UUID target) {
    }

    private void composeQuality(
            LinkedHashSet<ReviewQualityNote> quality, List<DefinedTerm> definedTerms,
            List<ContractReference> references, List<ContractParty> parties,
            List<ClauseSemantics> clauses) {
        for (var reference : references) {
            if (reference.status() == ReferenceStatus.BROKEN
                    || reference.status() == ReferenceStatus.AMBIGUOUS) {
                quality.add(ReviewQualityNote.UNRESOLVED_REFERENCES);
            }
        }
        for (var term : definedTerms) {
            if (term.duplicate()) {
                quality.add(ReviewQualityNote.DUPLICATE_DEFINED_TERM);
            }
        }
        for (var party : parties) {
            if (party.machineRoles().stream()
                    .anyMatch(role -> role == PartyRole.PARTY_A || role == PartyRole.PARTY_B)
                    && party.machineRoles().size() == 1) {
                quality.add(ReviewQualityNote.AMBIGUOUS_PARTY_ROLES);
            }
        }
        for (var clause : clauses) {
            var hasUnverifiedGenerative = clause.interpretations().stream()
                    .anyMatch(interpretation -> interpretation.kind()
                            == InterpretationKind.GENERATIVE_EXTRACTION
                            && interpretation.verification() != VerificationState.VERIFIED);
            if (hasUnverifiedGenerative) {
                quality.add(ReviewQualityNote.UNVERIFIED_FACTS);
            }
        }
    }

    private MetadataFact contractTypeFact(UUID versionId, ReviewProfile profile) {
        return new MetadataFact(UUID.randomUUID(), versionId, MetadataField.CONTRACT_TYPE,
                profile.contractType().name(),
                new Interpretation("contractType", InterpretationKind.DETERMINISTIC,
                        profile.contractType().name(), "user-declared-profile", 1, null,
                        List.of(), VerificationState.VERIFIED, null),
                null, null);
    }

    private boolean isFallbackOnly(List<PartyRole> roles) {
        return roles.isEmpty()
                || (roles.size() == 1 && (roles.get(0) == PartyRole.PARTY_A
                        || roles.get(0) == PartyRole.PARTY_B));
    }

    private String excerptOf(String text, String partyName) {
        if (text == null || text.isBlank()) {
            return "";
        }
        var index = text.indexOf(partyName);
        if (index < 0) {
            return text.length() > 400 ? text.substring(0, 400) : text;
        }
        var start = Math.max(0, index - 120);
        var end = Math.min(text.length(), index + partyName.length() + 280);
        return text.substring(start, end);
    }

    private Optional<ClauseSemantics> clauseByHeading(List<ClauseSemantics> clauses, UUID headingId) {
        return clauses.stream()
                .filter(clause -> clause.headingNodeId().equals(headingId))
                .findFirst();
    }

    private String headingOf(ClauseSemantics clause) {
        var text = clause.contentText();
        var newline = text.indexOf('\n');
        return newline > 0 ? text.substring(0, newline) : text;
    }

    private List<String> namesOf(List<LegalConcept> concepts) {
        return concepts.stream().map(Enum::name).toList();
    }

    private LegalConcept parseConcept(String value) {
        try {
            return LegalConcept.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ProviderProtocolException("Unknown primary concept label " + value);
        }
    }

    private boolean clearWinner(ChoiceAnswer answer) {
        var probabilities = new ArrayList<>(answer.probabilities().values());
        probabilities.sort(java.util.Comparator.reverseOrder());
        if (probabilities.size() < 2) {
            return true;
        }
        return probabilities.get(0) - probabilities.get(1) >= 0.10;
    }
}
