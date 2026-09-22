package com.redstar.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ContractDomainTest {

    private static final String SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Test
    void acceptsValidSourceArtifact() {
        var artifact = new SourceArtifact(
                UUID.randomUUID(), UUID.randomUUID(), "nda.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                1024, SHA256, "org/contracts/nda.docx", NOW);

        assertThat(artifact.sha256()).isEqualTo(SHA256);
        assertThat(artifact.sizeBytes()).isEqualTo(1024);
    }

    @Test
    void rejectsInvalidSha256() {
        assertThatThrownBy(() -> new SourceArtifact(
                UUID.randomUUID(), UUID.randomUUID(), "nda.docx",
                "application/octet-stream", 1, "not-a-hash", "k", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankFilenameAndNegativeSize() {
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        assertThatThrownBy(() -> new SourceArtifact(id, orgId, " ", "t", 1, SHA256, "k", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceArtifact(id, orgId, "nda.docx", "t", -1, SHA256, "k", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstVersionStartsAtOneWithOriginalRole() {
        var version = new ContractVersion(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                ContractRole.ORIGINAL, "redstar-docx-parser-1", ParseStatus.OK, List.of(), NOW);

        assertThat(version.versionNumber()).isEqualTo(1);
        assertThat(version.role()).isEqualTo(ContractRole.ORIGINAL);
        assertThat(version.parseStatus()).isEqualTo(ParseStatus.OK);
        assertThat(version.parseWarnings()).isEmpty();
    }

    @Test
    void rejectsVersionNumberBelowOne() {
        assertThatThrownBy(() -> new ContractVersion(
                UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID(),
                ContractRole.ORIGINAL, "p", ParseStatus.OK, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentNodeRequiresSpanAndValidOrdinal() {
        var versionId = UUID.randomUUID();
        var span = SourceSpan.ofParagraph("word/document.xml", 0, 0);

        var node = new DocumentNode(
                UUID.randomUUID(), versionId, null, DocumentNodeType.TITLE, 0,
                "Mutual NDA", null, null, span, "Title");

        assertThat(node.nodeType()).isEqualTo(DocumentNodeType.TITLE);
        assertThat(node.sourceSpan().bodyIndex()).isZero();

        assertThatThrownBy(() -> new DocumentNode(
                UUID.randomUUID(), versionId, null, DocumentNodeType.PARAGRAPH, -1,
                "x", null, null, span, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentNode(
                UUID.randomUUID(), versionId, null, DocumentNodeType.PARAGRAPH, 0,
                "x", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sourceSpanKeepsBodyAndStructuralIndices() {
        var span = SourceSpan.ofCell("word/document.xml", 3, 1, 0, 2);

        assertThat(span.bodyIndex()).isEqualTo(3);
        assertThat(span.tableIndex()).isEqualTo(1);
        assertThat(span.rowIndex()).isZero();
        assertThat(span.cellIndex()).isEqualTo(2);
        assertThat(span.hasNonNegativeIndices()).isTrue();
        assertThat(span.paragraphIndex()).isNull();
    }

    @Test
    void rejectsBlankSpanPartPath() {
        assertThatThrownBy(() -> SourceSpan.ofBody(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
