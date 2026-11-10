package com.coremaker.ledger.unit.service;

import static com.coremaker.ledger.exception.ErrorCode.ACCOUNT_NOT_FOUND;
import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

import com.coremaker.ledger.dto.request.AccountRequest;
import com.coremaker.ledger.dto.response.AccountEntryResponse;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.Transaction;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.EntryRepository;
import com.coremaker.ledger.service.AccountService;
import com.coremaker.ledger.service.IdempotencyService;
import com.coremaker.ledger.validation.AccountValidator;

class AccountServiceShould {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final EntryRepository entryRepository = mock(EntryRepository.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final AccountService accountService = new AccountService(
            accountRepository, entryRepository, new AccountValidator(), idempotencyService);

    @BeforeEach
    void runEveryRequestAsANewOne() {

        when(idempotencyService.commitOnce(any(), any(), any(), any(), any()))
                .then(call -> call.<Supplier<?>>getArgument(4).get());
    }

    @Test
    void openAnAccountWithAZeroOverdraftLimitWhenNoneIsGiven() {

        when(accountRepository.save(any())).then(returnsFirstArg());

        var account = accountService.open("key", new AccountRequest("EUR", null));

        assertThat(account.currency()).isEqualTo(EUR);
        assertThat(account.overdraftLimit()).isEqualTo("0.00");
        assertThat(account.balance()).isEqualTo("0.00");
        assertThat(account.createdAt()).isNotNull();
    }

    @Test
    void openAnAccountWithTheOverdraftLimitGiven() {

        when(accountRepository.save(any())).then(returnsFirstArg());

        var account = accountService.open("key", new AccountRequest("JPY", new BigDecimal("1000")));

        assertThat(account.overdraftLimit()).isEqualTo("1000");
        assertThat(account.balance()).isEqualTo("0");
    }

    @Test
    void saveNothingWhenTheRequestBreaksARule() {

        var unsupported = new AccountRequest("GBP", null);

        assertThatThrownBy(() -> accountService.open("key", unsupported)).isInstanceOf(LedgerException.class);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void showNoOverdraftLimitForAnAccountWithoutOne() {

        var settlement = new Account();
        settlement.setId(UUID.randomUUID());
        settlement.setCurrency(EUR);
        settlement.setUnlimitedOverdraft(true);
        settlement.setBalance(new BigDecimal("-125.5000"));
        settlement.setCreatedAt(Instant.now());
        when(accountRepository.findById(settlement.getId())).thenReturn(Optional.of(settlement));

        var account = accountService.get(settlement.getId());

        assertThat(account.overdraftLimit()).isNull();
        assertThat(account.balance()).isEqualTo("-125.50");
    }

    @Test
    void showTheNewestEntriesAndPointTheNextCursorAtTheLastOneShown() {

        var merchant = existing();
        when(entryRepository.findNewestFirst(merchant.getId(), Long.MAX_VALUE, Limit.of(3)))
                .thenReturn(List.of(entry(30, "125.5000"), entry(20, "25.5000"), entry(10, "125.5000")));

        var page = accountService.getEntries(merchant.getId(), 2, null);

        assertThat(page.entries()).extracting(AccountEntryResponse::balanceAfter).containsExactly("125.50", "25.50");
        assertThat(page.nextCursor()).isEqualTo("20");
    }

    @Test
    void continueBeforeTheCursorAndGiveNoCursorWhenNoEntryIsLeft() {

        var merchant = existing();
        when(entryRepository.findNewestFirst(merchant.getId(), 20, Limit.of(3)))
                .thenReturn(List.of(entry(15, "100.0000"), entry(10, "125.5000")));

        var page = accountService.getEntries(merchant.getId(), 2, 20L);

        assertThat(page.entries()).extracting(AccountEntryResponse::balanceAfter).containsExactly("100.00", "125.50");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void giveTheBalanceAfterTheLastEntryRecordedByTheInstantToTheMicrosecond() {

        var merchant = existing();
        var asOf = Instant.parse("2026-11-10T10:00:00.123456789Z");
        when(entryRepository.findBalanceAsOf(merchant.getId(), Instant.parse("2026-11-10T10:00:00.123456Z")))
                .thenReturn(Optional.of(new BigDecimal("25.5000")));

        var balance = accountService.getBalance(merchant.getId(), asOf);

        assertThat(balance.balance()).isEqualTo("25.50");
        assertThat(balance.asOf()).isEqualTo(asOf);
    }

    @Test
    void reportAnAccountThatDoesNotExist() {

        var id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.get(id))
                .isInstanceOfSatisfying(LedgerException.class,
                        notFound -> assertThat(notFound.getCode()).isEqualTo(ACCOUNT_NOT_FOUND));
    }

    private Account existing() {

        var account = new Account();
        account.setId(UUID.randomUUID());
        account.setCurrency(EUR);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    private static Entry entry(long id, String balanceAfter) {

        var transaction = new Transaction();
        transaction.setId(UUID.randomUUID());
        transaction.setRecordedAt(Instant.now());

        var entry = new Entry();
        entry.setId(id);
        entry.setTransaction(transaction);
        entry.setDirection(CREDIT);
        entry.setAmount(new BigDecimal("1.0000"));
        entry.setBalanceAfter(new BigDecimal(balanceAfter));
        return entry;
    }
}
