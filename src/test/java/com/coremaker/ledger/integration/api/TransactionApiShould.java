package com.coremaker.ledger.integration.api;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.enums.Currency;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.TransactionRepository;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class TransactionApiShould {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void recordTransactionsAndReadThemBack() throws Exception {

        var funding = account(EUR, "1000.00");
        var merchant = account(EUR, "0");
        var payouts = account(EUR, "0");
        var fees = account(EUR, "0");

        var settled = recordTransaction("Payment settled", debit(funding, "125.50"), credit(merchant, "125.50"));
        var payout = recordTransaction("Payout", debit(merchant, "100.00"), credit(payouts, "98.50"), credit(fees, "1.50"));

        assertThat(settled).hasStatus(HttpStatus.CREATED);
        assertThat(payout).hasStatus(HttpStatus.CREATED);
        assertThat(payout).bodyJson().extractingPath("$.entries[0].amount").isEqualTo("100.00");
        assertBalance(merchant, "25.50");
        assertBalance(payouts, "98.50");
        assertBalance(fees, "1.50");

        var read = get(payout.getResponse().getHeader("Location"));

        assertThat(read).hasStatusOk();
        assertThat(read).bodyJson().isEqualTo(payout.getResponse().getContentAsString());
    }

    @Test
    void reverseATransactionByFlippingEveryEntry() throws Exception {

        var funding = account(EUR, "1000.00");
        var merchant = account(EUR, "0");
        var payouts = account(EUR, "0");
        var fees = account(EUR, "0");
        recordTransaction("Payment settled", debit(funding, "125.50"), credit(merchant, "125.50"));
        var payout = recordTransaction("Payout", debit(merchant, "100.00"), credit(payouts, "98.50"), credit(fees, "1.50"));

        var reversal = reverse(id(payout));

        assertThat(reversal).hasStatus(HttpStatus.CREATED);
        assertThat(reversal).bodyJson().extractingPath("$.reversalOf").isEqualTo(id(payout));
        assertThat(reversal).bodyJson().extractingPath("$.description").isEqualTo("Reversal of %s".formatted(id(payout)));
        assertThat(reversal).bodyJson()
                .extractingPath("$.entries[*].direction")
                .asArray()
                .containsExactly("CREDIT", "DEBIT", "DEBIT");
        assertBalance(merchant, "125.50");
        assertBalance(payouts, "0.00");
        assertBalance(fees, "0.00");
        assertThat(get(reversal.getResponse().getHeader("Location"))).bodyJson().isEqualTo(body(reversal));
        assertThat(get("/v1/transactions/{id}", id(payout))).bodyJson().isEqualTo(body(payout));
    }

    @Test
    void recordATransactionOnceAndAnswerItsRetryWithTheOriginalResponse() throws Exception {

        var funding = account(EUR, "1000.00");
        var merchant = account(EUR, "0");
        var key = newKey();
        var writtenDifferently = """
                {"entries":[{"amount":"10.00","direction":"DEBIT","accountId":"%s"},
                {"amount":"10.00","direction":"CREDIT","accountId":"%s"}],"description":"Payment settled"}
                """.formatted(funding.getId(), merchant.getId());

        var first = post(key, transaction("Payment settled", debit(funding, "10.00"), credit(merchant, "10.00")));
        var retry = post(key, writtenDifferently);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertSameResponse(retry, first);
        assertBalance(merchant, "10.00");
    }

    @Test
    void reverseATransactionOnceAndAnswerItsRetryWithTheOriginalResponse() throws Exception {

        var funding = account(EUR, "1000.00");
        var merchant = account(EUR, "0");
        var payment = recordTransaction("Payment settled", debit(funding, "10.00"), credit(merchant, "10.00"));
        var key = newKey();

        var first = reverse(key, id(payment));
        var retry = reverse(key, id(payment));

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertSameResponse(retry, first);
        assertBalance(merchant, "0.00");
    }

    @Test
    void refuseAnIdempotencyKeyReusedForADifferentRequest() throws Exception {

        var funding = account(EUR, "1000.00");
        var merchant = account(EUR, "0");
        var payment = recordTransaction("Payment settled", debit(funding, "10.00"), credit(merchant, "10.00"));
        var otherPayment = recordTransaction("Payment settled", debit(funding, "20.00"), credit(merchant, "20.00"));
        var transactionKey = newKey();
        var reversalKey = newKey();
        post(transactionKey, transaction("Payment settled", debit(funding, "1.00"), credit(merchant, "1.00")));
        reverse(reversalKey, id(payment));

        var otherBody = post(transactionKey, transaction("Payment settled", debit(funding, "2.00"), credit(merchant, "2.00")));
        var otherTransaction = reverse(reversalKey, id(otherPayment));

        assertThat(otherBody).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(otherBody).bodyJson().extractingPath("$.errorCode").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(otherTransaction).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(otherTransaction).bodyJson().extractingPath("$.errorCode").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertBalance(merchant, "21.00");
    }

    @Test
    void answerTheRetryOfARefusedRequestWithTheSameRefusal() throws Exception {

        var funding = account(EUR, "1000.00");
        var merchant = account(EUR, "0");
        var payouts = account(EUR, "0");
        var key = newKey();
        var payout = transaction("Payout", debit(merchant, "10.00"), credit(payouts, "10.00"));

        var refused = post(key, payout);
        recordTransaction("Payment settled", debit(funding, "10.00"), credit(merchant, "10.00"));
        var retry = post(key, payout);
        var anotherPayout = post(key, transaction("Payout", debit(merchant, "5.00"), credit(payouts, "5.00")));

        assertThat(refused).bodyJson().extractingPath("$.errorCode").isEqualTo("OVERDRAFT_LIMIT_EXCEEDED");
        assertSameResponse(retry, refused);
        assertThat(anotherPayout).bodyJson().extractingPath("$.errorCode").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertBalance(payouts, "0.00");
    }

    @Test
    void refuseToReverseATransactionTwiceOrToReverseAReversal() throws Exception {

        var funding = account(EUR, "1000.00");
        var merchant = account(EUR, "0");
        var payment = recordTransaction("Payment settled", debit(funding, "10.00"), credit(merchant, "10.00"));
        var reversal = reverse(id(payment));

        var reversedAgain = reverse(id(payment));
        var reversalReversed = reverse(id(reversal));

        assertThat(reversedAgain).hasStatus(HttpStatus.CONFLICT);
        assertThat(reversedAgain).bodyJson().extractingPath("$.errorCode").isEqualTo("TRANSACTION_ALREADY_REVERSED");
        assertThat(reversalReversed).hasStatus(HttpStatus.CONFLICT);
        assertThat(reversalReversed).bodyJson().extractingPath("$.errorCode").isEqualTo("REVERSAL_NOT_REVERSIBLE");
        assertBalance(merchant, "0.00");
        assertBalance(funding, "0.00");
    }

    @Test
    void refuseAReversalThatWouldTakeAnAccountPastItsOverdraftLimit() throws Exception {

        var funding = account(EUR, "1000.00");
        var merchant = account(EUR, "0");
        var payouts = account(EUR, "0");
        var payment = recordTransaction("Payment settled", debit(funding, "125.50"), credit(merchant, "125.50"));
        recordTransaction("Payout", debit(merchant, "100.00"), credit(payouts, "100.00"));
        var transactionsBefore = transactionRepository.count();

        var reversal = reverse(id(payment));

        assertThat(reversal).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(reversal).bodyJson().extractingPath("$.errorCode").isEqualTo("OVERDRAFT_LIMIT_EXCEEDED");
        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore);
        assertBalance(merchant, "25.50");
    }

    @Test
    void leaveNothingBehindWhenATransactionIsRefused() {

        var merchant = account(EUR, "0");
        var payouts = account(EUR, "0");
        var transactionsBefore = transactionRepository.count();

        var unbalanced = recordTransaction("Payout", debit(merchant, "100.00"), credit(payouts, "99.00"));
        var pastTheLimit = recordTransaction("Payout", debit(merchant, "50.00"), credit(payouts, "50.00"));

        assertThat(unbalanced).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(unbalanced).bodyJson().extractingPath("$.errorCode").isEqualTo("UNBALANCED");
        assertThat(pastTheLimit).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(pastTheLimit).bodyJson().extractingPath("$.errorCode").isEqualTo("OVERDRAFT_LIMIT_EXCEEDED");
        assertThat(pastTheLimit).bodyJson().extractingPath("$.message")
                .isEqualTo("Account %s would go to -50.00, past its overdraft limit of 0.00".formatted(merchant.getId()));
        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore);
        assertBalance(merchant, "0.00");
        assertBalance(payouts, "0.00");
    }

    @Test
    void reportATransactionThatDoesNotExist() {

        var read = get("/v1/transactions/{id}", UUID.randomUUID());
        var reversal = reverse(UUID.randomUUID().toString());

        assertThat(read).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(read).bodyJson().extractingPath("$.errorCode").isEqualTo("TRANSACTION_NOT_FOUND");
        assertThat(reversal).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(reversal).bodyJson().extractingPath("$.errorCode").isEqualTo("TRANSACTION_NOT_FOUND");
    }

    @Test
    void rejectMalformedRequests() {

        var noEntries = post("""
                {"description": "Payout"}
                """);
        var unknownDirection = post("""
                {"description": "Payout", "entries": [{"accountId": "%s", "direction": "SIDEWAYS", "amount": "1.00"}]}
                """.formatted(UUID.randomUUID()));

        var nullEntry = post("""
                {"description": "Payout", "entries": [null, null]}
                """);
        var entries = Collections.nCopies(101, entry(UUID.randomUUID(), "CREDIT", "1.00"));
        var tooManyEntries = recordTransaction("Payout", entries.toArray(String[]::new));
        var reversalOfNotAnId = reverse("not-a-uuid");
        var payout = transaction("Payout",
                entry(UUID.randomUUID(), "DEBIT", "1.00"), entry(UUID.randomUUID(), "CREDIT", "1.00"));
        var noIdempotencyKey = mvc.post()
                .uri("/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payout)
                .exchange();
        var reversalWithoutIdempotencyKey = mvc.post()
                .uri("/v1/transactions/{id}/reversal", UUID.randomUUID())
                .exchange();
        var emptyIdempotencyKey = post("", payout);
        var tooLongIdempotencyKey = post("k".repeat(256), payout);

        assertThat(noEntries).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(unknownDirection).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(nullEntry).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(tooManyEntries).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(reversalOfNotAnId).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(noIdempotencyKey).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(reversalWithoutIdempotencyKey).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(emptyIdempotencyKey).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(tooLongIdempotencyKey).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private MvcTestResult recordTransaction(String description, String... entries) {

        return post(transaction(description, entries));
    }

    private MvcTestResult post(String body) {

        return post(newKey(), body);
    }

    private MvcTestResult post(String key, String body) {

        return mvc.post()
                .uri("/v1/transactions")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult reverse(String transactionId) {

        return reverse(newKey(), transactionId);
    }

    private MvcTestResult reverse(String key, String transactionId) {

        return mvc.post()
                .uri("/v1/transactions/{id}/reversal", transactionId)
                .header("Idempotency-Key", key)
                .exchange();
    }

    private MvcTestResult get(String uri, Object... variables) {

        return mvc.get()
                .uri(uri, variables)
                .exchange();
    }

    private static String id(MvcTestResult created) throws Exception {

        return JsonPath.read(body(created), "$.id");
    }

    private static String body(MvcTestResult result) throws Exception {

        return result.getResponse().getContentAsString();
    }

    private static void assertSameResponse(MvcTestResult retry, MvcTestResult first) throws Exception {

        assertThat(retry).hasStatus(first.getResponse().getStatus());
        assertThat(body(retry)).isEqualTo(body(first));
        assertThat(retry.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
    }

    private static String newKey() {

        return UUID.randomUUID().toString();
    }

    private void assertBalance(Account account, String balance) {

        var read = get("/v1/accounts/{id}", account.getId());

        assertThat(read).bodyJson().extractingPath("$.balance").isEqualTo(balance);
    }

    private Account account(Currency currency, String overdraftLimit) {

        var account = new Account();
        account.setCurrency(currency);
        account.setOverdraftLimit(new BigDecimal(overdraftLimit));
        account.setCreatedAt(Instant.now());
        return accountRepository.save(account);
    }

    private static String transaction(String description, String... entries) {

        return """
                {"description": "%s", "entries": [%s]}
                """.formatted(description, String.join(", ", entries));
    }

    private static String debit(Account account, String amount) {

        return entry(account, "DEBIT", amount);
    }

    private static String credit(Account account, String amount) {

        return entry(account, "CREDIT", amount);
    }

    private static String entry(Account account, String direction, String amount) {

        return entry(account.getId(), direction, amount);
    }

    private static String entry(UUID accountId, String direction, String amount) {

        return """
                {"accountId": "%s", "direction": "%s", "amount": "%s"}""".formatted(accountId, direction, amount);
    }
}
