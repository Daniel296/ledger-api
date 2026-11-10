package com.coremaker.ledger.integration.service;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.coremaker.ledger.dto.request.EntryRequest;
import com.coremaker.ledger.dto.request.TransactionRequest;
import com.coremaker.ledger.dto.response.TransactionResponse;
import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.IdempotentRequestRepository;
import com.coremaker.ledger.repository.TransactionRepository;
import com.coremaker.ledger.service.IdempotencyService;
import com.coremaker.ledger.service.TransactionService;

@IntegrationTest
class IdempotencyServiceShould {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private IdempotentRequestRepository idempotentRequestRepository;

    @Test
    void recordATransactionOnceWhenTheFirstAttemptDiedBeforeCommitting() {

        var funding = account("1000.00");
        var merchant = account("0");
        var request = transfer(funding, merchant);
        var key = newKey();
        var transactionsBefore = transactionRepository.count();

        assertThatThrownBy(() -> idempotencyService.commitOnce(key, "recordTransaction", request,
                TransactionResponse.class, () -> {

                    merchant.setBalance(new BigDecimal("10.00"));
                    accountRepository.saveAndFlush(merchant);
                    throw new IllegalStateException("The instance died before committing");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(balance(merchant)).isEqualByComparingTo("0.00");
        assertThat(idempotentRequestRepository.findById(key).orElseThrow().getResponse()).isNull();

        transactionService.post(key, request);

        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore + 1);
        assertThat(balance(merchant)).isEqualByComparingTo("10.00");
    }

    private static String newKey() {

        return UUID.randomUUID().toString();
    }

    private BigDecimal balance(Account account) {

        return accountRepository.findById(account.getId())
                .orElseThrow()
                .getBalance();
    }

    private Account account(String overdraftLimit) {

        var account = new Account();
        account.setCurrency(EUR);
        account.setOverdraftLimit(new BigDecimal(overdraftLimit));
        account.setCreatedAt(Instant.now());
        return accountRepository.save(account);
    }

    private static TransactionRequest transfer(Account from, Account to) {

        return new TransactionRequest("Transfer", List.of(
                new EntryRequest(from.getId(), DEBIT, new BigDecimal("10.00")),
                new EntryRequest(to.getId(), CREDIT, new BigDecimal("10.00"))));
    }
}
