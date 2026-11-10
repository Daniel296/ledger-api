package com.coremaker.ledger.unit.service;

import static com.coremaker.ledger.exception.ErrorCode.IDEMPOTENCY_KEY_REUSED;
import static com.coremaker.ledger.exception.ErrorCode.UNSUPPORTED_CURRENCY;
import static com.coremaker.ledger.model.enums.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.coremaker.ledger.dto.request.AccountRequest;
import com.coremaker.ledger.dto.response.AccountResponse;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.model.IdempotentRequest;
import com.coremaker.ledger.repository.IdempotentRequestRepository;
import com.coremaker.ledger.service.IdempotencyService;

import tools.jackson.databind.json.JsonMapper;

class IdempotencyServiceShould {

    private final IdempotentRequestRepository idempotentRequestRepository = mock(IdempotentRequestRepository.class);
    private final IdempotencyService idempotencyService = new IdempotencyService(
            idempotentRequestRepository, mock(PlatformTransactionManager.class), new JsonMapper());

    @Test
    void runARequestOnceAndAnswerItsRetryWithTheSameResponse() {

        reserved();
        var runs = new AtomicInteger();

        var first = open("openAccount", "EUR", runs);
        var retry = open("openAccount", "EUR", runs);

        assertThat(runs).hasValue(1);
        assertThat(retry).isEqualTo(first);
    }

    @Test
    void refuseAKeyReusedForADifferentRequest() {

        reserved();
        open("openAccount", "EUR", new AtomicInteger());
        var runs = new AtomicInteger();

        assertRefused(() -> open("openAccount", "USD", runs));
        assertThat(runs).hasValue(0);
    }

    @Test
    void refuseAKeyReusedForTheSameBodyOnAnotherOperation() {

        reserved();
        open("openAccount", "EUR", new AtomicInteger());

        assertRefused(() -> open("anotherOperation", "EUR", new AtomicInteger()));
    }

    @Test
    void runAnotherRequestOnAKeyWhoseFirstRequestTookNoEffect() {

        reserved();
        var runs = new AtomicInteger();

        assertThatThrownBy(() -> idempotencyService.commitOnce("key", "openAccount", new AccountRequest("GBP", null),
                AccountResponse.class, () -> {
                    throw new LedgerException(UNSUPPORTED_CURRENCY, "GBP is not supported");
                })).isInstanceOf(LedgerException.class);
        open("openAccount", "EUR", runs);

        assertThat(runs).hasValue(1);
    }

    private void reserved() {

        when(idempotentRequestRepository.findByIdForUpdate("key")).thenReturn(new IdempotentRequest());
    }

    private AccountResponse open(String operation, String currency, AtomicInteger runs) {

        return idempotencyService.commitOnce("key", operation, new AccountRequest(currency, null), AccountResponse.class,
                () -> {

                    runs.incrementAndGet();
                    return new AccountResponse(UUID.randomUUID(), EUR, "0.00", "0.00", Instant.now());
                });
    }

    private static void assertRefused(Runnable retry) {

        assertThatThrownBy(retry::run)
                .isInstanceOfSatisfying(LedgerException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo(IDEMPOTENCY_KEY_REUSED));
    }
}
