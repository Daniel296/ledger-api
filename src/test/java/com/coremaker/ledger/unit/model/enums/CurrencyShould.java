package com.coremaker.ledger.unit.model.enums;

import static com.coremaker.ledger.model.enums.Currency.EUR;
import static com.coremaker.ledger.model.enums.Currency.JPY;
import static com.coremaker.ledger.model.enums.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CurrencyShould {

    @Test
    void fitAmountsWithinItsDecimalPlaces() {

        assertThat(EUR.fits(new BigDecimal("125.50"))).isTrue();
        assertThat(EUR.fits(new BigDecimal("125.500"))).isTrue();
        assertThat(EUR.fits(new BigDecimal("125.505"))).isFalse();
        assertThat(USD.fits(new BigDecimal("0.01"))).isTrue();
        assertThat(USD.fits(new BigDecimal("0.001"))).isFalse();
        assertThat(JPY.fits(new BigDecimal("15000"))).isTrue();
        assertThat(JPY.fits(new BigDecimal("100.5"))).isFalse();
    }
}
