package com.rivell.workbench.platform.parsing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rivell.docx-parser")
record DocxParserProperties(long maxUncompressedBytes) {
}
