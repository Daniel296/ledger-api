package com.coremaker.ledger.unit.service;

import static com.coremaker.ledger.exception.ErrorCode.TRANSACTION_NOT_FOUND;
import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Currency.JPY;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.coremaker.ledger.dto.request.EntryRequest;
import com.coremaker.ledger.dto.request.TransactionRequest;
import com.coremaker.ledger.dto.response.EntryResponse;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.Transaction;
import com.coremaker.ledger.model.enums.Currency;
import com.coremaker.ledger.model.enums.Direction;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.TransactionRepository;
import com.coremaker.ledger.service.TransactionService;
import com.coremaker.ledger.validation.TransactionValidator;

class TransactionServiceShould {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final TransactionService transactionService =
            new TransactionService(accountRepository, transactionRepository, new TransactionValidator());

    @Test
    void moveTheMoneyAndRecordTheBalanceAfterEachEntry() {

        var merchant = account(EUR, "125.50");
        var payouts = account(EUR, "0");
        var fees = account(EUR, "0");
        lockable(merchant, payouts, fees);
        when(transactionRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));

        var recorded = transactionService.post(request(
                entry(merchant, DEBIT, "100.00"), entry(payouts, CREDIT, "98.50"), entry(fees, CREDIT, "1.50")));

        assertThat(merchant.getBalance()).isEqualByComparingTo("25.50");
        assertThat(payouts.getBalance()).isEqualByComparingTo("98.50");
        assertThat(fees.getBalance()).isEqualByComparingTo("1.50");
        assertThat(recorded.entries()).extracting(EntryResponse::amount).containsExactly("100.00", "98.50", "1.50");

        var saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(saved.capture());
        assertThat(saved.getValue().getCurrency()).isEqualTo(EUR);
        assertThat(saved.getValue().getRecordedAt()).isNotNull();
        assertThat(saved.getValue().getEntries())
                .extracting(Entry::getBalanceAfter)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("25.50"), new BigDecimal("98.50"), new BigDecimal("1.50"));
    }

    @Test
    void recordTheRunningBalanceWhenAnAccountIsPostedToTwice() {

        var merchant = account(EUR, "25.50");
        var payouts = account(EUR, "0");
        lockable(merchant, payouts);
        when(transactionRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));

        transactionService.post(request(
                entry(merchant, DEBIT, "50.00"), entry(merchant, CREDIT, "30.00"), entry(payouts, CREDIT, "20.00")));

        var saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(saved.capture());
        assertThat(saved.getValue().getEntries())
                .extracting(Entry::getBalanceAfter)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("-24.50"), new BigDecimal("5.50"), new BigDecimal("20.00"));
    }

    @Test
    void saveNothingAndChangeNoBalanceWhenARuleIsBroken() {

        var merchant = account(EUR, "25.50");
        var payouts = account(EUR, "0");
        lockable(merchant, payouts);

        var unbalanced = request(entry(merchant, DEBIT, "100.00"), entry(payouts, CREDIT, "99.00"));

        assertThatThrownBy(() -> transactionService.post(unbalanced)).isInstanceOf(LedgerException.class);

        verify(transactionRepository, never()).save(any());
        assertThat(merchant.getBalance()).isEqualByComparingTo("25.50");
        assertThat(payouts.getBalance()).isZero();
    }

    @Test
    void showAmountsWithTheDecimalPlacesOfTheCurrency() {

        var transaction = withId(new Transaction());
        transaction.setCurrency(JPY);
        transaction.setDescription("Payment settled");
        transaction.setRecordedAt(Instant.now());
        transaction.setEntries(List.of(
                entry(UUID.randomUUID(), DEBIT, "15000.0000"),
                entry(UUID.randomUUID(), CREDIT, "15000.0000")));
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));

        var read = transactionService.get(transaction.getId());

        assertThat(read.entries()).extracting(EntryResponse::amount).containsExactly("15000", "15000");
    }

    @Test
    void reportATransactionThatDoesNotExist() {

        var id = UUID.randomUUID();
        when(transactionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.get(id))
                .isInstanceOfSatisfying(LedgerException.class,
                        notFound -> assertThat(notFound.getCode()).isEqualTo(TRANSACTION_NOT_FOUND));
    }

    private void lockable(Account... accounts) {

        when(accountRepository.findAllByIdForUpdate(any())).thenReturn(List.of(accounts));
    }

    private static Account account(Currency currency, String balance) {

        var account = new Account();
        account.setId(UUID.randomUUID());
        account.setCurrency(currency);
        account.setBalance(new BigDecimal(balance));
        return account;
    }

    private static TransactionRequest request(EntryRequest... entries) {

        return new TransactionRequest("Payout", List.of(entries));
    }

    private static EntryRequest entry(Account account, Direction direction, String amount) {

        return new EntryRequest(account.getId(), direction, new BigDecimal(amount));
    }

    private static Entry entry(UUID accountId, Direction direction, String amount) {

        var entry = new Entry();
        entry.setAccountId(accountId);
        entry.setDirection(direction);
        entry.setAmount(new BigDecimal(amount));
        return entry;
    }

    private static Transaction withId(Transaction transaction) {

        transaction.setId(UUID.randomUUID());
        return transaction;
    }
}
