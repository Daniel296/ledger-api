package com.coremaker.ledger.unit.service;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.coremaker.ledger.dto.response.EntryResponse;
import com.coremaker.ledger.dto.response.TransactionResponse;
import com.coremaker.ledger.model.OutboxMessage;
import com.coremaker.ledger.repository.OutboxMessageRepository;
import com.coremaker.ledger.service.OutboxService;

import tools.jackson.databind.json.JsonMapper;

class OutboxServiceShould {

    private final OutboxMessageRepository outboxMessageRepository = mock(OutboxMessageRepository.class);
    private final OutboxService outboxService = new OutboxService(outboxMessageRepository, new JsonMapper());

    @Test
    void keepTheTransactionAsTheApiShowsItUntilItIsPublished() {

        var transaction = new TransactionResponse(UUID.randomUUID(), "Payout", EUR, List.of(
                new EntryResponse(UUID.randomUUID(), DEBIT, "10.00"),
                new EntryResponse(UUID.randomUUID(), CREDIT, "10.00")), Instant.now(), null);

        outboxService.add(transaction);

        var saved = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(saved.capture());
        assertThat(saved.getValue().getTransactionId()).isEqualTo(transaction.id());
        assertThat(new JsonMapper().readValue(saved.getValue().getPayload(), TransactionResponse.class)).isEqualTo(transaction);
        assertThat(saved.getValue().getPublishedAt()).isNull();
    }
}
