package com.coremaker.ledger.integration.concurrency;

import static com.coremaker.ledger.integration.support.ConcurrentAttempts.runAtOnce;
import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;

import com.coremaker.ledger.dto.request.EntryRequest;
import com.coremaker.ledger.dto.request.TransactionRequest;
import com.coremaker.ledger.integration.support.Announcements;
import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.integration.support.SecondInstance;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.service.TransactionService;

@IntegrationTest
class ConcurrentAnnouncementsShould {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private SecondInstance secondInstance;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @RepeatedTest(5)
    void announceEveryTransactionOnceInTheOrderItWasAppliedWhenBothInstancesRecordAndPublish() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            var failures = runAtOnce(20, attempt -> {

                var payment = instance(attempt).post(newKey(), transfer(funding, merchant));
                instance(attempt + 1).reverse(newKey(), payment.id());
            });

            assertThat(failures).isEmpty();
            assertThat(announcements.firstTransactionIds(40))
                    .doesNotHaveDuplicates()
                    .containsExactlyElementsOf(transactionsInTheOrderTheyWereApplied(funding));
        }
    }

    private List<String> transactionsInTheOrderTheyWereApplied(Account account) {

        return jdbcTemplate.queryForList("SELECT transaction_id FROM entries WHERE account_id = ? ORDER BY id",
                String.class, account.getId().toString());
    }

    private TransactionService instance(int attempt) {

        return attempt % 2 == 0 ? transactionService : secondInstance.transactionService();
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

    private static TransactionRequest transfer(Account from, Account to) {

        var debit = new EntryRequest(from.getId(), DEBIT, new BigDecimal("10.00"));
        var credit = new EntryRequest(to.getId(), CREDIT, new BigDecimal("10.00"));
        return new TransactionRequest("Transfer", List.of(debit, credit));
    }
}
