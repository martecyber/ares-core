package com.martecyber.ares.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";
    private static final String API_KEY_SCHEME = "apiKeyAuth";

    @Bean
    public OpenAPI aresOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Ares ASM — API core")
                .description("REST API for the Ares Attack Surface Management platform.")
                .version("v1")
                .contact(new Contact().name("Martecyber").url("https://martecyber.com"))
                .license(new License().name("Proprietary")))
            .components(new Components()
                .addSecuritySchemes(BEARER_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"))
                .addSecuritySchemes(API_KEY_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")
                        .description("Personal API key, created under Profile → API Keys.")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
            .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
