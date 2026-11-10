package com.coremaker.ledger.integration.service;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.testcontainers.kafka.KafkaContainer;

import com.coremaker.ledger.dto.request.EntryRequest;
import com.coremaker.ledger.dto.request.TransactionRequest;
import com.coremaker.ledger.integration.support.Announcements;
import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.messaging.TransactionRecordedPublisher;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.OutboxMessageRepository;
import com.coremaker.ledger.service.TransactionService;

@IntegrationTest
class OutboxRelayShould {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxMessageRepository outboxMessageRepository;

    @Autowired
    private TransactionRecordedPublisher transactionRecordedPublisher;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private DataSource dataSource;

    @Test
    void publishATransactionRecordedWhileKafkaWasDownOnceItIsBack() {

        var funding = account("1000.00");
        var merchant = account("0");

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            var payment = whileKafkaIsDown(() -> {

                var recorded = transactionService.post(newKey(), transfer(funding, merchant));
                await().during(Duration.ofSeconds(6))
                        .atMost(Duration.ofSeconds(10))
                        .until(this::anyUnpublished);
                return recorded;
            });

            assertThat(announcements.firstTransactionIds(1)).containsOnly(payment.id().toString());
            await().atMost(Duration.ofSeconds(30)).until(() -> !anyUnpublished());
        }
    }

    @Test
    void publishWhatAPublisherThatDiedLeftUnpublished() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            var payment = whileAPublisherAboutToDieHoldsTheLock(() -> {

                var recorded = transactionService.post(newKey(), transfer(funding, merchant));
                await().during(Duration.ofSeconds(1))
                        .atMost(Duration.ofSeconds(2))
                        .until(this::anyUnpublished);
                return recorded;
            });

            assertThat(announcements.firstTransactionIds(1)).containsExactly(payment.id().toString());
        }
    }

    @Test
    void sendAnIdenticalCopyWhenAPublisherDiedAfterSendingButBeforeMarkingItPublished() throws Exception {

        var funding = account("1000.00");
        var merchant = account("0");

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            var sent = whileAPublisherAboutToDieHoldsTheLock(() -> {

                transactionService.post(newKey(), transfer(funding, merchant));
                var message = outboxMessageRepository.findAll().get(0);
                transactionRecordedPublisher.publish(message.getTransactionId(), message.getPayload());
                return message;
            });

            var copy = tuple(sent.getTransactionId().toString(), sent.getPayload());
            assertThat(announcements.first(2))
                    .extracting(ConsumerRecord::key, ConsumerRecord::value)
                    .containsExactly(copy, copy);
        }
    }

    private <T> T whileKafkaIsDown(Supplier<T> action) {

        var docker = kafka.getDockerClient();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            return action.get();
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }
    }

    private <T> T whileAPublisherAboutToDieHoldsTheLock(Supplier<T> action) throws SQLException {

        try (var dyingPublisher = dataSource.getConnection(); var statement = dyingPublisher.createStatement()) {

            dyingPublisher.setAutoCommit(false);
            statement.execute("SELECT id FROM outbox_publisher FOR UPDATE");
            var result = action.get();
            dyingPublisher.rollback();
            return result;
        }
    }

    private boolean anyUnpublished() {

        return outboxMessageRepository.findAll().stream()
                .anyMatch(message -> message.getPublishedAt() == null);
    }

    private Account account(String overdraftLimit) {

        var account = new Account();
        account.setCurrency(EUR);
        account.setOverdraftLimit(new BigDecimal(overdraftLimit));
        account.setCreatedAt(Instant.now());
        return accountRepository.save(account);
    }

    private static String newKey() {

        return UUID.randomUUID().toString();
    }

    private static TransactionRequest transfer(Account from, Account to) {

        return new TransactionRequest("Transfer", List.of(
                new EntryRequest(from.getId(), DEBIT, new BigDecimal("10.00")),
                new EntryRequest(to.getId(), CREDIT, new BigDecimal("10.00"))));
    }
}
