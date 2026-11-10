package com.coremaker.ledger.service;

import static com.coremaker.ledger.exception.ErrorCode.IDEMPOTENCY_KEY_REUSED;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.coremaker.ledger.dto.response.ErrorResponse;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.model.IdempotentRequest;
import com.coremaker.ledger.repository.IdempotentRequestRepository;

import lombok.SneakyThrows;
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

        return answerOnce(idempotencyKey, operation, request, responseType, effect, true);
    }

    @Transactional(propagation = Propagation.NEVER)
    public <T> T commitOnceForgettingRefusals(String idempotencyKey, String operation, Object request,
            Class<T> responseType, Supplier<T> effect) {

        return answerOnce(idempotencyKey, operation, request, responseType, effect, false);
    }

    private <T> T answerOnce(String idempotencyKey, String operation, Object request, Class<T> responseType,
            Supplier<T> effect, boolean refusalRemembered) {

        var requestHash = sha256(jsonMapper.writeValueAsString(List.of(operation, request)));
        readCommitted.executeWithoutResult(reservation -> idempotentRequestRepository.reserve(idempotencyKey));

        var answer = readCommitted.execute(
                execution -> respond(idempotencyKey, requestHash, responseType, effect, refusalRemembered));
        return answer.get();
    }

    private <T> Supplier<T> respond(String idempotencyKey, String requestHash, Class<T> responseType,
            Supplier<T> effect, boolean refusalRemembered) {

        var idempotentRequest = idempotentRequestRepository.findByIdForUpdate(idempotencyKey);
        if (idempotentRequest.getRequestHash() == null) {
            var answer = answerFirst(idempotentRequest, effect, refusalRemembered);
            idempotentRequest.setRequestHash(requestHash);
            return answer;
        }

        if (!requestHash.equals(idempotentRequest.getRequestHash())) {
            throw new LedgerException(IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key %s was already used for a different request".formatted(idempotencyKey));
        }

        if (idempotentRequest.getRefusal() != null) {
            var refusal = jsonMapper.readValue(idempotentRequest.getRefusal(), ErrorResponse.class);
            throw new LedgerException(refusal.errorCode(), refusal.message());
        }

        var response = jsonMapper.readValue(idempotentRequest.getResponse(), responseType);
        return () -> response;
    }

    private <T> Supplier<T> answerFirst(IdempotentRequest idempotentRequest, Supplier<T> effect,
            boolean refusalRemembered) {

        try {
            var response = effect.get();
            idempotentRequest.setResponse(jsonMapper.writeValueAsString(response));
            return () -> response;
        } catch (LedgerException refusal) {
            if (!refusalRemembered) {
                throw refusal;
            }

            idempotentRequest.setRefusal(
                    jsonMapper.writeValueAsString(new ErrorResponse(refusal.getCode(), refusal.getMessage())));
            return () -> {
                throw refusal;
            };
        }
    }

    @SneakyThrows(NoSuchAlgorithmException.class)
    private static String sha256(String text) {

        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
    }
}
