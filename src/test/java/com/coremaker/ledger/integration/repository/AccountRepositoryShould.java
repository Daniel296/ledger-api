package com.coremaker.ledger.integration.repository;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Currency.JPY;
import static com.coremaker.ledger.model.enums.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.enums.Currency;
import com.coremaker.ledger.repository.AccountRepository;

@IntegrationTest
class AccountRepositoryShould {

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void findTheOneSettlementAccountTheMigrationCreatesForEachCurrencyAndNoOtherAccount() {

        var withALimit = accountRepository.save(account(EUR));

        assertSettlementAccount(EUR);
        assertSettlementAccount(USD);
        assertSettlementAccount(JPY);
        assertThat(accountRepository.findSettlementAccountId(EUR)).isNotEqualTo(Optional.of(withALimit.getId()));
    }

    @Test
    void saveAnAccountAndReadItBack() {

        var account = account(EUR);

        var saved = accountRepository.save(account);
        var found = accountRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getCurrency()).isEqualTo(EUR);
        assertThat(found.getOverdraftLimit()).isEqualByComparingTo("100.00");
        assertThat(found.isUnlimitedOverdraft()).isFalse();
        assertThat(found.getBalance()).isZero();
        assertThat(found.getCreatedAt()).isEqualTo(account.getCreatedAt());
    }

    private void assertSettlementAccount(Currency currency) {

        var id = accountRepository.findSettlementAccountId(currency).orElseThrow();
        var account = accountRepository.findById(id).orElseThrow();

        assertThat(account.getCurrency()).isEqualTo(currency);
        assertThat(account.isUnlimitedOverdraft()).isTrue();
    }

    private static Account account(Currency currency) {

        var account = new Account();
        account.setCurrency(currency);
        account.setOverdraftLimit(new BigDecimal("100.00"));
        account.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return account;
    }
}
