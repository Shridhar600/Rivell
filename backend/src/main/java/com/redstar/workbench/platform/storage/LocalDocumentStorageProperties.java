package com.redstar.workbench.platform.storage;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("redstar.storage.local")
public record LocalDocumentStorageProperties(Path root) {
}
