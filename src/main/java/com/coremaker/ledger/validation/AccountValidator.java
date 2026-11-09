package com.coremaker.ledger.validation;

import static com.coremaker.ledger.exception.ErrorCode.INVALID_CURRENCY;
import static com.coremaker.ledger.exception.ErrorCode.NEGATIVE_OVERDRAFT_LIMIT;
import static com.coremaker.ledger.exception.ErrorCode.TOO_MANY_DECIMAL_PLACES;
import static com.coremaker.ledger.exception.ErrorCode.UNSUPPORTED_CURRENCY;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.coremaker.ledger.exception.ValidationException;
import com.coremaker.ledger.model.enums.Currency;

@Component
public class AccountValidator {

    public void validate(String currencyCode, BigDecimal overdraftLimit) {

        var currency = supportedCurrency(currencyCode);
        if (overdraftLimit == null) {
            return;
        }

        checkNotNegative(overdraftLimit);
        checkDecimalPlaces(currency, overdraftLimit);
    }

    private Currency supportedCurrency(String code) {

        for (var currency : Currency.values()) {

            if (currency.name().equals(code)) {
                return currency;
            }
        }

        if (isIsoCode(code)) {
            throw new ValidationException(UNSUPPORTED_CURRENCY, "\"%s\" is not a currency the ledger supports".formatted(code));
        }

        throw new ValidationException(INVALID_CURRENCY,
                "\"%s\" is not an ISO 4217 currency code (three uppercase letters)".formatted(code));
    }

    private void checkNotNegative(BigDecimal overdraftLimit) {

        if (overdraftLimit.signum() < 0) {
            throw new ValidationException(NEGATIVE_OVERDRAFT_LIMIT, "The overdraft limit cannot be negative");
        }
    }

    private void checkDecimalPlaces(Currency currency, BigDecimal overdraftLimit) {

        if (!currency.fits(overdraftLimit)) {
            throw new ValidationException(TOO_MANY_DECIMAL_PLACES,
                    "Overdraft limit %s has more decimal places than %s allows (%d)"
                            .formatted(overdraftLimit.toPlainString(), currency, currency.getDecimals()));
        }
    }

    private boolean isIsoCode(String code) {

        try {
            java.util.Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
