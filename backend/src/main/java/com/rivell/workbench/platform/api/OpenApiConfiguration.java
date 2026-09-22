package com.rivell.workbench.platform.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI rivellOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Rivell Backend API")
                .version("v1")
                .description("Technical API contract for Rivell clients."));
    }
}
