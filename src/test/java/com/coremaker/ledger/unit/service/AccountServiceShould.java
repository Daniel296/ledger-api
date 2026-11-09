package com.coremaker.ledger.unit.service;

import static com.coremaker.ledger.exception.ErrorCode.ACCOUNT_NOT_FOUND;
import static com.coremaker.ledger.model.enums.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.service.AccountService;
import com.coremaker.ledger.validation.AccountValidator;

class AccountServiceShould {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountService accountService = new AccountService(accountRepository, new AccountValidator());

    @Test
    void openAnAccountWithAZeroOverdraftLimitWhenNoneIsGiven() {

        when(accountRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));

        var account = accountService.open("EUR", null);

        assertThat(account.id()).isNotNull();
        assertThat(account.currency()).isEqualTo(EUR);
        assertThat(account.overdraftLimit()).isEqualTo("0.00");
        assertThat(account.balance()).isEqualTo("0.00");
        assertThat(account.createdAt()).isNotNull();
    }

    @Test
    void openAnAccountWithTheOverdraftLimitGiven() {

        when(accountRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));

        var account = accountService.open("JPY", new BigDecimal("1000"));

        assertThat(account.overdraftLimit()).isEqualTo("1000");
        assertThat(account.balance()).isEqualTo("0");
    }

    @Test
    void saveNothingWhenTheRequestBreaksARule() {

        assertThatThrownBy(() -> accountService.open("GBP", null)).isInstanceOf(LedgerException.class);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void showNoOverdraftLimitForAnAccountWithoutOne() {

        var settlement = withId(new Account());
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
    void reportAnAccountThatDoesNotExist() {

        var id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.get(id))
                .isInstanceOfSatisfying(LedgerException.class,
                        notFound -> assertThat(notFound.getCode()).isEqualTo(ACCOUNT_NOT_FOUND));
    }

    private static Account withId(Account account) {

        account.setId(UUID.randomUUID());
        return account;
    }
}
