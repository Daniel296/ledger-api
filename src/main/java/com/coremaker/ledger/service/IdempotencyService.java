package com.coremaker.ledger.service;

import static com.coremaker.ledger.exception.ErrorCode.IDEMPOTENCY_KEY_REUSED;

import java.util.List;
import java.util.function.Supplier;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.repository.IdempotentRequestRepository;

import tools.jackson.databind.json.JsonMapper;

@Service
public class IdempotencyService {

    private final IdempotentRequestRepository idempotentRequestRepository;
    private final TransactionTemplate readCommitted;
    private final JsonMapper jsonMapper;

    public IdempotencyService(IdempotentRequestRepository idempotentRequestRepository,
            PlatformTransactionManager transactionManager, JsonMapper jsonMapper) {

        this.idempotentRequestRepository = idempotentRequestRepository;
        this.readCommitted = new TransactionTemplate(transactionManager);
        this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.NEVER)
    public <T> T commitOnce(String idempotencyKey, String operation, Object request, Class<T> responseType,
            Supplier<T> effect) {

        var requestHash = DigestUtils.sha256Hex(jsonMapper.writeValueAsString(List.of(operation, request)));
        readCommitted.executeWithoutResult(reservation -> idempotentRequestRepository.reserve(idempotencyKey));

        return readCommitted.execute(execution -> respond(idempotencyKey, requestHash, responseType, effect));
    }

    private <T> T respond(String idempotencyKey, String requestHash, Class<T> responseType, Supplier<T> effect) {

        var idempotentRequest = idempotentRequestRepository.findByIdForUpdate(idempotencyKey);
        if (idempotentRequest.getResponse() == null) {
            var response = effect.get();
            idempotentRequest.setRequestHash(requestHash);
            idempotentRequest.setResponse(jsonMapper.writeValueAsString(response));
            return response;
        }

        if (!requestHash.equals(idempotentRequest.getRequestHash())) {
            throw new LedgerException(IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key %s was already used for a different request".formatted(idempotencyKey));
        }

        return jsonMapper.readValue(idempotentRequest.getResponse(), responseType);
    }
}
