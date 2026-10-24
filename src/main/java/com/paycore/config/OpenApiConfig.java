package com.paycore.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Adds the "Authorize" button to Swagger UI so you can paste your keys once. */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI payCoreOpenApi() {
        return new OpenAPI()
                .info(new Info().title("PayCore API").version("0.0.2"))
                .components(new Components()
                        .addSecuritySchemes("merchantKey", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .description("Merchant API key (pk_...) for /payments endpoints"))
                        .addSecuritySchemes("adminKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Admin-Key")
                                .description("Admin key for accounts, deposits and /admin endpoints")))
                .addSecurityItem(new SecurityRequirement().addList("merchantKey").addList("adminKey"));
    }
}
