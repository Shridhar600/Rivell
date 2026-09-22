package com.redstar.workbench.platform.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Body;
import org.junit.jupiter.api.Test;

class DocxPackageSmokeTest {

    @Test
    void loadsSimpleContractFixture() throws Exception {
        try (InputStream stream = resource("fixtures/simple-contract.docx")) {
            var wordPackage = WordprocessingMLPackage.load(stream);

            assertThat(wordPackage).isNotNull();
            assertThat(body(wordPackage).getContent()).isNotEmpty();
        }
    }

    @Test
    void loadsTableFixture() throws Exception {
        try (InputStream stream = resource("fixtures/with-table.docx")) {
            var wordPackage = WordprocessingMLPackage.load(stream);

            assertThat(wordPackage).isNotNull();
            assertThat(body(wordPackage).getContent()).isNotEmpty();
        }
    }

    @Test
    void rejectsInvalidBytes() throws Exception {
        try (InputStream stream = resource("fixtures/invalid-bytes.docx")) {
            assertThatThrownBy(() -> WordprocessingMLPackage.load(stream))
                    .isInstanceOf(Exception.class);
        }
    }

    private Body body(WordprocessingMLPackage wordPackage) {
        var mainPart = wordPackage.getMainDocumentPart();
        assertThat(mainPart).isNotNull();
        return mainPart.getJaxbElement().getBody();
    }

    private InputStream resource(String location) {
        var stream = getClass().getClassLoader().getResourceAsStream(location);
        assertThat(stream).as("classpath resource %s", location).isNotNull();
        return stream;
    }
}
