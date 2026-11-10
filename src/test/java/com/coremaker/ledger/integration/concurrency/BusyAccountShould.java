package com.coremaker.ledger.integration.concurrency;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.repository.AccountRepository;
import com.zaxxer.hikari.HikariDataSource;

@IntegrationTest
class BusyAccountShould {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private HikariDataSource pool;

    @Autowired
    private JdbcConnectionDetails database;

    @Test
    void answerBusyThenServeOtherAccountsWhenEveryConnectionWaitsOnALockedOne() throws Exception {

        var locked = account();
        var payee = account();
        var other = account();
        var otherPayee = account();
        var waiting = Executors.newFixedThreadPool(pool.getMaximumPoolSize());

        try (var lockHolder = DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword())) {

            lockHolder.setAutoCommit(false);
            try (var lock = lockHolder.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {

                lock.setString(1, locked.getId().toString());
                lock.executeQuery();
            }

            var refused = IntStream.range(0, pool.getMaximumPoolSize())
                    .mapToObj(request -> CompletableFuture.supplyAsync(() -> transfer(locked, payee), waiting))
                    .toList();
            await().until(() -> pool.getHikariPoolMXBean().getActiveConnections() == pool.getMaximumPoolSize());

            assertThat(transfer(other, otherPayee)).hasStatus(HttpStatus.CREATED);
            for (var response : refused) {

                assertThat(response.get())
                        .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .hasHeader("Retry-After", "1")
                        .bodyJson()
                        .extractingPath("$.errorCode")
                        .isEqualTo("ACCOUNT_BUSY");
            }

            lockHolder.rollback();
        } finally {
            waiting.shutdown();
        }

        assertThat(balance(locked)).isZero();
        assertThat(balance(payee)).isZero();
    }

    private MvcTestResult transfer(Account from, Account to) {

        return mvc.post()
                .uri("/v1/transactions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"description": "Transfer", "entries": [
                            {"accountId": "%s", "direction": "DEBIT", "amount": "1.00"},
                            {"accountId": "%s", "direction": "CREDIT", "amount": "1.00"}]}
                        """.formatted(from.getId(), to.getId()))
                .exchange();
    }

    private BigDecimal balance(Account account) {

        return accountRepository.findById(account.getId())
                .orElseThrow()
                .getBalance();
    }

    private Account account() {

        var account = new Account();
        account.setCurrency(EUR);
        account.setOverdraftLimit(new BigDecimal("100.00"));
        account.setCreatedAt(Instant.now());
        return accountRepository.save(account);
    }
}
