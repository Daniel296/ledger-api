package com.coremaker.ledger.unit.validation;

import static com.coremaker.ledger.exception.ErrorCode.CURRENCY_MISMATCH;
import static com.coremaker.ledger.exception.ErrorCode.NON_POSITIVE_AMOUNT;
import static com.coremaker.ledger.exception.ErrorCode.OVERDRAFT_LIMIT_EXCEEDED;
import static com.coremaker.ledger.exception.ErrorCode.TOO_FEW_ENTRIES;
import static com.coremaker.ledger.exception.ErrorCode.TOO_MANY_DECIMAL_PLACES;
import static com.coremaker.ledger.exception.ErrorCode.UNBALANCED;
import static com.coremaker.ledger.exception.ErrorCode.UNKNOWN_ACCOUNT;
import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Currency.JPY;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.coremaker.ledger.exception.ErrorCode;
import com.coremaker.ledger.exception.LedgerException;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.Transaction;
import com.coremaker.ledger.model.enums.Currency;
import com.coremaker.ledger.model.enums.Direction;
import com.coremaker.ledger.validation.TransactionValidator;

class TransactionValidatorShould {

    private final TransactionValidator validator = new TransactionValidator();

    @Test
    void letAnAccountWithoutALimitGoAsFarBelowZeroAsNeeded() {

        var settlement = account(EUR, "0", "-125.50");
        settlement.setUnlimitedOverdraft(true);
        var merchant = account(EUR, "0", "0");

        assertAccepted(List.of(settlement, merchant), debit(settlement, "1000000.00"), credit(merchant, "1000000.00"));
    }

    @Test
    void letAnAccountGoDownToItsOverdraftLimitButNotPast() {

        var merchant = account(EUR, "100.00", "0");
        var payouts = account(EUR, "0", "0");
        var accounts = List.of(merchant, payouts);

        assertAccepted(accounts, debit(merchant, "100.00"), credit(payouts, "100.00"));
        assertRefused(OVERDRAFT_LIMIT_EXCEEDED, accounts, debit(merchant, "100.01"), credit(payouts, "100.01"));
    }

    @Test
    void reportOnlyTheFirstBrokenRule() {

        var onUnknownAccount = entry(UUID.randomUUID(), Direction.CREDIT, "10.00");

        assertRefused(TOO_FEW_ENTRIES, List.of(), onUnknownAccount);
    }

    @Test
    void judgeAnAccountPostedToTwiceOnItsNetChange() {

        var merchant = account(EUR, "0", "25.50");
        var payouts = account(EUR, "0", "0");

        assertAccepted(List.of(merchant, payouts),
                debit(merchant, "50.00"), credit(merchant, "30.00"), credit(payouts, "20.00"));
    }

    @Test
    void refuseDebitsAndCreditsThatDoNotBalance() {

        var merchant = account(EUR, "0", "125.50");
        var payouts = account(EUR, "0", "0");

        assertRefused(UNBALANCED, List.of(merchant, payouts), debit(merchant, "100.00"), credit(payouts, "99.00"));
    }

    @Test
    void refuseMoreDecimalPlacesThanTheCurrencyAllows() {

        var merchant = account(JPY, "0", "1000");
        var payouts = account(JPY, "0", "0");

        assertRefused(TOO_MANY_DECIMAL_PLACES, List.of(merchant, payouts), debit(merchant, "100.5"), credit(payouts, "100.5"));
    }

    @Test
    void refuseAccountsInDifferentCurrencies() {

        var euroMerchant = account(EUR, "0", "25.50");
        var yenMerchant = account(JPY, "0", "0");

        assertRefused(CURRENCY_MISMATCH, List.of(euroMerchant, yenMerchant),
                debit(euroMerchant, "10.00"), credit(yenMerchant, "10"));
    }

    @Test
    void refuseASingleEntry() {

        var merchant = account(EUR, "0", "0");

        assertRefused(TOO_FEW_ENTRIES, List.of(merchant), credit(merchant, "10.00"));
    }

    @Test
    void refuseAnUnknownAccount() {

        var merchant = account(EUR, "0", "25.50");
        var onUnknownAccount = entry(UUID.randomUUID(), Direction.CREDIT, "10.00");

        assertRefused(UNKNOWN_ACCOUNT, List.of(merchant), debit(merchant, "10.00"), onUnknownAccount);
    }

    @Test
    void refuseAnAmountThatIsNotPositive() {

        var merchant = account(EUR, "0", "25.50");
        var payouts = account(EUR, "0", "0");

        assertRefused(NON_POSITIVE_AMOUNT, List.of(merchant, payouts), debit(merchant, "0"), credit(payouts, "0"));
    }

    private void validate(List<Account> accounts, Entry... entries) {

        var transaction = new Transaction();
        transaction.setEntries(List.of(entries));
        var accountsById = accounts.stream()
                .collect(toMap(Account::getId, Function.identity()));
        validator.validate(transaction, accountsById);
    }

    private void assertAccepted(List<Account> accounts, Entry... entries) {

        assertThatCode(() -> validate(accounts, entries)).doesNotThrowAnyException();
    }

    private void assertRefused(ErrorCode code, List<Account> accounts, Entry... entries) {

        assertThatThrownBy(() -> validate(accounts, entries))
                .isInstanceOfSatisfying(LedgerException.class, refusal -> assertThat(refusal.getCode()).isEqualTo(code));
    }

    private static Account account(Currency currency, String overdraftLimit, String balance) {

        var account = new Account();
        account.setId(UUID.randomUUID());
        account.setCurrency(currency);
        account.setOverdraftLimit(new BigDecimal(overdraftLimit));
        account.setBalance(new BigDecimal(balance));
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
