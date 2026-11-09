package com.coremaker.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class AccountApiShould {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void openAnAccountAndReadItBack() throws Exception {

        var opened = mvc.post().uri("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currency": "EUR", "overdraftLimit": "100.00"}
                        """)
                .exchange();

        assertThat(opened).hasStatus(HttpStatus.CREATED);
        assertThat(opened).bodyJson().extractingPath("$.currency").isEqualTo("EUR");
        assertThat(opened).bodyJson().extractingPath("$.overdraftLimit").isEqualTo("100.00");
        assertThat(opened).bodyJson().extractingPath("$.balance").isEqualTo("0.00");

        var location = opened.getResponse().getHeader("Location");
        var read = mvc.get().uri(location).exchange();

        assertThat(read).hasStatusOk();
        assertThat(read).bodyJson().isEqualTo(opened.getResponse().getContentAsString());
    }

    @Test
    void showASettlementAccountWithoutAnOverdraftLimit() {

        var settlementEur = "ffd6f25c-cbbb-43ae-afe5-1834678bb9aa";

        var read = mvc.get().uri("/v1/accounts/{id}", settlementEur).exchange();

        assertThat(read).hasStatusOk();
        assertThat(read).bodyJson().doesNotHavePath("$.overdraftLimit");
    }

    @Test
    void refuseACurrencyTheLedgerDoesNotSupport() {

        var refused = mvc.post().uri("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currency": "GBP"}
                        """)
                .exchange();

        assertThat(refused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused).bodyJson().extractingPath("$.errorCode").isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    void reportAnAccountThatDoesNotExist() {

        var read = mvc.get().uri("/v1/accounts/{id}", UUID.randomUUID()).exchange();

        assertThat(read).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(read).bodyJson().extractingPath("$.errorCode").isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void rejectMalformedRequests() {

        var missingCurrency = mvc.post().uri("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
        var invalidJson = mvc.post().uri("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{").exchange();
        var invalidId = mvc.get().uri("/v1/accounts/not-a-uuid").exchange();

        assertThat(missingCurrency).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invalidJson).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invalidId).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
