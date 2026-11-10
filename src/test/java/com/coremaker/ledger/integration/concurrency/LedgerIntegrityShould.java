package com.coremaker.ledger.integration.concurrency;

import static com.coremaker.ledger.exception.ErrorCode.OVERDRAFT_LIMIT_EXCEEDED;
import static com.coremaker.ledger.exception.ErrorCode.TRANSACTION_ALREADY_REVERSED;
import static com.coremaker.ledger.integration.support.ConcurrentAttempts.runAtOnce;
import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Currency.JPY;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.coremaker.ledger.dto.request.EntryRequest;
import com.coremaker.ledger.dto.request.TransactionRequest;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.integration.support.SecondInstance;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.enums.Currency;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.service.TransactionService;

@IntegrationTest
class LedgerIntegrityShould {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private SecondInstance secondInstance;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcClient database;

    @RepeatedTest(5)
    void keepEveryCurrencyAtZeroWhileTransactionsAndReversalsRaceOnTwoInstances() throws Exception {

        var settlementEur = accountRepository.findSettlementAccountId(EUR).orElseThrow();
        var settlementJpy = accountRepository.findSettlementAccountId(JPY).orElseThrow();
        var eur = merchants(EUR);
        var jpy = merchants(JPY);
        var recorded = new CopyOnWriteArrayList<UUID>();
        for (int payment = 0; payment < 10; payment++) {

            recorded.add(transactionService.post(newKey(), transfer(settlementEur, eur)).id());
            recorded.add(transactionService.post(newKey(), transfer(settlementJpy, jpy)).id());
        }

        var load = ForkJoinPool.commonPool().submit(() -> runAtOnce(200, attempt -> {

            var instance = attempt % 2 == 0 ? transactionService : secondInstance.transactionService();
            var inEur = attempt % 4 < 2;
            var merchants = inEur ? eur : jpy;
            var settlement = inEur ? settlementEur : settlementJpy;
            switch (attempt % 3) {
                case 0 -> recorded.add(instance.post(newKey(), transfer(settlement, merchants)).id());
                case 1 -> recorded.add(instance.post(newKey(), transfer(pick(merchants), merchants)).id());
                default -> instance.reverse(newKey(), recorded.get(ThreadLocalRandom.current().nextInt(recorded.size())));
            }
        }));

        var brokenDuringLoad = new ArrayList<Map<String, Object>>();
        while (!load.isDone()) {

            brokenDuringLoad.addAll(brokenRules());
        }

        assertThat(brokenDuringLoad).isEmpty();
        assertThat(brokenRules()).isEmpty();
        assertThat(load.get()).allMatch(failure -> failure instanceof LedgerException refusal
                && Set.of(OVERDRAFT_LIMIT_EXCEEDED, TRANSACTION_ALREADY_REVERSED).contains(refusal.getCode()));
    }

    private List<Map<String, Object>> brokenRules() {

        var broken = new ArrayList<>(currenciesNotAtZero());
        broken.addAll(balancesNotEqualToTheirEntries());
        return broken;
    }

    private List<Map<String, Object>> currenciesNotAtZero() {

        return database.sql("""
                        SELECT currency, SUM(balance) AS total
                        FROM accounts
                        GROUP BY currency
                        HAVING total <> 0""")
                .query()
                .listOfRows();
    }

    private List<Map<String, Object>> balancesNotEqualToTheirEntries() {

        return database.sql("""
                        SELECT a.id, a.balance,
                               COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0) AS posted
                        FROM accounts a
                        LEFT JOIN entries e ON e.account_id = a.id
                        GROUP BY a.id, a.balance
                        HAVING a.balance <> posted""")
                .query()
                .listOfRows();
    }

    private List<Account> merchants(Currency currency) {

        return IntStream.range(0, 5)
                .mapToObj(merchant -> account(currency))
                .toList();
    }

    private Account account(Currency currency) {

        var account = new Account();
        account.setCurrency(currency);
        account.setCreatedAt(Instant.now());
        return accountRepository.save(account);
    }

    private static UUID pick(List<Account> accounts) {

        return accounts.get(ThreadLocalRandom.current().nextInt(accounts.size())).getId();
    }

    private static String newKey() {

        return UUID.randomUUID().toString();
    }

    private static TransactionRequest transfer(UUID from, List<Account> merchants) {

        var debit = new EntryRequest(from, DEBIT, BigDecimal.TEN);
        var credit = new EntryRequest(pick(merchants), CREDIT, BigDecimal.valueOf(9));
        var fee = new EntryRequest(pick(merchants), CREDIT, BigDecimal.ONE);
        return new TransactionRequest("Transfer", List.of(debit, credit, fee));
    }
}
