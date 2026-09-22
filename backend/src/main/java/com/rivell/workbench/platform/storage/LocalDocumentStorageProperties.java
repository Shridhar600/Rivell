package com.rivell.workbench.platform.storage;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rivell.storage.local")
public record LocalDocumentStorageProperties(Path root) {
}
