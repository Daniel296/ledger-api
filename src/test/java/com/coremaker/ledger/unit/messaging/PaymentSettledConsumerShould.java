package com.coremaker.ledger.unit.messaging;

import static com.coremaker.ledger.exception.ErrorCode.MALFORMED_MESSAGE;
import static com.coremaker.ledger.model.enums.Currency.JPY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.coremaker.ledger.dto.message.PaymentSettled;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.messaging.PaymentSettledConsumer;
import com.coremaker.ledger.service.TransactionService;

import jakarta.validation.Validation;
import tools.jackson.databind.json.JsonMapper;

class PaymentSettledConsumerShould {

    private final TransactionService transactionService = mock(TransactionService.class);
    private final PaymentSettledConsumer consumer = new PaymentSettledConsumer(transactionService, new JsonMapper(),
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void settleThePaymentTheMessageCarriesWhateverItsEventId() {

        var merchant = UUID.randomUUID();

        consumer.consume(message("""
                {"eventId": "%s", "paymentId": "pay-7", "merchantAccountId": "%s", "amount": "15000", "currency": "JPY"}
                """.formatted(UUID.randomUUID(), merchant)));

        verify(transactionService).settle(new PaymentSettled("pay-7", merchant, new BigDecimal("15000"), JPY));
    }

    @ParameterizedTest
    @MethodSource("malformedMessages")
    void refuseAMessageThatDoesNotCarryAPayment(String value, String problem) {

        assertThatThrownBy(() -> consumer.consume(message(value)))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", MALFORMED_MESSAGE)
                .hasMessageContaining(problem);

        verify(transactionService, never()).settle(any());
    }

    static Stream<Arguments> malformedMessages() {

        return Stream.of(
                arguments(null, "no payment"),
                arguments("null", "no payment"),
                arguments("{not json", "Unexpected character"),
                arguments(payment("pay-1", "not-a-uuid", "10.00", "EUR"), "UUID"),
                arguments(payment("pay-1", UUID.randomUUID(), "ten", "EUR"), "ten"),
                arguments(payment("pay-1", UUID.randomUUID(), "10.00", "GBP"), "GBP"),
                arguments(payment(" ", UUID.randomUUID(), "10.00", "EUR"), "paymentId must not be blank"),
                arguments(payment("p".repeat(201), UUID.randomUUID(), "10.00", "EUR"), "paymentId size must be between"),
                arguments(payment("pay-1", UUID.randomUUID(), "1234567890123456", "EUR"), "amount numeric value out of bounds"),
                arguments(payment("pay-1", UUID.randomUUID(), "10.00001", "EUR"), "amount numeric value out of bounds"),
                arguments("""
                        {"paymentId": "pay-1", "amount": "10.00", "currency": "EUR"}
                        """, "merchantAccountId must not be null"));
    }

    private static ConsumerRecord<String, String> message(String value) {

        return new ConsumerRecord<>("payment-settled", 0, 0, "pay-7", value);
    }

    private static String payment(String paymentId, Object merchantAccountId, String amount, String currency) {

        return """
                {"eventId": "%s", "paymentId": "%s", "merchantAccountId": "%s", "amount": "%s", "currency": "%s"}
                """.formatted(UUID.randomUUID(), paymentId, merchantAccountId, amount, currency);
    }
}
