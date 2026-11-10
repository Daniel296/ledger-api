package com.coremaker.ledger.messaging;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TransactionRecordedPublisher {

    private static final String TOPIC = "transaction-recorded";
    private static final int ACKNOWLEDGEMENT_TIMEOUT_SECONDS = 5;

    private final KafkaOperations<String, String> kafkaOperations;

    @Bean
    static NewTopic transactionRecordedTopic() {

        return TopicBuilder.name(TOPIC)
                .partitions(1)
                .build();
    }

    public void publish(UUID transactionId, String payload) {

        kafkaOperations.send(TOPIC, transactionId.toString(), payload)
                .orTimeout(ACKNOWLEDGEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join();
    }
}
