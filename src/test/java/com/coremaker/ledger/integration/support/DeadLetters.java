package com.coremaker.ledger.integration.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;

public final class DeadLetters implements AutoCloseable {

    private static final List<TopicPartition> PAYMENT_SETTLED_DEAD_LETTERS = List.of(
            new TopicPartition("payment-settled-dlt", 0),
            new TopicPartition("payment-settled-dlt", 1));

    private final Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    private DeadLetters(Consumer<String, String> consumer) {

        this.consumer = consumer;
    }

    public static DeadLetters fromNowOn(ConsumerFactory<String, String> consumerFactory) {

        var consumer = consumerFactory.createConsumer();
        consumer.assign(PAYMENT_SETTLED_DEAD_LETTERS);
        consumer.seekToEnd(PAYMENT_SETTLED_DEAD_LETTERS);
        PAYMENT_SETTLED_DEAD_LETTERS.forEach(consumer::position);
        return new DeadLetters(consumer);
    }

    public List<ConsumerRecord<String, String>> first(int count) {

        await().atMost(Duration.ofSeconds(30))
                .pollInSameThread()
                .until(() -> {

                    consumer.poll(Duration.ofMillis(100)).forEach(received::add);
                    return received.size() >= count;
                });

        return List.copyOf(received);
    }

    @Override
    public void close() {

        consumer.close();
    }
}
