package com.coremaker.ledger.integration.api;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.coremaker.ledger.dto.request.EntryRequest;
import com.coremaker.ledger.dto.request.TransactionRequest;
import com.coremaker.ledger.dto.response.TransactionResponse;
import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.enums.Direction;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.service.TransactionService;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class AccountEntriesApiShould {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionService transactionService;

    @Test
    void listTheEntriesNewestFirstWithTheBalanceAfterEachReversalsIncluded() {

        var funding = account("1000.00");
        var merchant = account("0");
        var payouts = account("0");
        var fees = account("0");
        var settled = transfer(funding, merchant, "125.50");
        var payout = record(entry(merchant.getId(), DEBIT, "100.00"),
                entry(payouts.getId(), CREDIT, "98.50"), entry(fees.getId(), CREDIT, "1.50"));
        var reversal = transactionService.reverse(newKey(), payout.id());

        var statement = get("/v1/accounts/{id}/entries", merchant.getId());

        assertThat(statement).hasStatusOk();
        assertThat(statement).bodyJson().isStrictlyEqualTo("""
                {"entries": [
                  {"transactionId": "%s", "direction": "CREDIT", "amount": "100.00", "balanceAfter": "125.50",
                   "recordedAt": "%s"},
                  {"transactionId": "%s", "direction": "DEBIT", "amount": "100.00", "balanceAfter": "25.50",
                   "recordedAt": "%s"},
                  {"transactionId": "%s", "direction": "CREDIT", "amount": "125.50", "balanceAfter": "125.50",
                   "recordedAt": "%s"}
                ]}
                """.formatted(reversal.id(), reversal.recordedAt(), payout.id(), payout.recordedAt(),
                settled.id(), settled.recordedAt()));
    }

    @Test
    void pageThroughTheEntriesWithoutAGapOrARepeatWhileNewOnesAreRecorded() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");
        for (int payment = 0; payment < 5; payment++) {

            transfer(funding, merchant, "1.00");
        }

        var first = get("/v1/accounts/{id}/entries?limit=2", merchant.getId());
        transfer(funding, merchant, "1.00");
        var second = get("/v1/accounts/{id}/entries?limit=2&cursor={cursor}", merchant.getId(), cursor(first));
        transfer(funding, merchant, "1.00");
        var last = get("/v1/accounts/{id}/entries?limit=2&cursor={cursor}", merchant.getId(), cursor(second));

        assertThat(balancesAfter(first)).containsExactly("5.00", "4.00");
        assertThat(balancesAfter(second)).containsExactly("3.00", "2.00");
        assertThat(balancesAfter(last)).containsExactly("1.00");
        assertThat(last).bodyJson().doesNotHavePath("$.nextCursor");
    }

    @Test
    void reportAnAccountThatDoesNotExist() {

        var statement = get("/v1/accounts/{id}/entries", UUID.randomUUID());

        assertThat(statement).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(statement).bodyJson().extractingPath("$.errorCode").isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void rejectALimitOutsideOneToAHundred() {

        var merchant = account("0").getId();

        assertThat(get("/v1/accounts/{id}/entries?limit=0", merchant)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get("/v1/accounts/{id}/entries?limit=101", merchant)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private TransactionResponse transfer(Account from, Account to, String amount) {

        return record(entry(from.getId(), DEBIT, amount), entry(to.getId(), CREDIT, amount));
    }

    private TransactionResponse record(EntryRequest... entries) {

        return transactionService.post(newKey(), new TransactionRequest("Payment", List.of(entries)));
    }

    private MvcTestResult get(String uri, Object... variables) {

        return mvc.get()
                .uri(uri, variables)
                .exchange();
    }

    private static String cursor(MvcTestResult page) throws Exception {

        return JsonPath.read(page.getResponse().getContentAsString(), "$.nextCursor");
    }

    private static List<String> balancesAfter(MvcTestResult page) throws Exception {

        return JsonPath.read(page.getResponse().getContentAsString(), "$.entries[*].balanceAfter");
    }

    private static EntryRequest entry(UUID accountId, Direction direction, String amount) {

        return new EntryRequest(accountId, direction, new BigDecimal(amount));
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
}
