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
    void parsingContractFixturesLogsNoDocx4jError() throws Exception {
        // The m5 contract fixtures carry word/styles.xml but no relationship that points at it.
        var parser = new DocxStructuralParser(new DocxParserProperties(200L * 1024 * 1024));
        var fixtures = Path.of("src/test/resources/fixtures");
        try (var files = Files.list(fixtures)) {
            for (var file : files
                    .filter(f -> f.getFileName().toString().matches("m5-.*\\.docx"))
                    .toList()) {
                parser.parse(new ByteArrayInputStream(Files.readAllBytes(file)), UUID.randomUUID());
            }
        }
        var msa = Files.readAllBytes(fixtures.resolve("m5-msa-services.docx"));
        assertThat(parser.parse(new ByteArrayInputStream(msa), UUID.randomUUID()).status())
                .isEqualTo(ParseStatus.OK);

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                .extracting(ILoggingEvent::getFormattedMessage)
                .isEmpty();
    }
}
