package com.coremaker.ledger.integration.api;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.coremaker.ledger.dto.request.EntryRequest;
import com.coremaker.ledger.dto.request.TransactionRequest;
import com.coremaker.ledger.dto.response.TransactionResponse;
import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.IdempotentRequestRepository;
import com.coremaker.ledger.repository.TransactionRepository;
import com.coremaker.ledger.service.IdempotencyService;
import com.coremaker.ledger.service.TransactionService;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class RetriedRequestsShould {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private IdempotentRequestRepository idempotentRequestRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void openAnAccountOnceAndAnswerTheRetryWithTheOriginalResponse() throws Exception {

        var key = newKey();
        var accountsBefore = accountRepository.count();

        var first = open(key, "EUR");
        var opened = accountRepository.findById(id(first)).orElseThrow();
        opened.setBalance(new BigDecimal("10.00"));
        accountRepository.save(opened);
        var retry = open(key, "EUR");

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertSameResponse(retry, first);
        assertThat(retry).bodyJson().extractingPath("$.balance").isEqualTo("0.00");
        assertThat(accountRepository.count()).isEqualTo(accountsBefore + 1);
    }

    @Test
    void recordATransactionOnceAndAnswerTheRetryWithTheOriginalResponse() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");
        var key = newKey();

        var first = post(key, transfer(funding, merchant, "10.00"));
        var retry = post(key, transfer(funding, merchant, "10.00"));

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertSameResponse(retry, first);
        assertBalance(merchant, "10.00");
    }

    @Test
    void reverseATransactionOnceAndAnswerTheRetryWithTheOriginalResponse() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");
        var payment = post(newKey(), transfer(funding, merchant, "10.00"));
        var key = newKey();

        var first = reverse(key, id(payment));
        var retry = reverse(key, id(payment));

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertSameResponse(retry, first);
        assertBalance(merchant, "0.00");
    }

    @Test
    void treatTheSameBodyWrittenDifferentlyAsARetry() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");
        var key = newKey();
        var reordered = """
                {"entries":[{"amount":"10.00","direction":"DEBIT","accountId":"%s"},
                {"amount":"10.00","direction":"CREDIT","accountId":"%s"}],"description":"Transfer"}
                """.formatted(funding.getId(), merchant.getId());

        var first = post(key, transfer(funding, merchant, "10.00"));
        var retry = post(key, reordered);

        assertSameResponse(retry, first);
        assertBalance(merchant, "10.00");
    }

    @Test
    void refuseAKeyReusedForADifferentRequest() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");
        var payment = post(newKey(), transfer(funding, merchant, "10.00"));
        var otherPayment = post(newKey(), transfer(funding, merchant, "20.00"));
        var transactionKey = newKey();
        var reversalKey = newKey();
        post(transactionKey, transfer(funding, merchant, "1.00"));
        reverse(reversalKey, id(payment));
        var accountsBefore = accountRepository.count();

        assertKeyReused(post(transactionKey, transfer(funding, merchant, "2.00")));
        assertKeyReused(reverse(reversalKey, id(otherPayment)));
        assertKeyReused(open(transactionKey, "EUR"));

        assertThat(accountRepository.count()).isEqualTo(accountsBefore);
        assertBalance(merchant, "21.00");
    }

    @Test
    void treatKeysThatDifferOnlyInCaseAsDifferentKeys() throws Exception {

        var key = "case-%s".formatted(UUID.randomUUID());

        var lowerCase = open(key, "EUR");
        var upperCase = open(key.toUpperCase(), "EUR");

        assertThat(upperCase).hasStatus(HttpStatus.CREATED);
        assertThat(id(upperCase)).isNotEqualTo(id(lowerCase));
    }

    @Test
    void rejectARequestWithoutAValidIdempotencyKey() {

        var funding = account("1000.00");
        var merchant = account("0");
        var transactionsBefore = transactionRepository.count();

        var noKeyToOpen = mvc.post()
                .uri("/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(accountIn("EUR"))
                .exchange();
        var noKeyToPost = mvc.post()
                .uri("/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transfer(funding, merchant, "10.00"))
                .exchange();
        var noKeyToReverse = mvc.post()
                .uri("/v1/transactions/{id}/reversal", UUID.randomUUID())
                .exchange();
        var emptyKey = post("", transfer(funding, merchant, "10.00"));
        var tooLongKey = post("k".repeat(256), transfer(funding, merchant, "10.00"));

        assertThat(noKeyToOpen).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(noKeyToPost).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(noKeyToReverse).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(emptyKey).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(tooLongKey).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore);
    }

    @Test
    void judgeARefusedRequestAgainWhenItIsRetried() {

        var funding = account("1000.00");
        var merchant = account("0");
        var payouts = account("0");
        var key = newKey();

        var refused = post(key, transfer(merchant, payouts, "10.00"));
        post(newKey(), transfer(funding, merchant, "10.00"));
        var retry = post(key, transfer(merchant, payouts, "10.00"));

        assertThat(refused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertBalance(payouts, "10.00");
    }

    @Test
    void recordATransactionOnceWhenTheFirstAttemptDiedBeforeCommitting() {

        var funding = account("1000.00");
        var merchant = account("0");
        var key = newKey();
        var transactionsBefore = transactionRepository.count();

        assertThatThrownBy(() -> idempotencyService.commitOnce(key, "recordTransaction", transferRequest(funding, merchant),
                TransactionResponse.class, () -> {

                    merchant.setBalance(new BigDecimal("10.00"));
                    accountRepository.saveAndFlush(merchant);
                    throw new IllegalStateException("The instance died before committing");
                })).isInstanceOf(IllegalStateException.class);

        assertBalance(merchant, "0.00");
        assertThat(idempotentRequestRepository.findById(key).orElseThrow().getResponse()).isNull();

        var retry = post(key, transfer(funding, merchant, "10.00"));

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore + 1);
        assertBalance(merchant, "10.00");
    }

    @Test
    void neverJoinTheCallersTransaction() {

        var request = transferRequest(account("1000.00"), account("0"));

        transactionTemplate.executeWithoutResult(callersTransaction -> assertThatThrownBy(
                () -> transactionService.post(newKey(), request)).isInstanceOf(IllegalTransactionStateException.class));
    }

    private MvcTestResult open(String key, String currency) {

        return mvc.post()
                .uri("/v1/accounts")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(accountIn(currency))
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

    private static void assertSameResponse(MvcTestResult retry, MvcTestResult first) throws Exception {

        assertThat(retry).hasStatus(first.getResponse().getStatus());
        assertThat(body(retry)).isEqualTo(body(first));
        assertThat(retry.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
    }

    private static void assertKeyReused(MvcTestResult refused) {

        assertThat(refused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused).bodyJson().extractingPath("$.errorCode").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    private void assertBalance(Account account, String balance) {

        var read = mvc.get()
                .uri("/v1/accounts/{id}", account.getId())
                .exchange();

        assertThat(read).bodyJson().extractingPath("$.balance").isEqualTo(balance);
    }

    private static UUID id(MvcTestResult created) throws Exception {

        return UUID.fromString(JsonPath.read(body(created), "$.id"));
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

    private static String accountIn(String currency) {

        return """
                {"currency": "%s"}
                """.formatted(currency);
    }

    private static TransactionRequest transferRequest(Account from, Account to) {

        return new TransactionRequest("Transfer", List.of(
                new EntryRequest(from.getId(), DEBIT, new BigDecimal("10.00")),
                new EntryRequest(to.getId(), CREDIT, new BigDecimal("10.00"))));
    }

    private static String transfer(Account from, Account to, String amount) {

        return """
                {"description": "Transfer", "entries": [
                {"accountId": "%s", "direction": "DEBIT", "amount": "%s"},
                {"accountId": "%s", "direction": "CREDIT", "amount": "%s"}]}
                """.formatted(from.getId(), amount, to.getId(), amount);
    }
}
