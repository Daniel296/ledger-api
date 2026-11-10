package com.coremaker.ledger.integration.concurrency;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.IdempotentRequestRepository;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class ConcurrentRetriesShould {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdempotentRequestRepository idempotentRequestRepository;

    @RepeatedTest(5)
    void openOneAccountWhenTheSameRequestIsSentManyTimesAtOnce() throws Exception {

        var key = newKey();
        var accountsBefore = accountRepository.count();

        var responses = sendAtOnce(10, () -> open(key));

        assertAllTheSame(responses, HttpStatus.CREATED);
        assertThat(accountRepository.count()).isEqualTo(accountsBefore + 1);
    }

    @RepeatedTest(5)
    void recordOneTransactionWhenTheSameRequestIsSentManyTimesAtOnce() throws Exception {

        var funding = account("1000000.00");
        var merchant = account("0");
        var key = newKey();

        var responses = sendAtOnce(10, () -> post(key, transfer(funding, merchant, "10.00")));

        assertAllTheSame(responses, HttpStatus.CREATED);
        assertThat(balance(merchant)).isEqualByComparingTo("10.00");
    }

    @RepeatedTest(5)
    void reverseATransactionOnceWhenTheSameReversalIsSentManyTimesAtOnce() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");
        var payment = post(newKey(), transfer(funding, merchant, "10.00"));
        var paymentId = JsonPath.read(body(payment), "$.id");
        var key = newKey();

        var responses = sendAtOnce(10, () -> reverse(key, paymentId));

        assertAllTheSame(responses, HttpStatus.CREATED);
        assertThat(balance(merchant)).isZero();
    }

    @RepeatedTest(5)
    void refuseEveryCopyOfARefusedRequestSentManyTimesAtOnce() throws Exception {

        var merchant = account("0");
        var payouts = account("0");
        var key = newKey();

        var responses = sendAtOnce(10, () -> post(key, transfer(merchant, payouts, "10.00")));

        assertAllTheSame(responses, HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(idempotentRequestRepository.findById(key).orElseThrow().getResponse()).isNull();
        assertThat(balance(payouts)).isZero();
    }

    private static List<MvcTestResult> sendAtOnce(int copies, Supplier<MvcTestResult> request) throws Exception {

        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(copies);
        try {
            var pending = new ArrayList<Future<MvcTestResult>>();
            for (int copy = 0; copy < copies; copy++) {

                pending.add(executor.submit(() -> {

                    start.await();
                    return request.get();
                }));
            }

            start.countDown();
            var responses = new ArrayList<MvcTestResult>();
            for (var response : pending) {

                responses.add(response.get());
            }

            return responses;
        } finally {
            executor.shutdown();
        }
    }

    private static void assertAllTheSame(List<MvcTestResult> responses, HttpStatus status) throws Exception {

        var first = responses.get(0);
        assertThat(responses).allSatisfy(response -> {

            assertThat(response).hasStatus(status);
            assertThat(body(response)).isEqualTo(body(first));
            assertThat(response.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
        });
    }

    private MvcTestResult open(String key) {

        return mvc.post()
                .uri("/v1/accounts")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currency": "EUR"}
                        """)
                .exchange();
    }

    private MvcTestResult post(String key, String body) {

        return mvc.post()
                .uri("/v1/transactions")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult reverse(String key, Object transactionId) {

        return mvc.post()
                .uri("/v1/transactions/{id}/reversal", transactionId)
                .header("Idempotency-Key", key)
                .exchange();
    }

    private static String body(MvcTestResult result) throws Exception {

        return result.getResponse().getContentAsString();
    }

    private static String newKey() {

        return UUID.randomUUID().toString();
    }

    private BigDecimal balance(Account account) {

        return accountRepository.findById(account.getId())
                .orElseThrow()
                .getBalance();
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
