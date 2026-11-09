package com.coremaker.ledger.unit.validation;

import static com.coremaker.ledger.exception.ErrorCode.CURRENCY_MISMATCH;
import static com.coremaker.ledger.exception.ErrorCode.NON_POSITIVE_AMOUNT;
import static com.coremaker.ledger.exception.ErrorCode.OVERDRAFT_LIMIT_EXCEEDED;
import static com.coremaker.ledger.exception.ErrorCode.TOO_FEW_ENTRIES;
import static com.coremaker.ledger.exception.ErrorCode.TOO_MANY_DECIMAL_PLACES;
import static com.coremaker.ledger.exception.ErrorCode.UNBALANCED;
import static com.coremaker.ledger.exception.ErrorCode.UNKNOWN_ACCOUNT;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.coremaker.ledger.exception.ErrorCode;
import com.coremaker.ledger.exception.ValidationException;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.Currency;
import com.coremaker.ledger.model.Direction;
import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.Transaction;
import com.coremaker.ledger.validation.TransactionValidator;

class TransactionValidatorShould {

    private final TransactionValidator validator = new TransactionValidator();

    // Balances as after examples 1 and 2 of the challenge
    private final Account settlementEur = unlimited(account(Currency.EUR, "0", "-125.50"));
    private final Account merchant42 = account(Currency.EUR, "0", "25.50");
    private final Account payoutsEur = account(Currency.EUR, "0", "98.50");
    private final Account merchant7 = account(Currency.JPY, "0", "0");
    private final Account payoutsJpy = account(Currency.JPY, "0", "0");
    private final Account overdrawableEur = account(Currency.EUR, "100.00", "0");
    private final Map<UUID, Account> accounts = Stream.of(settlementEur, merchant42, payoutsEur, merchant7, payoutsJpy, overdrawableEur)
            .collect(toMap(Account::getId, Function.identity()));

    @Test
    void letAnAccountWithoutALimitGoAsFarBelowZeroAsNeeded() {

        assertAccepted(debit(settlementEur, "1000000.00"), credit(merchant42, "1000000.00"));
    }

    @Test
    void letAnAccountGoDownToItsOverdraftLimitButNotPast() {

        assertAccepted(debit(overdrawableEur, "100.00"), credit(payoutsEur, "100.00"));
        assertRefused(OVERDRAFT_LIMIT_EXCEEDED, debit(overdrawableEur, "100.01"), credit(payoutsEur, "100.01"));
    }

    @Test
    void reportOnlyTheFirstBrokenRule() {

        var unknown = entry(UUID.randomUUID(), Direction.CREDIT, "10.00");

        assertRefused(TOO_FEW_ENTRIES, unknown);
    }

    @Test
    void judgeAnAccountPostedToTwiceOnItsNetChange() {

        assertAccepted(debit(merchant42, "50.00"), credit(merchant42, "30.00"), credit(payoutsEur, "20.00"));
    }

    @Test
    void refuseDebitsAndCreditsThatDoNotBalance() {

        assertRefused(UNBALANCED, debit(merchant42, "100.00"), credit(payoutsEur, "99.00"));
    }

    @Test
    void refuseTakingAnAccountPastItsOverdraftLimit() {

        assertThatThrownBy(() -> validate(debit(merchant42, "50.00"), credit(payoutsEur, "50.00")))
                .isInstanceOfSatisfying(ValidationException.class, refusal -> assertThat(refusal.getCode()).isEqualTo(OVERDRAFT_LIMIT_EXCEEDED))
                .hasMessage("Account %s would go to -24.50, past its overdraft limit of 0", merchant42.getId());
    }

    @Test
    void refuseMoreDecimalPlacesThanTheCurrencyAllows() {

        assertRefused(TOO_MANY_DECIMAL_PLACES, debit(merchant7, "100.5"), credit(payoutsJpy, "100.5"));
    }

    @Test
    void refuseAccountsInDifferentCurrencies() {

        assertRefused(CURRENCY_MISMATCH, debit(merchant42, "10.00"), credit(merchant7, "10"));
    }

    @Test
    void refuseASingleEntry() {

        assertRefused(TOO_FEW_ENTRIES, credit(merchant42, "10.00"));
    }

    @Test
    void refuseAnUnknownAccount() {

        var unknown = entry(UUID.randomUUID(), Direction.CREDIT, "10.00");

        assertRefused(UNKNOWN_ACCOUNT, debit(settlementEur, "10.00"), unknown);
    }

    @Test
    void refuseAnAmountThatIsNotPositive() {

        assertRefused(NON_POSITIVE_AMOUNT, debit(settlementEur, "0"), credit(merchant42, "0"));
    }

    private void validate(Entry... entries) {

        var transaction = new Transaction();
        transaction.setEntries(List.of(entries));
        validator.validate(transaction, accounts);
    }

    private void assertAccepted(Entry... entries) {

        assertThatCode(() -> validate(entries)).doesNotThrowAnyException();
    }

    private void assertRefused(ErrorCode code, Entry... entries) {

        assertThatThrownBy(() -> validate(entries)).isInstanceOfSatisfying(ValidationException.class, refusal -> assertThat(refusal.getCode()).isEqualTo(code));
    }

    private static Account account(Currency currency, String overdraftLimit, String balance) {

        var account = new Account();
        account.setId(UUID.randomUUID());
        account.setCurrency(currency);
        account.setOverdraftLimit(new BigDecimal(overdraftLimit));
        account.setBalance(new BigDecimal(balance));
        return account;
    }

    private static Account unlimited(Account account) {

        account.setUnlimitedOverdraft(true);
        return account;
    }

    private static Entry debit(Account account, String amount) {

        return entry(account.getId(), Direction.DEBIT, amount);
    }

    private static Entry credit(Account account, String amount) {

        return entry(account.getId(), Direction.CREDIT, amount);
    }

    private static Entry entry(UUID accountId, Direction direction, String amount) {

        var entry = new Entry();
        entry.setAccountId(accountId);
        entry.setDirection(direction);
        entry.setAmount(new BigDecimal(amount));
        return entry;
    }
}
