package com.rivell.workbench.redline;

import com.rivell.workbench.taxonomy.CoreConcepts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.ContractStructure;
import com.rivell.workbench.contracts.DocumentNode;
import com.rivell.workbench.contracts.DocumentNodeType;

import static com.rivell.workbench.redline.RedlineTestStructures.BodyElement;
import static com.rivell.workbench.redline.RedlineTestStructures.clause;
import static com.rivell.workbench.redline.RedlineTestStructures.concepts;
import static com.rivell.workbench.redline.RedlineTestStructures.structure;
import static com.rivell.workbench.redline.RedlineTestStructures.text;

class InsertPlacementStrategyTest {

    private final InsertPlacementStrategy strategy = new InsertPlacementStrategy();

    @Test
    void placesAfterTheHighestPriorityPresentAnchorConcept() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Confidentiality", text("Confidentiality body")),
                clause("2. Indemnification", text("Indemnification body")),
                clause("3. Term and Survival", text("Term body"))));
        var conceptByHeading = concepts(structure, Map.of(
                "1. Confidentiality", CoreConcepts.CONFIDENTIALITY_OBLIGATIONS,
                "2. Indemnification", CoreConcepts.INDEMNIFICATION,
                "3. Term and Survival", CoreConcepts.TERM));

        var placement = strategy.resolve(CoreConcepts.LIMITATION_OF_LIABILITY,
                structure, conceptByHeading);

        assertThat(placement.label()).isEqualTo("AFTER_CLAUSE[INDEMNIFICATION]");
        assertThat(placement.afterBodyIndex())
                .isEqualTo(lastBodyIndex(structure, "2. Indemnification"));
        assertThat(placement.styleTemplateBodyIndex()).isEqualTo(placement.afterBodyIndex());
    }

    @Test
    void usesTheLastMatchingRegionInDocumentOrder() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Indemnification", text("First indemnity")),
                clause("2. Confidentiality", text("Confidentiality body")),
                clause("3. Indemnification", text("Second indemnity"))));
        var conceptByHeading = concepts(structure, Map.of(
                "1. Indemnification", CoreConcepts.INDEMNIFICATION,
                "2. Confidentiality", CoreConcepts.CONFIDENTIALITY_OBLIGATIONS,
                "3. Indemnification", CoreConcepts.INDEMNIFICATION));

        var placement = strategy.resolve(CoreConcepts.LIMITATION_OF_LIABILITY,
                structure, conceptByHeading);

        assertThat(placement.afterBodyIndex())
                .isEqualTo(lastBodyIndex(structure, "3. Indemnification"));
    }

    @Test
    void fallsBackToBeforeTheSignatureBlockWhenNoAnchorConceptIsPresent() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Services", text("Services body")),
                clause("2. Acme Corp", text("IN WITNESS WHEREOF the parties execute."))));
        var conceptByHeading = concepts(structure, Map.of(
                "1. Services", CoreConcepts.GOVERNING_LAW));

        var placement = strategy.resolve(CoreConcepts.LIMITATION_OF_LIABILITY,
                structure, conceptByHeading);

        assertThat(placement.label()).isEqualTo("BEFORE_SIGNATURE_BLOCK");
        assertThat(placement.afterBodyIndex())
                .isEqualTo(bodyIndexOfHeading(structure, "2. Acme Corp") - 1);
        assertThat(placement.styleTemplateBodyIndex()).isNull();
    }

    @Test
    void fallsBackToTheEndOfTheBodyWhenNoSignatureOrScheduleRegionExists() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of(
                clause("1. Services", text("Services body")),
                clause("2. Fees", text("Fees body"))));
        var conceptByHeading = concepts(structure, Map.of(
                "1. Services", CoreConcepts.GOVERNING_LAW,
                "2. Fees", CoreConcepts.GOVERNING_LAW));

        var placement = strategy.resolve(CoreConcepts.LIMITATION_OF_LIABILITY,
                structure, conceptByHeading);

        assertThat(placement.label()).isEqualTo("END_OF_BODY");
        assertThat(placement.afterBodyIndex()).isEqualTo(lastBodyIndex(structure, "2. Fees"));
        assertThat(placement.styleTemplateBodyIndex()).isEqualTo(placement.afterBodyIndex());
    }

    @Test
    void failsClosedWhenTheDocumentHasNoClauseRegionsAtAll() {
        var contractId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        var structure = structure(contractId, versionId, List.of());

        assertThatThrownBy(() -> strategy.resolve(CoreConcepts.LIMITATION_OF_LIABILITY,
                structure, Map.of()))
                .isInstanceOf(RedlinePlanException.class)
                .extracting(e -> ((RedlinePlanException) e).code())
                .isEqualTo(RedlineFailureCode.INSERT_PLACEMENT_UNRESOLVED);
    }

    private static int lastBodyIndex(ContractStructure structure, String headingText) {
        var nodes = structure.nodes();
        var headingIndex = -1;
        for (var i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            if (node.nodeType() == DocumentNodeType.HEADING
                    && headingText.equals(node.text())) {
                headingIndex = i;
                break;
            }
        }
        var last = nodes.get(headingIndex).sourceSpan().bodyIndex();
        for (var i = headingIndex + 1; i < nodes.size(); i++) {
            var node = nodes.get(i);
            if (node.nodeType() == DocumentNodeType.HEADING) {
                break;
            }
            if (node.nodeType() == DocumentNodeType.PARAGRAPH
                    || node.nodeType() == DocumentNodeType.TABLE) {
                last = node.sourceSpan().bodyIndex();
            }
        }
        return last;
    }

    private static int bodyIndexOfHeading(ContractStructure structure, String headingText) {
        return structure.nodes().stream()
                .filter(node -> node.nodeType() == DocumentNodeType.HEADING)
                .filter(node -> headingText.equals(node.text()))
                .map(DocumentNode::sourceSpan)
                .map(com.rivell.workbench.contracts.SourceSpan::bodyIndex)
                .findFirst()
                .orElseThrow();
    }
}
