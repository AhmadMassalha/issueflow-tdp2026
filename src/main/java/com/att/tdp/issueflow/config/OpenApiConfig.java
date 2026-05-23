package com.att.tdp.issueflow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi configuration.
 *
 * <p>Defines a single global "Bearer JWT" security scheme so the Swagger UI
 * "Authorize" button accepts a token from {@code POST /auth/login} and
 * automatically adds the {@code Authorization: Bearer …} header to every
 * subsequent request. The scheme is required at the document level — every
 * endpoint inherits it unless individually overridden with
 * {@code @SecurityRequirements({})}. (We don't override anywhere — even
 * {@code /auth/login} is fine to show the Authorize lock; clicking it just
 * skips the field.)
 *
 * <p>Doc paths ({@code /v3/api-docs/**}, {@code /swagger-ui/**},
 * {@code /swagger-ui.html}) are made {@code permitAll()} in
 * {@code SecurityConfig} so the spec + UI load without a token.
 *
 * <p>This config exists for reviewer convenience and is not gated on any
 * spec section. Auto-discovers every {@code @RestController} on the classpath;
 * no per-endpoint registration is needed.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI issueflowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("IssueFlow API")
                        .version("0.0.1-SNAPSHOT")
                        .description("AT&T TDP IssueFlow — ticket management backend (spec-driven, see docs/spec/).")
                        .license(new License().name("MIT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the token from POST /auth/login (without the 'Bearer ' prefix).")));
    }
}
