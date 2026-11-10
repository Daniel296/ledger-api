package com.coremaker.ledger.integration.concurrency;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;

import com.coremaker.ledger.integration.support.Announcements;
import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.integration.support.SecondInstance;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.repository.AccountRepository;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class ConcurrentPaymentsShould {

    @Autowired
    private SecondInstance secondInstance;

    @Autowired
    private KafkaListenerEndpointRegistry consumers;

    @Autowired
    private KafkaOperations<String, String> kafkaOperations;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @RepeatedTest(5)
    void recordEveryPaymentOnceWhenBothInstancesReceiveItAtOnce() {

        var merchant = account();
        var last = account();
        await().until(this::bothInstancesConsume);

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            for (int payment = 0; payment < 20; payment++) {

                var paymentId = newPaymentId();
                send(0, payment(paymentId, merchant, "10.00"));
                send(1, payment(paymentId, merchant, "10.00"));
            }

            send(0, payment(newPaymentId(), last, "1.00"));
            send(1, payment(newPaymentId(), last, "1.00"));
            await().until(() -> balance(last).compareTo(new BigDecimal("2.00")) == 0);

            assertThat(balance(merchant)).isEqualByComparingTo("200.00");
            assertThat(entriesOf(merchant)).isEqualTo(20);
            assertThat(announcements.firstTransactionIds(22)).doesNotHaveDuplicates();
        }
    }

    @Test
    void recordAPaymentOnceWhenItComesBackAfterAnInstanceRecordedItAndDiedBeforeCommittingItsOffset() throws Exception {

        var merchant = account();
        var paymentId = newPaymentId();
        var next = newPaymentId();

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            var sent = send(0, payment(paymentId, merchant, "10.00"));
            announcements.first(1);

            moveTheCommittedOffsetBackTo(sent);
            send(0, payment(next, merchant, "1.00"));

            assertThat(announcements.first(2))
                    .extracting(announcement -> JsonPath.<String>read(announcement.value(), "$.description"))
                    .containsExactly("Payment %s settled".formatted(paymentId), "Payment %s settled".formatted(next));
            assertThat(balance(merchant)).isEqualByComparingTo("11.00");
            assertThat(entriesOf(merchant)).isEqualTo(2);
        }
    }

    private void moveTheCommittedOffsetBackTo(RecordMetadata sent) throws Exception {

        var everyInstance = List.of(consumers, secondInstance.context().getBean(KafkaListenerEndpointRegistry.class));
        everyInstance.forEach(KafkaListenerEndpointRegistry::stop);

        try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {

            var partition = new TopicPartition(sent.topic(), sent.partition());
            admin.alterConsumerGroupOffsets("ledger", Map.of(partition, new OffsetAndMetadata(sent.offset())))
                    .all()
                    .get();
        } finally {
            everyInstance.forEach(KafkaListenerEndpointRegistry::start);
        }
    }

    private boolean bothInstancesConsume() {

        return List.of(consumers, secondInstance.context().getBean(KafkaListenerEndpointRegistry.class)).stream()
                .flatMap(instance -> instance.getAllListenerContainers().stream())
                .noneMatch(consumer -> consumer.getAssignedPartitions().isEmpty());
    }

    private RecordMetadata send(int partition, String message) {

        return kafkaOperations.send("payment-settled", partition, null, message)
                .join()
                .getRecordMetadata();
    }

    private static String payment(String paymentId, Account merchant, String amount) {

        return """
                {"eventId": "%s", "paymentId": "%s", "merchantAccountId": "%s", "amount": "%s", "currency": "EUR"}"""
                .formatted(UUID.randomUUID(), paymentId, merchant.getId(), amount);
    }

    private static String newPaymentId() {

        return UUID.randomUUID().toString();
    }

    private Integer entriesOf(Account account) {

        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM entries WHERE account_id = ?", Integer.class,
                account.getId().toString());
    }

    private BigDecimal balance(Account account) {

        return accountRepository.findById(account.getId())
                .orElseThrow()
                .getBalance();
    }

    private Account account() {

        var account = new Account();
        account.setCurrency(EUR);
        account.setCreatedAt(Instant.now());
        return accountRepository.save(account);
    }
}
