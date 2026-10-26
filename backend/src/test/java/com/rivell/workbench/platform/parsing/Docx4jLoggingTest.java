package com.rivell.workbench.platform.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.rivell.workbench.contracts.ParseStatus;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A package with no relationship to a styles part made docx4j log "Package field null
 * for this Part ... StyleDefinitionsPart" at ERROR on every parse, though nothing failed.
 */
class Docx4jLoggingTest {

    private final Logger docx4jLogger = (Logger) LoggerFactory.getLogger("org.docx4j");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void capture() {
        appender.start();
        docx4jLogger.addAppender(appender);
    }

    @AfterEach
    void release() {
        docx4jLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void parsingTheDemoDocumentsLogsNoDocx4jError() throws Exception {
        // The demo contracts carry word/styles.xml but no relationship that points at it.
        var parser = new DocxStructuralParser(new DocxParserProperties(200L * 1024 * 1024));
        var demoDocuments = Path.of("src/main/resources/demo/documents");
        try (var files = Files.list(demoDocuments)) {
            for (var file : files.filter(f -> f.toString().endsWith(".docx")).toList()) {
                parser.parse(new ByteArrayInputStream(Files.readAllBytes(file)), UUID.randomUUID());
            }
        }
        var acme = Files.readAllBytes(demoDocuments.resolve("Acme MSA - Review in progress.docx"));
        assertThat(parser.parse(new ByteArrayInputStream(acme), UUID.randomUUID()).status())
                .isEqualTo(ParseStatus.OK);

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                .extracting(ILoggingEvent::getFormattedMessage)
                .isEmpty();
    }
}
