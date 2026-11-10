package com.coremaker.ledger.integration.api;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.coremaker.ledger.integration.support.Announcements;
import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.repository.AccountRepository;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class TransactionAnnouncementsShould {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Test
    void announceEveryTransactionAndReversalAsTheApiAnswersThem() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            var payment = post(newKey(), transfer(funding, merchant, "125.50"));
            var reversal = reverse(id(payment));

            var announced = announcements.first(2);

            assertThat(announced).extracting(ConsumerRecord::key).containsExactly(id(payment), id(reversal));
            assertThat(announced).extracting(ConsumerRecord::value).containsExactly(body(payment), body(reversal));
        }
    }

    @Test
    void announceNothingForARefusedTransactionOrReversal() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            var payment = post(newKey(), transfer(funding, merchant, "10.00"));
            var reversal = reverse(id(payment));
            var refusedTransaction = post(newKey(), transfer(merchant, funding, "10.00"));
            var refusedReversal = reverse(id(payment));
            var last = post(newKey(), transfer(funding, merchant, "1.00"));

            assertThat(refusedTransaction).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(refusedReversal).hasStatus(HttpStatus.CONFLICT);
            assertThat(announcements.firstTransactionIds(3)).containsExactly(id(payment), id(reversal), id(last));
        }
    }

    @Test
    void announceARequestRetriedWithTheSameKeyOnce() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");
        var key = newKey();

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            var first = post(key, transfer(funding, merchant, "10.00"));
            var retry = post(key, transfer(funding, merchant, "10.00"));
            var last = post(newKey(), transfer(funding, merchant, "1.00"));

            assertThat(id(retry)).isEqualTo(id(first));
            assertThat(announcements.firstTransactionIds(2)).containsExactly(id(first), id(last));
        }
    }

    @Test
    void announceATransactionWithTheRequestIdOfItsRequest() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            var payment = post(newKey(), "trace-42", transfer(funding, merchant, "10.00"));

            assertThat(requestId(payment)).isEqualTo("trace-42");
            assertThat(correlationId(announcements.first(1).get(0))).isEqualTo("trace-42");
        }
    }

    @Test
    void generateARequestIdWhenItIsMissingOrInvalid() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            var missing = post(newKey(), transfer(funding, merchant, "10.00"));
            var invalid = post(newKey(), "forged\nlog line", transfer(funding, merchant, "10.00"));

            assertThat(announcements.first(2))
                    .extracting(TransactionAnnouncementsShould::correlationId)
                    .containsExactly(requestId(missing), requestId(invalid));
            assertThat(List.of(requestId(missing), requestId(invalid)))
                    .allSatisfy(generated -> assertThat(UUID.fromString(generated)).hasToString(generated));
        }
    }

    private MvcTestResult post(String key, String body) {

        return mvc.post()
                .uri("/v1/transactions")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult post(String key, String requestId, String body) {

        return mvc.post()
                .uri("/v1/transactions")
                .header("Idempotency-Key", key)
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult reverse(String transactionId) {

        return mvc.post()
                .uri("/v1/transactions/{id}/reversal", transactionId)
                .header("Idempotency-Key", newKey())
                .exchange();
    }

    private static String requestId(MvcTestResult result) {

        return result.getResponse().getHeader("X-Request-Id");
    }

    private static String correlationId(ConsumerRecord<String, String> announcement) {

        return new String(announcement.headers().lastHeader("correlationId").value(), UTF_8);
    }

    private static String id(MvcTestResult created) throws Exception {

        return JsonPath.read(body(created), "$.id");
    }

    private static String body(MvcTestResult result) throws Exception {

        return result.getResponse().getContentAsString();
    }

    private static String newKey() {

        return UUID.randomUUID().toString();
    }

    private Account account(String overdraftLimit) {

        var account = new Account();
        account.setCurrency(EUR);
        account.setOverdraftLimit(new BigDecimal(overdraftLimit));
        account.setCreatedAt(Instant.now());
        return accountRepository.save(account);
    }

    private static String transfer(Account from, Account to, String amount) {

        return """
                {"description": "Transfer", "entries": [
                    {"accountId": "%s", "direction": "DEBIT", "amount": "%s"},
                    {"accountId": "%s", "direction": "CREDIT", "amount": "%s"}]}
                """.formatted(from.getId(), amount, to.getId(), amount);
    }
}
