package com.coremaker.ledger.unit.validation;

import static com.coremaker.ledger.exception.ErrorCode.INVALID_CURRENCY;
import static com.coremaker.ledger.exception.ErrorCode.NEGATIVE_OVERDRAFT_LIMIT;
import static com.coremaker.ledger.exception.ErrorCode.TOO_MANY_DECIMAL_PLACES;
import static com.coremaker.ledger.exception.ErrorCode.UNSUPPORTED_CURRENCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.coremaker.ledger.exception.ErrorCode;
import com.coremaker.ledger.exception.ValidationException;
import com.coremaker.ledger.validation.AccountValidator;

class AccountValidatorShould {

    private final AccountValidator validator = new AccountValidator();

    @Test
    void acceptASupportedCurrencyWithOrWithoutAnOverdraftLimit() {

        assertThatCode(() -> validator.validate("EUR", null)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("JPY", new BigDecimal("1000"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EURO", "ABC"})
    void refuseACodeThatIsNotIso4217(String code) {

        assertRefused(code, null, INVALID_CURRENCY);
    }

    @Test
    void refuseAnIsoCurrencyTheLedgerDoesNotSupport() {

        assertRefused("GBP", null, UNSUPPORTED_CURRENCY);
    }

    @Test
    void refuseANegativeOverdraftLimit() {

        assertRefused("EUR", new BigDecimal("-1"), NEGATIVE_OVERDRAFT_LIMIT);
    }

    @Test
    void refuseAnOverdraftLimitWithMoreDecimalsThanItsCurrency() {

        assertRefused("JPY", new BigDecimal("10.5"), TOO_MANY_DECIMAL_PLACES);
    }

    private void assertRefused(String currencyCode, BigDecimal overdraftLimit, ErrorCode code) {

        assertThatThrownBy(() -> validator.validate(currencyCode, overdraftLimit))
                .isInstanceOfSatisfying(ValidationException.class, refusal -> assertThat(refusal.getCode()).isEqualTo(code));
    }
}
