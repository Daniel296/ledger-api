package com.coremaker.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class AccountApiShould {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void openAnAccountAndReadItBack() throws Exception {

        var opened = open("""
                {"currency": "EUR", "overdraftLimit": "100.00"}
                """);

        assertThat(opened).hasStatus(HttpStatus.CREATED);
        assertThat(opened).bodyJson().extractingPath("$.currency").isEqualTo("EUR");
        assertThat(opened).bodyJson().extractingPath("$.overdraftLimit").isEqualTo("100.00");
        assertThat(opened).bodyJson().extractingPath("$.balance").isEqualTo("0.00");

        var location = opened.getResponse().getHeader("Location");
        var read = get(location);

        assertThat(read).hasStatusOk();
        assertThat(read).bodyJson().isEqualTo(opened.getResponse().getContentAsString());
    }

    @Test
    void showASettlementAccountWithoutAnOverdraftLimit() {

        var settlementEur = "ffd6f25c-cbbb-43ae-afe5-1834678bb9aa";

        var read = get("/v1/accounts/{id}", settlementEur);

        assertThat(read).hasStatusOk();
        assertThat(read).bodyJson().doesNotHavePath("$.overdraftLimit");
    }

    @Test
    void refuseACurrencyTheLedgerDoesNotSupport() {

        var refused = open("""
                {"currency": "GBP"}
                """);

        assertThat(refused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused).bodyJson().extractingPath("$.errorCode").isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void reportAnAccountThatDoesNotExist() {

        var read = get("/v1/accounts/{id}", UUID.randomUUID());

        assertThat(read).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(read).bodyJson().extractingPath("$.errorCode").isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void rejectMalformedRequests() {

        var missingCurrency = open("{}");
        var invalidJson = open("{");
        var invalidId = get("/v1/accounts/not-a-uuid");

        assertThat(missingCurrency).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invalidJson).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invalidId).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private MvcTestResult open(String body) {

        return mvc.post()
                .uri("/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult get(String uri, Object... variables) {

        return mvc.get()
                .uri(uri, variables)
                .exchange();
    }
}
