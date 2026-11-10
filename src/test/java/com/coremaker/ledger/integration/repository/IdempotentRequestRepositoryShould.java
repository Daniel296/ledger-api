package com.coremaker.ledger.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.repository.IdempotentRequestRepository;

@IntegrationTest
class IdempotentRequestRepositoryShould {

    @Autowired
    private IdempotentRequestRepository idempotentRequestRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void treatKeysThatDifferOnlyInCaseAsDifferentKeys() {

        var key = "case-%s".formatted(UUID.randomUUID());
        var keysBefore = idempotentRequestRepository.count();

        reserve(key);
        reserve(key.toUpperCase());

        assertThat(idempotentRequestRepository.count()).isEqualTo(keysBefore + 2);
    }

    private void reserve(String key) {

        transactionTemplate.executeWithoutResult(reservation -> idempotentRequestRepository.reserve(key));
    }
}
