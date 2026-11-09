package com.coremaker.ledger.service;

import static com.coremaker.ledger.exception.ErrorCode.ACCOUNT_NOT_FOUND;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coremaker.ledger.dto.response.AccountResponse;
import com.coremaker.ledger.exception.NotFoundException;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.enums.Currency;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.validation.AccountValidator;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountValidator accountValidator;

    @Transactional(readOnly = true)
    public AccountResponse get(UUID id) {

        return accountRepository.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(() -> new NotFoundException(ACCOUNT_NOT_FOUND, "Account %s does not exist".formatted(id)));
    }

    @Transactional
    public AccountResponse open(String currencyCode, BigDecimal overdraftLimit) {

        accountValidator.validate(currencyCode, overdraftLimit);

        var account = new Account();
        account.setCurrency(Currency.valueOf(currencyCode));
        if (overdraftLimit != null) {
            account.setOverdraftLimit(overdraftLimit);
        }

        account.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return AccountResponse.from(accountRepository.save(account));
    }

}
