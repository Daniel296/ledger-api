package com.coremaker.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class HealthEndpointShould {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void reportUpWhenTheDatabaseIsReachable() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }
}
