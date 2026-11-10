package com.coremaker.ledger.integration.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.repository.AccountRepository;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class AccountApiShould {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AccountRepository accountRepository;

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
    void openAnAccountOnceAndAnswerItsRetryWithTheOriginalResponse() throws Exception {

        var key = UUID.randomUUID().toString();
        var request = """
                {"currency": "EUR"}
                """;
        var accountsBefore = accountRepository.count();

        var first = open(key, request);
        var opened = accountRepository.findById(UUID.fromString(JsonPath.read(body(first), "$.id"))).orElseThrow();
        opened.setBalance(new BigDecimal("10.00"));
        accountRepository.save(opened);
        var retry = open(key, request);

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(body(retry)).isEqualTo(body(first));
        assertThat(retry.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(retry).bodyJson().extractingPath("$.balance").isEqualTo("0.00");
        assertThat(accountRepository.count()).isEqualTo(accountsBefore + 1);
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
        var noIdempotencyKey = mvc.post()
                .uri("/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currency": "EUR"}
                        """)
                .exchange();

        assertThat(missingCurrency).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invalidJson).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invalidId).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(noIdempotencyKey).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private MvcTestResult open(String body) {

        return open(UUID.randomUUID().toString(), body);
    }

    private MvcTestResult open(String key, String body) {

        return mvc.post()
                .uri("/v1/accounts")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static String body(MvcTestResult result) throws Exception {

        return result.getResponse().getContentAsString();
    }

    private MvcTestResult get(String uri, Object... variables) {

        return mvc.get()
                .uri(uri, variables)
                .exchange();
    }
}
