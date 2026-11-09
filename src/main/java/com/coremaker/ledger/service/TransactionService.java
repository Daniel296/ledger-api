package com.coremaker.ledger.service;

import static com.coremaker.ledger.exception.ErrorCode.TRANSACTION_NOT_FOUND;
import static java.util.stream.Collectors.toMap;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coremaker.ledger.dto.EntryRequest;
import com.coremaker.ledger.dto.RecordTransactionRequest;
import com.coremaker.ledger.dto.TransactionResponse;
import com.coremaker.ledger.exception.NotFoundException;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.Transaction;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.TransactionRepository;
import com.coremaker.ledger.validation.TransactionValidator;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionValidator transactionValidator;

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID id) {

        return transactionRepository.findById(id)
                .map(TransactionResponse::from)
                .orElseThrow(() -> new NotFoundException(TRANSACTION_NOT_FOUND, "Transaction %s does not exist".formatted(id)));
    }

    @Transactional
    public TransactionResponse post(RecordTransactionRequest request) {

        var transaction = new Transaction();
        transaction.setDescription(request.description());
        transaction.setEntries(request.entries().stream()
                .map(this::toEntry)
                .toList());

        var accounts = lockAccounts(transaction);
        transactionValidator.validate(transaction, accounts);

        for (var entry : transaction.getEntries()) {

            var account = accounts.get(entry.getAccountId());
            account.setBalance(account.getBalance().add(entry.getDirection().signed(entry.getAmount())));
            entry.setBalanceAfter(account.getBalance());
        }

        var firstAccount = accounts.get(transaction.getEntries().get(0).getAccountId());
        transaction.setCurrency(firstAccount.getCurrency());
        transaction.setRecordedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));

        return TransactionResponse.from(transactionRepository.save(transaction));
    }

    private Map<UUID, Account> lockAccounts(Transaction transaction) {

        var ids = transaction.getEntries().stream()
                .map(Entry::getAccountId)
                .distinct()
                .toList();

        return accountRepository.findAllByIdForUpdate(ids).stream()
                .collect(toMap(Account::getId, Function.identity()));
    }

    private Entry toEntry(EntryRequest request) {

        var entry = new Entry();
        entry.setAccountId(request.accountId());
        entry.setDirection(request.direction());
        entry.setAmount(request.amount());
        return entry;
    }
}
