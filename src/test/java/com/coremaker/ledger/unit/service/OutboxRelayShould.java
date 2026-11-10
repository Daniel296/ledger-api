package com.coremaker.ledger.unit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;

import com.coremaker.ledger.messaging.TransactionRecordedPublisher;
import com.coremaker.ledger.model.OutboxMessage;
import com.coremaker.ledger.repository.OutboxMessageRepository;
import com.coremaker.ledger.service.OutboxRelay;

class OutboxRelayShould {

    private final OutboxMessageRepository outboxMessageRepository = mock(OutboxMessageRepository.class);
    private final TransactionRecordedPublisher transactionRecordedPublisher = mock(TransactionRecordedPublisher.class);
    private final OutboxRelay outboxRelay = new OutboxRelay(outboxMessageRepository, transactionRecordedPublisher);

    @Test
    void publishPendingMessagesInTheirOrderThenMarkThemPublished() {

        var first = pending(1L);
        var second = pending(2L);
        when(outboxMessageRepository.findUnlockedPublisherForUpdate()).thenReturn(Optional.of(1));
        when(outboxMessageRepository.findFirst100ByPublishedAtIsNullOrderById()).thenReturn(List.of(first, second));

        outboxRelay.publishPending();

        var order = inOrder(transactionRecordedPublisher, outboxMessageRepository);
        order.verify(transactionRecordedPublisher).publish(first);
        order.verify(transactionRecordedPublisher).publish(second);
        order.verify(outboxMessageRepository).markPublished(eq(List.of(1L, 2L)), any());
    }

    @Test
    void publishNothingWhileAnotherInstanceIsPublishing() {

        when(outboxMessageRepository.findUnlockedPublisherForUpdate()).thenReturn(Optional.empty());

        outboxRelay.publishPending();

        verify(outboxMessageRepository, never()).findFirst100ByPublishedAtIsNullOrderById();
        verifyNoInteractions(transactionRecordedPublisher);
    }

    @Test
    void markNothingPublishedWhenKafkaRefusesAMessage() {

        var first = pending(1L);
        var second = pending(2L);
        when(outboxMessageRepository.findUnlockedPublisherForUpdate()).thenReturn(Optional.of(1));
        when(outboxMessageRepository.findFirst100ByPublishedAtIsNullOrderById()).thenReturn(List.of(first, second));
        doThrow(new RecordTooLargeException()).when(transactionRecordedPublisher).publish(second);

        assertThatThrownBy(outboxRelay::publishPending).isInstanceOf(RecordTooLargeException.class);

        verify(outboxMessageRepository, never()).markPublished(any(), any());
    }

    private static OutboxMessage pending(Long id) {

        var message = new OutboxMessage();
        message.setId(id);
        message.setTransactionId(UUID.randomUUID());
        message.setPayload("{\"id\": %d}".formatted(id));
        return message;
    }
}
