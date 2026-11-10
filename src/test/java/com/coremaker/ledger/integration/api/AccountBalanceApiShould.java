package com.coremaker.ledger.integration.api;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

@IntegrationTest
class AccountBalanceApiShould {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionService transactionService;

    @Test
    void giveTheBalanceAsAtAPastInstant() {

        var funding = account("1000.00");
        var merchant = account("0");
        var settled = transfer(funding, merchant, "125.50");
        var refunded = transfer(merchant, funding, "100.00");

        var balance = balance(merchant, settled.recordedAt().toString());

        assertThat(balance).hasStatusOk();
        assertThat(balance).bodyJson().isStrictlyEqualTo("""
                {"accountId": "%s", "currency": "EUR", "balance": "125.50", "asOf": "%s"}
                """.formatted(merchant.getId(), settled.recordedAt()));
        assertBalance(merchant, justBefore(settled), "0.00");
        assertBalance(merchant, justBefore(refunded), "125.50");
        assertBalance(merchant, Instant.now().plus(1, ChronoUnit.DAYS).toString(), "25.50");
    }

    @Test
    void rejectARequestWithoutAnInstant() {

        var noInstant = mvc.get()
                .uri("/v1/accounts/{id}/balance", account("0").getId())
                .exchange();

        assertThat(noInstant).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private void assertBalance(Account account, String asOf, String expected) {

        assertThat(balance(account, asOf)).bodyJson().extractingPath("$.balance").isEqualTo(expected);
    }

    private MvcTestResult balance(Account account, String asOf) {

        return mvc.get()
                .uri("/v1/accounts/{id}/balance", account.getId())
                .param("asOf", asOf)
                .exchange();
    }

    private TransactionResponse transfer(Account from, Account to, String amount) {

        return record(entry(from.getId(), DEBIT, amount), entry(to.getId(), CREDIT, amount));
    }

    private TransactionResponse record(EntryRequest... entries) {

        return transactionService.post(UUID.randomUUID().toString(), new TransactionRequest("Payment", List.of(entries)));
    }

    private static String justBefore(TransactionResponse transaction) {

        return transaction.recordedAt().minusNanos(1).toString();
    }

    private static EntryRequest entry(UUID accountId, Direction direction, String amount) {

        return new EntryRequest(accountId, direction, new BigDecimal(amount));
    }

    private Account account(String overdraftLimit) {

        var account = new Account();
        account.setCurrency(EUR);
        account.setOverdraftLimit(new BigDecimal(overdraftLimit));
        account.setCreatedAt(Instant.now());
        return accountRepository.save(account);
    }
}
