package com.rivell.workbench.platform.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rivell.workbench.foundation.DocumentStorage;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalDocumentStorageProperties.class)
class LocalDocumentStorageConfiguration {

    @Bean
    DocumentStorage documentStorage(LocalDocumentStorageProperties properties) {
        return new LocalDocumentStorage(properties.root());
    }
}
