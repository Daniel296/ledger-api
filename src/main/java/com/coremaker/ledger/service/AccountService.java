package com.coremaker.ledger.service;

import static com.coremaker.ledger.exception.ErrorCode.ACCOUNT_NOT_FOUND;
import static java.lang.String.valueOf;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coremaker.ledger.dto.request.AccountRequest;
import com.coremaker.ledger.dto.response.AccountEntriesPageResponse;
import com.coremaker.ledger.dto.response.AccountEntryResponse;
import com.coremaker.ledger.dto.response.AccountResponse;
import com.coremaker.ledger.dto.response.BalanceResponse;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.enums.Currency;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.EntryRepository;
import com.coremaker.ledger.validation.AccountValidator;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final EntryRepository entryRepository;
    private final AccountValidator accountValidator;
    private final IdempotencyService idempotencyService;

    @Transactional(readOnly = true)
    public AccountResponse get(UUID id) {

        return AccountResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public AccountEntriesPageResponse getEntries(UUID id, int limit, Long cursor) {

        var currency = find(id).getCurrency();
        var before = cursor == null ? Long.MAX_VALUE : cursor;
        var entries = entryRepository.findNewestFirst(id, before, Limit.of(limit + 1));

        var page = entries.stream()
                .limit(limit)
                .map(entry -> AccountEntryResponse.from(entry, currency))
                .toList();
        var nextCursor = entries.size() > limit ? valueOf(entries.get(limit - 1).getId()) : null;

        return new AccountEntriesPageResponse(page, nextCursor);
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID id, Instant asOf) {

        var account = find(id);
        var balance = entryRepository.findBalanceAsOf(id, asOf.truncatedTo(ChronoUnit.MICROS))
                .orElse(BigDecimal.ZERO);

        return BalanceResponse.from(account, balance, asOf);
    }

    public AccountResponse open(String idempotencyKey, AccountRequest request) {

        return idempotencyService.commitOnce(idempotencyKey, "openAccount", request, AccountResponse.class,
                () -> openAccount(request));
    }

    private Account find(UUID id) {

        return accountRepository.findById(id)
                .orElseThrow(() -> new LedgerException(ACCOUNT_NOT_FOUND, "Account %s does not exist".formatted(id)));
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
