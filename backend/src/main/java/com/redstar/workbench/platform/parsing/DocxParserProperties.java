package com.redstar.workbench.platform.parsing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("redstar.docx-parser")
record DocxParserProperties(long maxUncompressedBytes) {
}
