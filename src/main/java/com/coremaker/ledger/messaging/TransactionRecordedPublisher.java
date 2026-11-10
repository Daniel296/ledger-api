package com.coremaker.ledger.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Component;

import com.coremaker.ledger.model.OutboxMessage;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TransactionRecordedPublisher {

    private static final String TOPIC = "transaction-recorded";
    private static final String CORRELATION_ID = "correlationId";
    private static final int ACKNOWLEDGEMENT_TIMEOUT_SECONDS = 5;

    private final KafkaOperations<String, String> kafkaOperations;

    @Bean
    static NewTopic transactionRecordedTopic() {

        return TopicBuilder.name(TOPIC)
                .partitions(1)
                .build();
    }

    public void publish(OutboxMessage message) {

        var announcement = new ProducerRecord<>(TOPIC, message.getTransactionId().toString(), message.getPayload());
        if (message.getCorrelationId() != null) {
            announcement.headers().add(CORRELATION_ID, message.getCorrelationId().getBytes(UTF_8));
        }

        kafkaOperations.send(announcement)
                .orTimeout(ACKNOWLEDGEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join();
    }
}
