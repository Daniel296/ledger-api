package com.coremaker.ledger.integration.concurrency;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import com.coremaker.ledger.dto.request.EntryRequest;
import com.coremaker.ledger.dto.request.RecordTransactionRequest;
import com.coremaker.ledger.exception.ValidationException;
import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.service.TransactionService;

@IntegrationTest
class ConcurrentTransactionsShould {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @RepeatedTest(5)
    void neverTakeAnAccountPastItsOverdraftLimit() throws Exception {

        var funding = account("1000000.00");
        var merchant = account("0");
        var payouts = account("0");
        transactionService.post(transfer(funding, merchant, "100.00"));

        var failures = runAtOnce(20, attempt -> transfer(merchant, payouts, "10.00"));

        assertThat(failures).hasSize(10).allMatch(ValidationException.class::isInstance);
        assertThat(balance(merchant)).isEqualByComparingTo("0");
        assertThat(balance(payouts)).isEqualByComparingTo("100.00");
    }

    @RepeatedTest(5)
    void neverLoseAnUpdate() throws Exception {

        var funding = account("1000000.00");
        var merchant = account("0");

        var failures = runAtOnce(50, attempt -> transfer(funding, merchant, "1.00"));

        assertThat(failures).isEmpty();
        assertThat(balance(merchant)).isEqualByComparingTo("50.00");
        assertThat(balance(funding)).isEqualByComparingTo("-50.00");
    }

    @RepeatedTest(5)
    void notDeadlockWhenMoneyMovesBothWaysAtOnce() throws Exception {

        var first = account("1000.00");
        var second = account("1000.00");

        var failures = runAtOnce(40, attempt -> attempt % 2 == 0
                ? transfer(first, second, "1.00")
                : transfer(second, first, "1.00"));

        assertThat(failures).isEmpty();
        assertThat(balance(first)).isZero();
        assertThat(balance(second)).isZero();
    }

    private List<Throwable> runAtOnce(int attempts, IntFunction<RecordTransactionRequest> request) throws Exception {

        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(attempts);
        try {
            var outcomes = new ArrayList<Future<Throwable>>();
            for (int attempt = 0; attempt < attempts; attempt++) {

                var transfer = request.apply(attempt);
                outcomes.add(executor.submit(() -> {

                    start.await();
                    return post(transfer);
                }));
            }

            start.countDown();
            var failures = new ArrayList<Throwable>();
            for (var outcome : outcomes) {

                if (outcome.get() != null) {
                    failures.add(outcome.get());
                }
            }

            return failures;
        } finally {
            executor.shutdown();
        }
    }

    private Throwable post(RecordTransactionRequest transfer) {

        try {
            transactionService.post(transfer);
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
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

    private static RecordTransactionRequest transfer(Account from, Account to, String amount) {

        var debit = new EntryRequest(from.getId(), DEBIT, new BigDecimal(amount));
        var credit = new EntryRequest(to.getId(), CREDIT, new BigDecimal(amount));
        return new RecordTransactionRequest("Transfer", List.of(debit, credit));
    }
}
