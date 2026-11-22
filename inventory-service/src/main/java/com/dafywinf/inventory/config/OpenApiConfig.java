package com.dafywinf.inventory.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        // The release version from the jar manifest (CI builds with -Drevision); null when run from an IDE or mvn
        var version = OpenApiConfig.class.getPackage().getImplementationVersion();
        return new OpenAPI().info(new Info()
                .title("Inventory Service")
                .description("Holds stock per SKU and reserves it when an OrderPlaced event arrives.")
                .version(version != null ? version : "local"));
    }
}
