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

        assertThat(read).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(read).bodyJson().extractingPath("$.errorCode").isEqualTo("TRANSACTION_NOT_FOUND");
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

        assertThat(noEntries).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(unknownDirection).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(nullEntry).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(tooManyEntries).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private MvcTestResult recordTransaction(String description, String... entries) {

        return post("""
                {"description": "%s", "entries": [%s]}
                """.formatted(description, String.join(", ", entries)));
    }

    private MvcTestResult post(String body) {

        return mvc.post()
                .uri("/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult get(String uri, Object... variables) {

        return mvc.get()
                .uri(uri, variables)
                .exchange();
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
