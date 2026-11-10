package com.coremaker.ledger.service;

import static com.coremaker.ledger.exception.ErrorCode.TRANSACTION_NOT_FOUND;
import static java.util.stream.Collectors.toMap;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coremaker.ledger.dto.request.TransactionRequest;
import com.coremaker.ledger.dto.response.TransactionResponse;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.Transaction;
import com.coremaker.ledger.model.enums.Direction;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.TransactionRepository;
import com.coremaker.ledger.validation.ReversalValidator;
import com.coremaker.ledger.validation.TransactionValidator;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionValidator transactionValidator;
    private final ReversalValidator reversalValidator;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID id) {

        return TransactionResponse.from(find(id));
    }

    public TransactionResponse post(String idempotencyKey, TransactionRequest request) {

        return idempotencyService.commitOnce(idempotencyKey, "recordTransaction", request, TransactionResponse.class,
                () -> recordTransaction(request));
    }

    public TransactionResponse reverse(String idempotencyKey, UUID id) {

        return idempotencyService.commitOnce(idempotencyKey, "reverseTransaction", id, TransactionResponse.class,
                () -> recordReversal(id));
    }

    private TransactionResponse recordTransaction(TransactionRequest request) {

        var transaction = new Transaction();
        transaction.setDescription(request.description());
        transaction.setEntries(request.entries().stream()
                .map(requested -> entry(requested.accountId(), requested.direction(), requested.amount()))
                .toList());

        var accounts = lockAccounts(transaction);
        return apply(transaction, accounts);
    }

    private TransactionResponse recordReversal(UUID id) {

        var original = find(id);
        var reversal = new Transaction();
        reversal.setDescription("Reversal of %s".formatted(id));
        reversal.setReversalOf(id);
        reversal.setEntries(original.getEntries().stream()
                .map(posted -> entry(posted.getAccountId(), posted.getDirection().opposite(), posted.getAmount()))
                .toList());

        var accounts = lockAccounts(reversal);
        reversalValidator.validate(original, transactionRepository.existsByReversalOf(id));

        return apply(reversal, accounts);
    }

    private Transaction find(UUID id) {

        return transactionRepository.findById(id)
                .orElseThrow(() -> new LedgerException(TRANSACTION_NOT_FOUND, "Transaction %s does not exist".formatted(id)));
    }

    private Map<UUID, Account> lockAccounts(Transaction transaction) {

        var ids = transaction.getEntries().stream()
                .map(Entry::getAccountId)
                .distinct()
                .toList();

        return accountRepository.findAllByIdForUpdate(ids).stream()
                .collect(toMap(Account::getId, Function.identity()));
    }

    private TransactionResponse apply(Transaction transaction, Map<UUID, Account> accounts) {

        transactionValidator.validate(transaction, accounts);

        for (var entry : transaction.getEntries()) {

            var account = accounts.get(entry.getAccountId());
            account.setBalance(account.getBalance().add(entry.getDirection().signed(entry.getAmount())));
            entry.setBalanceAfter(account.getBalance());
        }

        var firstAccount = accounts.get(transaction.getEntries().get(0).getAccountId());
        transaction.setCurrency(firstAccount.getCurrency());
        transaction.setRecordedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));

        var recorded = TransactionResponse.from(transactionRepository.save(transaction));
        outboxService.add(recorded);

        return recorded;
    }

    private static Entry entry(UUID accountId, Direction direction, BigDecimal amount) {

        var entry = new Entry();
        entry.setAccountId(accountId);
        entry.setDirection(direction);
        entry.setAmount(amount);
        return entry;
    }
}
