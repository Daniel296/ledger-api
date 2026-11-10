package com.coremaker.ledger.messaging;

import static com.coremaker.ledger.exception.ErrorCode.MALFORMED_MESSAGE;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Map;
import java.util.Optional;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.context.annotation.Bean;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

import com.coremaker.ledger.dto.message.PaymentSettled;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.logging.CorrelationId;
import com.coremaker.ledger.service.TransactionService;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSettledConsumer {

    private static final String TOPIC = "payment-settled";
    private static final String DEAD_LETTER_TOPIC = "payment-settled-dlt";
    private static final int PARTITIONS = 2;

    private final TransactionService transactionService;
    private final JsonMapper jsonMapper;
    private final Validator validator;

    @Bean
    static NewTopic paymentSettledTopic() {

        return TopicBuilder.name(TOPIC)
                .partitions(PARTITIONS)
                .build();
    }

    @Bean
    static NewTopic paymentSettledDeadLetterTopic() {

        return TopicBuilder.name(DEAD_LETTER_TOPIC)
                .partitions(PARTITIONS)
                .build();
    }

    @Bean
    static DefaultErrorHandler deadLetterRefusalsAndRetryTheRest(KafkaOperations<String, String> kafkaOperations) {

        var deadLetters = new DeadLetterPublishingRecoverer(kafkaOperations);
        deadLetters.setExceptionHeadersCreator((headers, failure, isKey, names) -> addRefusal(headers, failure));

        var errorHandler = new DefaultErrorHandler(deadLetters, new ExponentialBackOff());
        errorHandler.setClassifications(Map.of(LedgerException.class, false), true);
        errorHandler.setRetryListeners((message, failure, delivery) -> log.warn("{} failed on delivery {}: {}",
                KafkaUtils.format(message), delivery, NestedExceptionUtils.getMostSpecificCause(failure).toString()));
        return errorHandler;
    }

    @KafkaListener(topics = TOPIC, groupId = "ledger", properties = "auto.offset.reset=earliest")
    public void consume(ConsumerRecord<String, String> message) {

        var payment = paymentSettled(message.value());
        CorrelationId.set(payment.paymentId());
        try {
            transactionService.settle(payment);
        } finally {
            CorrelationId.clear();
        }
    }

    private PaymentSettled paymentSettled(String json) {

        var payment = Optional.ofNullable(json)
                .map(this::read)
                .orElseThrow(() -> malformed("The message carries no payment"));

        validator.validate(payment).stream()
                .map(violation -> "%s %s".formatted(violation.getPropertyPath(), violation.getMessage()))
                .sorted()
                .findFirst()
                .ifPresent(problem -> {
                    throw malformed(problem);
                });

        return payment;
    }

    private PaymentSettled read(String json) {

        try {
            return jsonMapper.readValue(json, PaymentSettled.class);
        } catch (JacksonException e) {
            throw malformed(e.getOriginalMessage());
        }
    }

    private static LedgerException malformed(String problem) {

        return new LedgerException(MALFORMED_MESSAGE, problem);
    }

    private static void addRefusal(Headers headers, Exception failure) {

        var refusal = (LedgerException) NestedExceptionUtils.getMostSpecificCause(failure);
        headers.add("errorCode", refusal.getCode().name().getBytes(UTF_8))
                .add("errorMessage", refusal.getMessage().getBytes(UTF_8));
    }
}
