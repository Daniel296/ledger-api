package com.coremaker.ledger.validation;

import static com.coremaker.ledger.exception.ErrorCode.CURRENCY_MISMATCH;
import static com.coremaker.ledger.exception.ErrorCode.NON_POSITIVE_AMOUNT;
import static com.coremaker.ledger.exception.ErrorCode.OVERDRAFT_LIMIT_EXCEEDED;
import static com.coremaker.ledger.exception.ErrorCode.TOO_FEW_ENTRIES;
import static com.coremaker.ledger.exception.ErrorCode.TOO_MANY_DECIMAL_PLACES;
import static com.coremaker.ledger.exception.ErrorCode.UNBALANCED;
import static com.coremaker.ledger.exception.ErrorCode.UNKNOWN_ACCOUNT;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.coremaker.ledger.exception.ValidationException;
import com.coremaker.ledger.model.Account;
import com.coremaker.ledger.model.Currency;
import com.coremaker.ledger.model.Direction;
import com.coremaker.ledger.model.Entry;
import com.coremaker.ledger.model.Transaction;

@Component
public class TransactionValidator {

    public void validate(Transaction transaction, Map<UUID, Account> accounts) {

        var entries = transaction.getEntries();
        checkAtLeastTwoEntries(entries);
        checkAmountsArePositive(entries);
        checkAccountsExist(entries, accounts);
        checkSameCurrency(entries, accounts);
        checkDecimalPlaces(entries, accounts);
        checkDebitsEqualCredits(entries);
        checkOverdraftLimits(entries, accounts);
    }

    private void checkAtLeastTwoEntries(List<Entry> entries) {

        if (entries.size() < 2) {
            throw new ValidationException(TOO_FEW_ENTRIES, "A transaction needs at least two entries");
        }
    }

    private void checkAmountsArePositive(List<Entry> entries) {

        for (var entry : entries) {

            if (entry.getAmount().signum() <= 0) {
                throw new ValidationException(NON_POSITIVE_AMOUNT,
                        "Amount %s must be positive".formatted(entry.getAmount().toPlainString()));
            }
        }
    }

    private void checkAccountsExist(List<Entry> entries, Map<UUID, Account> accounts) {

        for (var entry : entries) {

            if (!accounts.containsKey(entry.getAccountId())) {
                throw new ValidationException(UNKNOWN_ACCOUNT, "Account %s does not exist".formatted(entry.getAccountId()));
            }
        }
    }

    private void checkSameCurrency(List<Entry> entries, Map<UUID, Account> accounts) {

        var currency = currencyOf(entries.get(0), accounts);
        for (var entry : entries) {

            var entryCurrency = currencyOf(entry, accounts);
            if (entryCurrency != currency) {
                throw new ValidationException(CURRENCY_MISMATCH,
                        "Account %s is in %s, the transaction is in %s"
                                .formatted(entry.getAccountId(), entryCurrency, currency));
            }
        }
    }

    private void checkDecimalPlaces(List<Entry> entries, Map<UUID, Account> accounts) {

        for (var entry : entries) {

            var currency = currencyOf(entry, accounts);
            if (!currency.fits(entry.getAmount())) {
                throw new ValidationException(TOO_MANY_DECIMAL_PLACES,
                        "Amount %s has more decimal places than %s allows (%d)"
                                .formatted(entry.getAmount().toPlainString(), currency, currency.getDecimals()));
            }
        }
    }

    private void checkDebitsEqualCredits(List<Entry> entries) {

        var debits = total(entries, Direction.DEBIT);
        var credits = total(entries, Direction.CREDIT);
        if (debits.compareTo(credits) != 0) {
            throw new ValidationException(UNBALANCED,
                    "Debits total %s but credits total %s".formatted(debits.toPlainString(), credits.toPlainString()));
        }
    }

    private void checkOverdraftLimits(List<Entry> entries, Map<UUID, Account> accounts) {

        for (var entry : entries) {

            var account = accounts.get(entry.getAccountId());
            var balanceAfter = account.getBalance().add(netChange(account, entries));
            if (!account.isUnlimitedOverdraft() && balanceAfter.compareTo(account.getOverdraftLimit().negate()) < 0) {
                throw new ValidationException(OVERDRAFT_LIMIT_EXCEEDED,
                        "Account %s would go to %s, past its overdraft limit of %s".formatted(
                                account.getId(), balanceAfter.toPlainString(), account.getOverdraftLimit().toPlainString()));
            }
        }
    }

    // All the transaction's entries on the account count, so an account posted to twice is judged on the sum
    private BigDecimal netChange(Account account, List<Entry> entries) {

        return entries.stream()
                .filter(entry -> entry.getAccountId().equals(account.getId()))
                .map(entry -> entry.getDirection().signed(entry.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal total(List<Entry> entries, Direction direction) {

        return entries.stream()
                .filter(entry -> entry.getDirection() == direction)
                .map(Entry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Currency currencyOf(Entry entry, Map<UUID, Account> accounts) {

        return accounts.get(entry.getAccountId()).getCurrency();
    }
}
