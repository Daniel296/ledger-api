package com.coremaker.ledger.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.coremaker.ledger.dto.response.TransactionResponse;
import com.coremaker.ledger.logging.CorrelationId;
import com.coremaker.ledger.model.OutboxMessage;
import com.coremaker.ledger.repository.OutboxMessageRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxMessageRepository outboxMessageRepository;
    private final JsonMapper jsonMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void add(TransactionResponse transaction) {

        var message = new OutboxMessage();
        message.setTransactionId(transaction.id());
        message.setCorrelationId(CorrelationId.get());
        message.setPayload(jsonMapper.writeValueAsString(transaction));
        outboxMessageRepository.save(message);
    }
}
