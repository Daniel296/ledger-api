package com.coremaker.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.Currency;
import com.coremaker.ledger.repository.AccountRepository;

@IntegrationTest
class AccountRepositoryShould {

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void findTheSettlementAccountsTheMigrationCreates() {

        assertSettlementAccount("ffd6f25c-cbbb-43ae-afe5-1834678bb9aa", Currency.EUR);
        assertSettlementAccount("91509bf3-2df9-43f8-89e3-2283c7f5a97d", Currency.USD);
        assertSettlementAccount("7bba09b3-74f5-4fbd-9315-25c9c98e4f94", Currency.JPY);
    }

    @Test
    void saveAnAccountAndReadItBack() {

        var account = new Account();
        account.setCurrency(Currency.EUR);
        account.setOverdraftLimit(new BigDecimal("100.00"));
        account.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));

        var saved = accountRepository.save(account);
        var found = accountRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getCurrency()).isEqualTo(Currency.EUR);
        assertThat(found.getOverdraftLimit()).isEqualByComparingTo("100.00");
        assertThat(found.isUnlimitedOverdraft()).isFalse();
        assertThat(found.getBalance()).isZero();
        assertThat(found.getCreatedAt()).isEqualTo(account.getCreatedAt());
    }

    private void assertSettlementAccount(String id, Currency currency) {

        var account = accountRepository.findById(UUID.fromString(id)).orElseThrow();

        assertThat(account.getCurrency()).isEqualTo(currency);
        assertThat(account.isUnlimitedOverdraft()).isTrue();
    }
}
