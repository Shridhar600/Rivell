package com.rivell.workbench.platform.parsing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ZipGuardTest {

    @Test
    void aMainDocumentDeclaringEntitiesIsRefused() {
        var xml = """
                <?xml version="1.0"?>
                <!DOCTYPE w:document [<!ENTITY x SYSTEM "file:///etc/hosts">]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body>
                </w:document>
                """;

        assertThatThrownBy(() -> ZipGuard.countTopLevelBodyChildren(xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);
    }
}
