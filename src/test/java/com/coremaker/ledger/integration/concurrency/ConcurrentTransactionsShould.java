package com.coremaker.ledger.integration.concurrency;

import static com.coremaker.ledger.exception.ErrorCode.OVERDRAFT_LIMIT_EXCEEDED;
import static com.coremaker.ledger.exception.ErrorCode.TRANSACTION_ALREADY_REVERSED;
import static com.coremaker.ledger.integration.support.ConcurrentAttempts.runAtOnce;
import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import com.coremaker.ledger.dto.request.EntryRequest;
import com.coremaker.ledger.dto.request.TransactionRequest;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.integration.support.SecondInstance;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.service.TransactionService;

@IntegrationTest
class ConcurrentTransactionsShould {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private SecondInstance secondInstance;

    @Autowired
    private AccountRepository accountRepository;

    @RepeatedTest(5)
    void neverTakeAnAccountPastItsOverdraftLimit() throws Exception {

        var merchant = account("100.00");
        var payouts = account("0");

        var failures = runAtOnce(20, attempt -> instance(attempt).post(newKey(), transfer(merchant, payouts, "10.00")));

        assertThat(failures)
                .hasSize(10)
                .allMatch(failure -> failure instanceof LedgerException refusal
                        && refusal.getCode() == OVERDRAFT_LIMIT_EXCEEDED);
        assertThat(balance(merchant)).isEqualByComparingTo("-100.00");
        assertThat(balance(payouts)).isEqualByComparingTo("100.00");
    }

    @RepeatedTest(5)
    void neverLoseAnUpdate() throws Exception {

        var funding = account("1000000.00");
        var merchant = account("0");

        var failures = runAtOnce(50, attempt -> instance(attempt).post(newKey(), transfer(funding, merchant, "1.00")));

        assertThat(failures).isEmpty();
        assertThat(balance(merchant)).isEqualByComparingTo("50.00");
        assertThat(balance(funding)).isEqualByComparingTo("-50.00");
    }

    @RepeatedTest(5)
    void notDeadlockWhenMoneyMovesBothWaysAtOnce() throws Exception {

        var first = account("1000.00");
        var second = account("1000.00");

        var failures = runAtOnce(40, attempt -> instance(attempt).post(newKey(), attempt % 4 < 2
                ? transfer(first, second, "1.00")
                : transfer(second, first, "1.00")));

        assertThat(failures).isEmpty();
        assertThat(balance(first)).isZero();
        assertThat(balance(second)).isZero();
    }

    @RepeatedTest(5)
    void reverseATransactionOnlyOnceWhenBothInstancesReverseItAtOnce() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");
        var payment = transactionService.post(newKey(), transfer(funding, merchant, "100.00"));

        var failures = runAtOnce(10, attempt -> instance(attempt).reverse(newKey(), payment.id()));

        assertThat(failures)
                .hasSize(9)
                .allMatch(failure -> failure instanceof LedgerException refusal
                        && refusal.getCode() == TRANSACTION_ALREADY_REVERSED);
        assertThat(balance(merchant)).isZero();
        assertThat(balance(funding)).isZero();
    }

    @RepeatedTest(5)
    void recordATransactionOnceWhenBothInstancesGetTheSameIdempotencyKeyAtOnce() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");
        var key = newKey();
        var answered = ConcurrentHashMap.<UUID>newKeySet();

        var failures = runAtOnce(10, attempt -> {

            var payment = instance(attempt).post(key, transfer(funding, merchant, "10.00"));
            answered.add(payment.id());
        });

        assertThat(failures).isEmpty();
        assertThat(answered).hasSize(1);
        assertThat(balance(merchant)).isEqualByComparingTo("10.00");
    }

    private TransactionService instance(int attempt) {

        return attempt % 2 == 0 ? transactionService : secondInstance.transactionService();
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

    private static String newKey() {

        return UUID.randomUUID().toString();
    }

    private static TransactionRequest transfer(Account from, Account to, String amount) {

        var debit = new EntryRequest(from.getId(), DEBIT, new BigDecimal(amount));
        var credit = new EntryRequest(to.getId(), CREDIT, new BigDecimal(amount));
        return new TransactionRequest("Transfer", List.of(debit, credit));
    }
}
