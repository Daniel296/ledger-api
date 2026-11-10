package com.coremaker.ledger.service;

import static com.coremaker.ledger.exception.ErrorCode.ACCOUNT_NOT_FOUND;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coremaker.ledger.dto.request.AccountRequest;
import com.coremaker.ledger.dto.response.AccountResponse;
import com.coremaker.ledger.exception.LedgerException;
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
    private final IdempotencyService idempotencyService;

    @Transactional(readOnly = true)
    public AccountResponse get(UUID id) {

        return accountRepository.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(() -> new LedgerException(ACCOUNT_NOT_FOUND, "Account %s does not exist".formatted(id)));
    }

    public AccountResponse open(String idempotencyKey, AccountRequest request) {

        return idempotencyService.commitOnce(idempotencyKey, "openAccount", request, AccountResponse.class,
                () -> openAccount(request));
    }

    private AccountResponse openAccount(AccountRequest request) {

        accountValidator.validate(request.currency(), request.overdraftLimit());

        var account = new Account();
        account.setCurrency(Currency.valueOf(request.currency()));
        if (request.overdraftLimit() != null) {
            account.setOverdraftLimit(request.overdraftLimit());
        }

        account.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return AccountResponse.from(accountRepository.save(account));
    }
}
