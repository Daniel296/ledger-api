package com.coremaker.ledger.unit.service;

import static com.coremaker.ledger.exception.ErrorCode.TRANSACTION_ALREADY_REVERSED;
import static com.coremaker.ledger.exception.ErrorCode.TRANSACTION_NOT_FOUND;
import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Currency.JPY;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.coremaker.ledger.dto.message.PaymentSettled;
import com.coremaker.ledger.dto.request.EntryRequest;
import com.coremaker.ledger.dto.request.TransactionRequest;
import com.coremaker.ledger.dto.response.EntryResponse;
import com.coremaker.ledger.dto.response.TransactionResponse;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.Transaction;
import com.coremaker.ledger.model.enums.Currency;
import com.coremaker.ledger.model.enums.Direction;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.TransactionRepository;
import com.coremaker.ledger.service.IdempotencyService;
import com.coremaker.ledger.service.OutboxService;
import com.coremaker.ledger.service.TransactionService;
import com.coremaker.ledger.validation.ReversalValidator;
import com.coremaker.ledger.validation.TransactionValidator;

class TransactionServiceShould {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final TransactionService transactionService = new TransactionService(accountRepository, transactionRepository,
            new TransactionValidator(), new ReversalValidator(), idempotencyService, outboxService);

    @BeforeEach
    void runEveryRequestAsANewOne() {

        when(idempotencyService.commitOnce(any(), any(), any(), any(), any()))
                .then(call -> call.<Supplier<?>>getArgument(4).get());
    }

    @Test
    void moveTheMoneyAndRecordTheBalanceAfterEachEntry() {

        var merchant = account(EUR, "125.50");
        var payouts = account(EUR, "0");
        var fees = account(EUR, "0");
        lockable(merchant, payouts, fees);
        when(transactionRepository.save(any())).then(returnsFirstArg());

        var recorded = transactionService.post("key", request(
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
        verify(outboxService).add(recorded);
    }

    @Test
    void recordTheRunningBalanceWhenAnAccountIsPostedToTwice() {

        var merchant = account(EUR, "25.50");
        var payouts = account(EUR, "0");
        lockable(merchant, payouts);
        when(transactionRepository.save(any())).then(returnsFirstArg());

        transactionService.post("key", request(
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

        assertThatThrownBy(() -> transactionService.post("key", unbalanced)).isInstanceOf(LedgerException.class);

        verify(transactionRepository, never()).save(any());
        verify(outboxService, never()).add(any());
        assertThat(merchant.getBalance()).isEqualByComparingTo("25.50");
        assertThat(payouts.getBalance()).isZero();
    }

    @Test
    void showAmountsWithTheDecimalPlacesOfTheCurrency() {

        var transaction = recorded(JPY,
                entry(UUID.randomUUID(), DEBIT, "15000.0000"),
                entry(UUID.randomUUID(), CREDIT, "15000.0000"));

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

    @Test
    void reverseEveryEntryAndLinkTheReversalToTheOriginal() {

        var merchant = account(EUR, "25.50");
        var payouts = account(EUR, "98.50");
        var fees = account(EUR, "1.50");
        var payout = recorded(EUR,
                entry(merchant.getId(), DEBIT, "100.00"),
                entry(payouts.getId(), CREDIT, "98.50"),
                entry(fees.getId(), CREDIT, "1.50"));
        lockable(merchant, payouts, fees);
        when(transactionRepository.save(any())).then(returnsFirstArg());

        var reversal = transactionService.reverse("key", payout.getId());

        assertThat(reversal.reversalOf()).isEqualTo(payout.getId());
        assertThat(reversal.description()).isEqualTo("Reversal of %s".formatted(payout.getId()));
        assertThat(reversal.entries())
                .extracting(EntryResponse::accountId, EntryResponse::direction, EntryResponse::amount)
                .containsExactly(
                        tuple(merchant.getId(), CREDIT, "100.00"),
                        tuple(payouts.getId(), DEBIT, "98.50"),
                        tuple(fees.getId(), DEBIT, "1.50"));
        assertThat(merchant.getBalance()).isEqualByComparingTo("125.50");
        assertThat(payouts.getBalance()).isZero();
        assertThat(fees.getBalance()).isZero();
        verify(outboxService).add(reversal);
    }

    @Test
    void reverseNothingWhenTheTransactionWasAlreadyReversed() {

        var merchant = account(EUR, "125.50");
        var payouts = account(EUR, "0");
        var payout = recorded(EUR, entry(merchant.getId(), DEBIT, "100.00"), entry(payouts.getId(), CREDIT, "100.00"));
        lockable(merchant, payouts);
        var payoutId = payout.getId();
        when(transactionRepository.existsByReversalOf(payoutId)).thenReturn(true);

        assertThatThrownBy(() -> transactionService.reverse("key", payoutId))
                .isInstanceOfSatisfying(LedgerException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo(TRANSACTION_ALREADY_REVERSED));

        verify(transactionRepository, never()).save(any());
        verify(outboxService, never()).add(any());
        assertThat(merchant.getBalance()).isEqualByComparingTo("125.50");
        assertThat(payouts.getBalance()).isZero();
    }

    @Test
    void settleAPaymentFromTheSettlementAccountOfItsCurrencyToTheMerchantUnderThePaymentId() {

        var settlement = account(EUR, "0");
        settlement.setUnlimitedOverdraft(true);
        var merchant = account(EUR, "0");
        when(accountRepository.findSettlementAccountId(EUR)).thenReturn(Optional.of(settlement.getId()));
        lockable(settlement, merchant);
        when(transactionRepository.save(any())).then(returnsFirstArg());
        var payment = new PaymentSettled("pay-42", merchant.getId(), new BigDecimal("125.50"), EUR);

        var settled = transactionService.settle(payment);

        assertThat(settled.description()).isEqualTo("Payment pay-42 settled");
        assertThat(settled.entries())
                .extracting(EntryResponse::accountId, EntryResponse::direction, EntryResponse::amount)
                .containsExactly(tuple(settlement.getId(), DEBIT, "125.50"), tuple(merchant.getId(), CREDIT, "125.50"));
        assertThat(settlement.getBalance()).isEqualByComparingTo("-125.50");
        assertThat(merchant.getBalance()).isEqualByComparingTo("125.50");
        verify(idempotencyService).commitOnce(eq("payment-settled:pay-42"), eq("settlePayment"), eq(payment),
                eq(TransactionResponse.class), any());
        verify(outboxService).add(settled);
    }

    @Test
    void settleNothingWhileTheCurrencyHasNoSettlementAccount() {

        when(accountRepository.findSettlementAccountId(JPY)).thenReturn(Optional.empty());
        var payment = new PaymentSettled("pay-7", UUID.randomUUID(), new BigDecimal("15000"), JPY);

        assertThatThrownBy(() -> transactionService.settle(payment)).isInstanceOf(IllegalStateException.class);

        verify(transactionRepository, never()).save(any());
        verify(outboxService, never()).add(any());
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

    private Transaction recorded(Currency currency, Entry... entries) {

        var transaction = withId(new Transaction());
        transaction.setCurrency(currency);
        transaction.setEntries(List.of(entries));
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        return transaction;
    }

    private static Transaction withId(Transaction transaction) {

        transaction.setId(UUID.randomUUID());
        return transaction;
    }
}
