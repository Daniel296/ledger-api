package com.coremaker.ledger.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport.Level;

@TestConfiguration(proxyBeanMethods = false)
class OpenApiContractConfiguration {

    @Bean
    MockMvcBuilderCustomizer openApiContract() {

        var requestChecksTestsBreakOnPurpose = LevelResolver.create()
                .withLevel("validation.request.path.missing", Level.IGNORE)
                .withLevel("validation.request.body", Level.IGNORE)
                .withLevel("validation.request.parameter", Level.IGNORE)
                .withLevel("validation.request.contentType", Level.IGNORE)
                .withLevel("validation.request.accept", Level.IGNORE)
                .build();
        var validator = OpenApiInteractionValidator.createForSpecificationUrl("docs/openapi.yaml")
                .withLevelResolver(requestChecksTestsBreakOnPurpose)
                .build();
        return builder -> builder.alwaysExpect(OpenApiValidationMatchers.openApi().isValid(validator));
    }
}
