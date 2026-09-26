package com.rivell.workbench.redline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.LegalConcept;

import static com.rivell.workbench.redline.RedlineTestStructures.Table;
import static com.rivell.workbench.redline.RedlineTestStructures.clause;
import static com.rivell.workbench.redline.RedlineTestStructures.concepts;
import static com.rivell.workbench.redline.RedlineTestStructures.delete;
import static com.rivell.workbench.redline.RedlineTestStructures.headingId;
import static com.rivell.workbench.redline.RedlineTestStructures.insert;
import static com.rivell.workbench.redline.RedlineTestStructures.replace;
import static com.rivell.workbench.redline.RedlineTestStructures.set;
import static com.rivell.workbench.redline.RedlineTestStructures.structure;
import static com.rivell.workbench.redline.RedlineTestStructures.text;

class RedlinePlanResolverTest {

    private final RedlinePlanResolver resolver = new RedlinePlanResolver();

    private static final String AUTHOR = "Rivell";
    private static final Instant EXECUTED_AT = Instant.parse("2026-09-26T10:00:00Z");
    private static final String SOURCE_SHA = "a".repeat(64);

    @Test
    void replacesTheClauseBodyAndPreservesTheHeading() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Services", text("Services body")),
                clause("2. Limitation of Liability", text("Old cap text."),
                        text("Old cap continuation.")),
                clause("3. Governing Law", text("Delaware law."))));
        var conceptByHeading = concepts(structure, Map.of(
                "1. Services", LegalConcept.GOVERNING_LAW,
                "2. Limitation of Liability", LegalConcept.LIMITATION_OF_LIABILITY));
        var changeSetId = UUID.randomUUID();
        var change = replace(changeSetId, 0,
                headingId(structure, "2. Limitation of Liability"), "New cap text.");
        var set = set(UUID.randomUUID(), contractId, versionId, 1, List.of(change));

        var plan = resolver.resolve(set, structure, conceptByHeading, AUTHOR, EXECUTED_AT,
                SOURCE_SHA);

        assertThat(plan.operations()).hasSize(1);
        var operation = plan.operations().get(0);
        assertThat(operation.actionType()).isEqualTo(ChangeActionType.REPLACE);
        assertThat(operation.deletionTargets()).extracting(PlannedParagraph::expectedText)
                .containsExactly("Old cap text.", "Old cap continuation.");
        assertThat(operation.insertion().paragraphs()).containsExactly("New cap text.");
        assertThat(operation.insertion().afterBodyIndex())
                .isEqualTo(operation.deletionTargets().get(0).bodyIndex() - 1);
        assertThat(operation.insertion().styleTemplateBodyIndex())
                .isEqualTo(operation.deletionTargets().get(0).bodyIndex());
        assertThat(operation.insertion().styleTemplateParagraphIndex())
                .isEqualTo(operation.deletionTargets().get(0).paragraphIndex());
        assertThat(operation.targetLabel()).isEqualTo("2. Limitation of Liability");
    }

    @Test
    void deletesTheEntireClauseRegionIncludingTheHeading() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Services", text("Services body")),
                clause("2. Limitation of Liability", text("Cap text.")),
                clause("3. Governing Law", text("Delaware law."))));
        var changeSetId = UUID.randomUUID();
        var change = delete(changeSetId, 0,
                headingId(structure, "2. Limitation of Liability"));
        var set = set(UUID.randomUUID(), contractId, versionId, 1, List.of(change));

        var plan = resolver.resolve(set, structure, Map.of(), AUTHOR, EXECUTED_AT, SOURCE_SHA);

        var operation = plan.operations().get(0);
        assertThat(operation.actionType()).isEqualTo(ChangeActionType.DELETE);
        assertThat(operation.deletionTargets()).extracting(PlannedParagraph::expectedText)
                .containsExactly("2. Limitation of Liability", "Cap text.");
        assertThat(operation.insertion()).isNull();
    }

    @Test
    void resolvesAnInsertionThroughTheDeterministicPlacementStrategy() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Indemnification", text("Indemnity body")),
                clause("2. Confidentiality", text("Confidentiality body"))));
        var conceptByHeading = concepts(structure, Map.of(
                "1. Indemnification", LegalConcept.INDEMNIFICATION,
                "2. Confidentiality", LegalConcept.CONFIDENTIALITY));
        var changeSetId = UUID.randomUUID();
        var change = insert(changeSetId, 0, LegalConcept.LIMITATION_OF_LIABILITY,
                "Approved cap text.");
        var set = set(UUID.randomUUID(), contractId, versionId, 1, List.of(change));

        var plan = resolver.resolve(set, structure, conceptByHeading, AUTHOR, EXECUTED_AT,
                SOURCE_SHA);

        var operation = plan.operations().get(0);
        assertThat(operation.actionType()).isEqualTo(ChangeActionType.INSERT);
        assertThat(operation.deletionTargets()).isEmpty();
        assertThat(operation.placementLabel()).isEqualTo("AFTER_CLAUSE[INDEMNIFICATION]");
        assertThat(operation.insertion().paragraphs()).containsExactly("Approved cap text.");
        assertThat(operation.insertion().styleTemplateBodyIndex()).isNotNull();
    }

    @Test
    void splitsApprovedLanguageIntoParagraphsWithoutChangingContent() {
        assertThat(RedlinePlanResolver.splitParagraphs("One line."))
                .containsExactly("One line.");
        assertThat(RedlinePlanResolver.splitParagraphs("First.\nSecond."))
                .containsExactly("First.", "Second.");
        assertThat(RedlinePlanResolver.splitParagraphs("First.\r\nSecond.\r\n"))
                .containsExactly("First.", "Second.");
        assertThat(RedlinePlanResolver.splitParagraphs("First.\n\nThird.\n"))
                .containsExactly("First.", "", "Third.");
        assertThat(RedlinePlanResolver.splitParagraphs("  leading and trailing  "))
                .containsExactly("  leading and trailing  ");
    }

    @Test
    void rejectsBlankApprovedLanguage() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Services", text("Services body"))));
        var changeSetId = UUID.randomUUID();
        var change = replace(changeSetId, 0, headingId(structure, "1. Services"), "\n\n");
        var set = set(UUID.randomUUID(), contractId, versionId, 1, List.of(change));

        assertThatThrownBy(() -> resolver.resolve(set, structure, Map.of(), AUTHOR,
                EXECUTED_AT, SOURCE_SHA))
                .isInstanceOf(RedlinePlanException.class)
                .extracting(e -> ((RedlinePlanException) e).code())
                .isEqualTo(RedlineFailureCode.MISSING_APPROVED_LANGUAGE);
    }

    @Test
    void failsClosedForAnUnknownHeadingTarget() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Services", text("Services body"))));
        var changeSetId = UUID.randomUUID();
        var change = replace(changeSetId, 0, UUID.randomUUID(), "New text.");
        var set = set(UUID.randomUUID(), contractId, versionId, 1, List.of(change));

        assertThatThrownBy(() -> resolver.resolve(set, structure, Map.of(), AUTHOR,
                EXECUTED_AT, SOURCE_SHA))
                .isInstanceOf(RedlinePlanException.class)
                .extracting(e -> ((RedlinePlanException) e).code())
                .isEqualTo(RedlineFailureCode.TARGET_NOT_FOUND);
    }

    @Test
    void failsClosedForATableBodyRegion() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Pricing", new Table("Fee", "Amount"))));
        var changeSetId = UUID.randomUUID();
        var change = replace(changeSetId, 0, headingId(structure, "1. Pricing"), "New text.");
        var set = set(UUID.randomUUID(), contractId, versionId, 1, List.of(change));

        assertThatThrownBy(() -> resolver.resolve(set, structure, Map.of(), AUTHOR,
                EXECUTED_AT, SOURCE_SHA))
                .isInstanceOf(RedlinePlanException.class)
                .extracting(e -> ((RedlinePlanException) e).code())
                .isEqualTo(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE);
    }

    @Test
    void rejectsAnUnsupportedChangeSetVersion() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Services", text("Services body"))));
        var changeSetId = UUID.randomUUID();
        var change = replace(changeSetId, 0, headingId(structure, "1. Services"), "New text.");
        var set = set(UUID.randomUUID(), contractId, versionId, 2, List.of(change));

        assertThatThrownBy(() -> resolver.resolve(set, structure, Map.of(), AUTHOR,
                EXECUTED_AT, SOURCE_SHA))
                .isInstanceOf(RedlinePlanException.class)
                .extracting(e -> ((RedlinePlanException) e).code())
                .isEqualTo(RedlineFailureCode.UNSUPPORTED_CHANGESET_VERSION);
    }

    @Test
    void allowsAnInsertionAnchoredAfterAParagraphDeletedByAnotherChange() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Confidentiality", text("Confidentiality body")),
                clause("2. Term and Survival", text("Term body"))));
        var conceptByHeading = concepts(structure, Map.of(
                "1. Confidentiality", LegalConcept.CONFIDENTIALITY,
                "2. Term and Survival", LegalConcept.TERM_TERMINATION));
        var changeSetId = UUID.randomUUID();
        var deleteConfidentiality = delete(changeSetId, 0,
                headingId(structure, "1. Confidentiality"));
        var insertLiability = insert(changeSetId, 1, LegalConcept.LIMITATION_OF_LIABILITY,
                "Approved cap text.");
        var set = set(UUID.randomUUID(), contractId, versionId, 1, List.of(deleteConfidentiality, insertLiability));

        var plan = resolver.resolve(set, structure, conceptByHeading, AUTHOR, EXECUTED_AT,
                SOURCE_SHA);

        assertThat(plan.operations()).hasSize(2);
    }

    @Test
    void failsClosedWhenTheDocumentFinalParagraphIsDeleted() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Services", text("Services body"))));
        var changeSetId = UUID.randomUUID();
        var delete = delete(changeSetId, 0, headingId(structure, "1. Services"));
        var set = set(UUID.randomUUID(), contractId, versionId, 1, List.of(delete));

        assertThatThrownBy(() -> resolver.resolve(set, structure, Map.of(), AUTHOR,
                EXECUTED_AT, SOURCE_SHA))
                .isInstanceOf(RedlinePlanException.class)
                .extracting(e -> ((RedlinePlanException) e).code())
                .isEqualTo(RedlineFailureCode.UNSUPPORTED_SOURCE_STRUCTURE);
    }

    @Test
    void insertionCarriesTheResolvedAnchorIdentity() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Indemnification", text("Indemnity body")),
                clause("2. Confidentiality", text("Confidentiality body"))));
        var conceptByHeading = concepts(structure, Map.of(
                "1. Indemnification", LegalConcept.INDEMNIFICATION,
                "2. Confidentiality", LegalConcept.CONFIDENTIALITY));
        var changeSetId = UUID.randomUUID();
        var change = insert(changeSetId, 0, LegalConcept.LIMITATION_OF_LIABILITY,
                "Approved cap text.");
        var set = set(UUID.randomUUID(), contractId, versionId, 1, List.of(change));

        var plan = resolver.resolve(set, structure, conceptByHeading, AUTHOR, EXECUTED_AT,
                SOURCE_SHA);

        var insertion = plan.operations().get(0).insertion();
        assertThat(insertion.anchorExpectedText()).isEqualTo("Indemnity body");
        assertThat(insertion.anchorParagraphIndex()).isNotNull();
    }

    @Test
    void failsClosedWhenTwoChangesDeleteTheSameParagraph() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Services", text("Services body"))));
        var changeSetId = UUID.randomUUID();
        var delete = delete(changeSetId, 0, headingId(structure, "1. Services"));
        var replace = replace(changeSetId, 1, headingId(structure, "1. Services"),
                "Replacement text.");
        var set = set(UUID.randomUUID(), contractId, versionId, 1, List.of(delete, replace));

        assertThatThrownBy(() -> resolver.resolve(set, structure, Map.of(), AUTHOR,
                EXECUTED_AT, SOURCE_SHA))
                .isInstanceOf(RedlinePlanException.class)
                .extracting(e -> ((RedlinePlanException) e).code())
                .isEqualTo(RedlineFailureCode.CHANGE_OVERLAP);
    }

    @Test
    void ordersOperationsByBusinessOrdinal() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Services", text("Services body")),
                clause("2. Confidentiality", text("Confidentiality body")),
                clause("3. Governing Law", text("Delaware law."))));
        var changeSetId = UUID.randomUUID();
        var later = replace(changeSetId, 1,
                headingId(structure, "2. Confidentiality"), "New confidentiality.");
        var earlier = replace(changeSetId, 0,
                headingId(structure, "1. Services"), "New services.");
        var set = set(UUID.randomUUID(), contractId, versionId, 1, List.of(later, earlier));

        var plan = resolver.resolve(set, structure, Map.of(), AUTHOR, EXECUTED_AT, SOURCE_SHA);

        assertThat(plan.operations()).extracting(PlannedDocumentOperation::ordinal)
                .containsExactly(0, 1);
    }
}
