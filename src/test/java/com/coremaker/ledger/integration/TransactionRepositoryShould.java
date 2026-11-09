package com.coremaker.ledger.integration;

import static com.coremaker.ledger.model.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.Direction;
import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.Transaction;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.TransactionRepository;

@IntegrationTest
class TransactionRepositoryShould {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void saveATransactionWithItsEntriesAndReadItBack() {

        var payer = account();
        var payee = account();
        var transaction = transaction(entry(payer.getId(), Direction.DEBIT, "125.50", "-125.50"),
                entry(payee.getId(), Direction.CREDIT, "125.50", "125.50"));

        var saved = transactionRepository.save(transaction);
        var found = transactionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getDescription()).isEqualTo("Payment settled");
        assertThat(found.getRecordedAt()).isEqualTo(transaction.getRecordedAt());
        assertThat(found.getEntries())
                .extracting(Entry::getAccountId, Entry::getDirection)
                .containsExactly(tuple(payer.getId(), Direction.DEBIT), tuple(payee.getId(), Direction.CREDIT));
        assertThat(found.getEntries()).extracting(Entry::getAmount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("125.50"), new BigDecimal("125.50"));
        assertThat(found.getEntries()).extracting(Entry::getBalanceAfter).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("-125.50"), new BigDecimal("125.50"));
    }

    @Test
    void neverUpdateARecordedTransaction() {

        var payer = account();
        var payee = account();
        var saved = transactionRepository.save(transaction(entry(payer.getId(), Direction.DEBIT, "10.00", "-10.00"),
                entry(payee.getId(), Direction.CREDIT, "10.00", "10.00")));

        saved.setDescription("Changed");
        transactionRepository.save(saved);

        assertThat(transactionRepository.findById(saved.getId()).orElseThrow().getDescription()).isEqualTo("Payment settled");
    }

    private Account account() {

        var account = new Account();
        account.setCurrency(EUR);
        account.setCreatedAt(Instant.now());
        return accountRepository.save(account);
    }

    private static Transaction transaction(Entry... entries) {

        var transaction = new Transaction();
        transaction.setDescription("Payment settled");
        transaction.setRecordedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        transaction.setEntries(List.of(entries));
        return transaction;
    }

    private static Entry entry(UUID accountId, Direction direction, String amount, String balanceAfter) {

        var entry = new Entry();
        entry.setAccountId(accountId);
        entry.setDirection(direction);
        entry.setAmount(new BigDecimal(amount));
        entry.setBalanceAfter(new BigDecimal(balanceAfter));
        return entry;
    }
}
