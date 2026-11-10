package com.coremaker.ledger.integration.messaging;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Direction.CREDIT;
import static com.coremaker.ledger.model.enums.Direction.DEBIT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;

import com.coremaker.ledger.integration.support.Announcements;
import com.coremaker.ledger.integration.support.DeadLetters;
import com.coremaker.ledger.integration.support.IntegrationTest;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.enums.Currency;
import com.coremaker.ledger.repository.AccountRepository;
import com.coremaker.ledger.repository.TransactionRepository;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class PaymentSettledConsumerShould {

    @Autowired
    private KafkaOperations<String, String> kafkaOperations;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private DataSource dataSource;

    @ParameterizedTest
    @CsvSource({"EUR, 125.50", "JPY, 15000"})
    void recordAPaymentAsADebitOfTheSettlementAccountAndACreditOfTheMerchant(Currency currency, String amount) {

        var merchant = account(currency);
        var paymentId = newPaymentId();

        try (var announcements = Announcements.fromNowOn(consumerFactory)) {

            send(payment(paymentId, merchant, amount, currency));

            var recorded = transactionRepository.findById(UUID.fromString(announcements.firstTransactionIds(1).get(0)))
                    .orElseThrow();
            var settlement = accountRepository.findSettlementAccountId(currency).orElseThrow();

            assertThat(recorded.getDescription()).isEqualTo("Payment %s settled".formatted(paymentId));
            assertThat(recorded.getEntries())
                    .extracting(Entry::getAccountId, Entry::getDirection)
                    .containsExactly(tuple(settlement, DEBIT), tuple(merchant.getId(), CREDIT));
            assertThat(balance(merchant.getId())).isEqualByComparingTo(amount);
            assertThat(balance(settlement)).isEqualByComparingTo(new BigDecimal(amount).negate());
        }
    }

    @Test
    void recordAPaymentOnceUnderWhateverEventIdAndDeadLetterACopyWithAnotherAmount() {

        var merchant = account(EUR);
        var paymentId = newPaymentId();
        var next = newPaymentId();
        var payment = payment(paymentId, merchant, "10.00", EUR);
        var otherAmount = payment(paymentId, merchant, "99.00", EUR);

        try (var announcements = Announcements.fromNowOn(consumerFactory);
                var deadLetters = DeadLetters.fromNowOn(consumerFactory)) {

            send(payment);
            send(payment);
            send(payment(paymentId, merchant, "10.00", EUR));
            send(otherAmount);
            send(payment(next, merchant, "1.00", EUR));

            assertThat(announcements.first(2))
                    .extracting(announcement -> JsonPath.<String>read(announcement.value(), "$.description"))
                    .containsExactly("Payment %s settled".formatted(paymentId), "Payment %s settled".formatted(next));
            assertThat(deadLetters.first(1))
                    .extracting(ConsumerRecord::value, deadLetter -> header(deadLetter, "errorCode"))
                    .containsExactly(tuple(otherAmount, "IDEMPOTENCY_KEY_REUSED"));
            assertThat(balance(merchant.getId())).isEqualByComparingTo("11.00");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            not a payment                                                                          | MALFORMED_MESSAGE
            {"paymentId": "p-1", "amount": "10.00", "currency": "EUR"}                             | MALFORMED_MESSAGE
            {"paymentId": "p-1", "merchantAccountId": "%s", "amount": "10", "currency": "JPY"}     | CURRENCY_MISMATCH
            {"paymentId": "p-1", "merchantAccountId": "%s", "amount": "10.001", "currency": "EUR"} | TOO_MANY_DECIMAL_PLACES
            {"paymentId": "p-1", "merchantAccountId": "%s", "amount": "0.00", "currency": "EUR"}   | NON_POSITIVE_AMOUNT
            {"paymentId": "p-1", "merchantAccountId": "%s", "amount": "1234567890123456", "currency": "EUR"} | MALFORMED_MESSAGE
            {"paymentId": "p-1", "merchantAccountId": "%s", "amount": "10.00001", "currency": "EUR"} | MALFORMED_MESSAGE
            {"paymentId": "p-1", "merchantAccountId": "%2$s", "amount": "10.00", "currency": "EUR"} | UNKNOWN_ACCOUNT
            """)
    void deadLetterAMessageItCannotRecordUnchangedAndRecordTheOnesBehindIt(String message, String errorCode) {

        var merchant = account(EUR);
        var invalid = message.formatted(merchant.getId(), UUID.randomUUID());
        var behind = newPaymentId();

        try (var announcements = Announcements.fromNowOn(consumerFactory);
                var deadLetters = DeadLetters.fromNowOn(consumerFactory)) {

            send(invalid);
            send(payment(behind, merchant, "1.00", EUR));

            assertThat(announcements.first(1))
                    .extracting(announcement -> JsonPath.<String>read(announcement.value(), "$.description"))
                    .containsExactly("Payment %s settled".formatted(behind));
            var deadLettered = deadLetters.first(1);
            assertThat(deadLettered)
                    .extracting(ConsumerRecord::value, deadLetter -> header(deadLetter, "errorCode"))
                    .containsExactly(tuple(invalid, errorCode));
            assertThat(header(deadLettered.get(0), "errorMessage")).isNotBlank();
            assertThat(transactionRepository.count()).isOne();
            assertThat(balance(accountRepository.findSettlementAccountId(EUR).orElseThrow())).isEqualByComparingTo("-1.00");
        }
    }

    @Test
    void retryAPaymentWhileItsAccountStaysLockedAndRecordItOnceTheLockIsReleased() throws SQLException {

        var merchant = account(EUR);

        try (var announcements = Announcements.fromNowOn(consumerFactory);
                var lockHolder = dataSource.getConnection();
                var lock = lockHolder.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {

            lockHolder.setAutoCommit(false);
            lock.setString(1, merchant.getId().toString());
            lock.executeQuery();

            send(payment(newPaymentId(), merchant, "10.00", EUR));
            await().during(Duration.ofSeconds(11))
                    .atMost(Duration.ofSeconds(12))
                    .until(() -> transactionRepository.count() == 0);
            lockHolder.rollback();

            assertThat(announcements.firstTransactionIds(1)).hasSize(1);
            assertThat(balance(merchant.getId())).isEqualByComparingTo("10.00");
        }
    }

    private void send(String message) {

        kafkaOperations.send("payment-settled", 0, null, message).join();
    }

    private static String payment(String paymentId, Account merchant, String amount, Currency currency) {

        return """
                {"eventId": "%s", "paymentId": "%s", "merchantAccountId": "%s", "amount": "%s", "currency": "%s"}"""
                .formatted(UUID.randomUUID(), paymentId, merchant.getId(), amount, currency);
    }

    private static String newPaymentId() {

        return UUID.randomUUID().toString();
    }

    private static String header(ConsumerRecord<String, String> deadLetter, String name) {

        return new String(deadLetter.headers().lastHeader(name).value(), UTF_8);
    }

    private BigDecimal balance(UUID accountId) {

        return accountRepository.findById(accountId)
                .orElseThrow()
                .getBalance();
    }

    private Account account(Currency currency) {

        var account = new Account();
        account.setCurrency(currency);
        account.setCreatedAt(Instant.now());
        return accountRepository.save(account);
    }
}
