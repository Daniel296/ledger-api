package com.coremaker.ledger.integration.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;

import com.atlassian.oai.validator.report.MessageResolver;
import com.atlassian.oai.validator.schema.SchemaValidator;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;

public final class Announcements implements AutoCloseable {

    private static final TopicPartition TRANSACTION_RECORDED = new TopicPartition("transaction-recorded", 0);
    private static final OpenAPI CONTRACT = new OpenAPIV3Parser().read("docs/openapi.yaml");
    private static final SchemaValidator CONTRACT_VALIDATOR = new SchemaValidator(CONTRACT, new MessageResolver());
    private static final Schema<?> TRANSACTION = CONTRACT.getComponents()
            .getSchemas()
            .get("Transaction");

    private final Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    private Announcements(Consumer<String, String> consumer) {

        this.consumer = consumer;
    }

    public static Announcements fromNowOn(ConsumerFactory<String, String> consumerFactory) {

        var consumer = consumerFactory.createConsumer();
        consumer.assign(List.of(TRANSACTION_RECORDED));
        consumer.seekToEnd(List.of(TRANSACTION_RECORDED));
        consumer.position(TRANSACTION_RECORDED);
        return new Announcements(consumer);
    }

    public List<ConsumerRecord<String, String>> first(int count) {

        await().atMost(Duration.ofSeconds(30))
                .pollInSameThread()
                .until(() -> {

                    consumer.poll(Duration.ofMillis(100)).forEach(this::receive);
                    return received.size() >= count;
                });

        return List.copyOf(received);
    }

    public List<String> firstTransactionIds(int count) {

        return first(count).stream()
                .map(ConsumerRecord::key)
                .toList();
    }

    @Override
    public void close() {

        consumer.close();
    }

    private void receive(ConsumerRecord<String, String> announcement) {

        var report = CONTRACT_VALIDATOR.validate(announcement.value(), TRANSACTION, "transactionRecorded");

        assertThat(report.getMessages()).as(announcement.value()).isEmpty();
        received.add(announcement);
    }
}
