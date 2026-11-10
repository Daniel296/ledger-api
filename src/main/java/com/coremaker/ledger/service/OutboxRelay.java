package com.coremaker.ledger.service;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.coremaker.ledger.messaging.TransactionRecordedPublisher;
import com.coremaker.ledger.model.OutboxMessage;
import com.coremaker.ledger.repository.OutboxMessageRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxMessageRepository outboxMessageRepository;
    private final TransactionRecordedPublisher transactionRecordedPublisher;

    @Scheduled(fixedDelay = 200)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void publishPending() {

        if (outboxMessageRepository.findUnlockedPublisherForUpdate().isEmpty()) {
            return;
        }

        var pending = outboxMessageRepository.findFirst100ByPublishedAtIsNullOrderById();
        if (pending.isEmpty()) {
            return;
        }

        for (var message : pending) {

            transactionRecordedPublisher.publish(message.getTransactionId(), message.getPayload());
        }

        var published = pending.stream()
                .map(OutboxMessage::getId)
                .toList();
        outboxMessageRepository.markPublished(published, Instant.now());
    }
}
